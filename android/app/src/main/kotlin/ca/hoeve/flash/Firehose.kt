package ca.hoeve.flash

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access data to program. */
interface Src {
    val size: Long
    fun read(pos: Long, dst: ByteArray, len: Int)
}

class FileSrc(private val ch: FileChannel, private val base: Long, override val size: Long) : Src {
    override fun read(pos: Long, dst: ByteArray, len: Int) {
        val bb = ByteBuffer.wrap(dst, 0, len)
        var p = base + pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n < 0) throw EOFException()
            p += n
        }
    }
}

class BytesSrc(private val b: ByteArray) : Src {
    override val size get() = b.size.toLong()
    override fun read(pos: Long, dst: ByteArray, len: Int) = System.arraycopy(b, pos.toInt(), dst, 0, len)
}

/** Repeats a 4-byte sparse fill pattern. */
class FillSrc(private val pattern: ByteArray, override val size: Long) : Src {
    override fun read(pos: Long, dst: ByteArray, len: Int) {
        val s = (pos and 3).toInt()
        for (i in 0 until len) dst[i] = pattern[(s + i) and 3]
    }
}

/** Firehose XML protocol (port of qdl.js firehose.js). */
class Firehose(private val usb: Link, private val log: (String) -> Unit) {
    class Reply(val rsp: Map<String, String>, val logs: List<String>) {
        val ack get() = rsp["value"] == "ACK"
        /** Mirrors qdl.js xmlSend(): only a NAK with rawmode="false" counts as failure. */
        val ok get() = rsp["rawmode"] != "false" || "value" !in rsp || ack || rsp["value"] == "true"
    }

    private val buf = ByteArray(MAX_PAYLOAD)

    fun waitForData(retries: Int = 3): Reply {
        val out = ByteArrayOutputStream()
        var misses = 0
        while ("<response" !in out.toString(Charsets.UTF_8.name())) {
            val c = usb.read(1000)
            if (c.isEmpty()) {
                if (++misses > retries) break
                continue
            }
            misses = 0
            out.write(c)
        }
        return parse(out.toByteArray()).also { r -> r.logs.forEach { log("device: $it") } }
    }

    private fun send(cmd: ByteArray, retries: Int = 3): Reply {
        usb.write(cmd, 0, minOf(cmd.size, MAX_XML), 1000)
        return waitForData(retries)
    }

    /** Reads and logs whatever the device has queued (e.g. the loader's startup banner) until it goes quiet. */
    private fun drain() {
        val out = ByteArrayOutputStream()
        while (true) out.write(usb.read(300).takeIf { it.isNotEmpty() } ?: break)
        parse(out.toByteArray()).logs.forEach { log("device: $it") }
    }

    fun configure() {
        val cmd = xml("configure", "MemoryName" to "UFS", "Verbose" to 0, "AlwaysValidate" to 0,
            "MaxDigestTableSizeInBytes" to 2048, "MaxPayloadSizeToTargetInBytes" to MAX_PAYLOAD,
            "ZLPAwareHost" to 1, "SkipStorageInit" to 0, "SkipWrite" to 0)
        // The loader blocks until its startup banner is read and won't accept commands meanwhile.
        // (qdl.js gets away with an un-awaited write because WebUSB reads concurrently.)
        var sent = false
        for (attempt in 1..3) {
            drain()
            sent = runCatching { usb.write(cmd, timeout = 2000) }.isSuccess
            if (sent) break
            log("debug: configure not accepted yet (attempt $attempt/3)")
        }
        check(sent) { "Firehose configure: device is not accepting commands" }
        var r = waitForData()
        if ("MemoryName" !in r.rsp) r = waitForData().let { Reply(r.rsp + it.rsp, r.logs + it.logs) }
        check(r.rsp.isNotEmpty()) { "Firehose configure: no response from device" }
        check(r.ack) { "Firehose configure: negative response ${r.rsp}" }
        check(r.logs.any { "Calling handler for configure" in it }) { "Failed to configure: handler not called" }
        check(r.logs.any { "Storage type set to value UFS" in it }) { "Failed to configure: storage type not set" }
    }

    fun readBuffer(lun: Int, start: Long, sectors: Int): ByteArray {
        log("debug: read LUN $lun @$start ×$sectors")
        usb.write(xml("read", "SECTOR_SIZE_IN_BYTES" to SECTOR, "num_partition_sectors" to sectors,
            "physical_partition_number" to lun, "start_sector" to start))
        val r = waitForData(1)
        check(r.ack && r.rsp["rawmode"] == "true") { "Failed to read LUN $lun sector $start: ${r.rsp}" }
        val data = usb.readFully(SECTOR * sectors, 2000)
        check(data.size == SECTOR * sectors) { "Failed to read buffer: timed out" }
        check(waitForData().ack) { "Failed to read buffer: negative response code" }
        return data
    }

    fun program(lun: Int, start: Long, src: Src, onProgress: ((Long) -> Unit)? = null): Boolean {
        val total = src.size
        log("debug: program LUN $lun @$start ×${(total + SECTOR - 1) / SECTOR} ($total bytes)")
        val r0 = send(xml("program", "SECTOR_SIZE_IN_BYTES" to SECTOR, "num_partition_sectors" to (total + SECTOR - 1) / SECTOR,
            "physical_partition_number" to lun, "start_sector" to start))
        // Stricter than qdl.js (which only rejects NAK+rawmode=false): never stream data the device did not ACK.
        if (!r0.ack) return false.also { log("Program LUN $lun sector $start refused: ${r0.rsp}") }
        var off = 0L
        var i = 0
        while (off < total) {
            val w = minOf(total - off, MAX_PAYLOAD.toLong()).toInt()
            src.read(off, buf, w)
            val padded = (w + SECTOR - 1) / SECTOR * SECTOR
            buf.fill(0, w, padded)
            usb.write(buf, 0, padded)
            usb.zlp()
            off += w
            if (i++ % 10 == 0) onProgress?.invoke(off)
        }
        onProgress?.invoke(total)
        val r = waitForData(10)
        if (!r.ack) log("Program LUN $lun sector $start failed: ${r.rsp}")
        return r.ack
    }

    fun erase(lun: Int, start: Long, sectors: Int) {
        log("debug: erase LUN $lun @$start ×$sectors")
        val r = send(xml("erase", "SECTOR_SIZE_IN_BYTES" to SECTOR, "num_partition_sectors" to sectors,
            "physical_partition_number" to lun, "start_sector" to start), 30)
        check(r.ack) { "Failed to erase LUN $lun sector $start: ${r.rsp}" }
    }

    fun setBootLun(lun: Int) = check(send(xml("setbootablestoragedrive", "value" to lun)).ok) { "Failed to set boot lun $lun" }

    fun fixGpt(lun: Int, grow: Int) = check(send(xml("fixgpt", "lun" to lun, "grow_last_partition" to grow)).ok) { "Failed to fix gpt" }

    /** [mode]: "reset", "reset_to_edl" or "off". */
    fun reset(mode: String = "reset") {
        check(send(xml("power", "value" to mode)).ok) { "Power $mode failed" }
        runCatching { waitForData() }
    }

    /** JSON text of the storage info log line. */
    fun storageInfo(): String =
        send(xml("getstorageinfo", "physical_partition_number" to 0)).logs.firstOrNull { "storage_info" in it }
            ?.substring("INFO: ".length) ?: error("Storage info JSON not returned")

    fun deviceType(): Int? = send(xml("devicetype")).rsp["DeviceType"]?.toIntOrNull()

    companion object {
        const val SECTOR = 4096
        const val MAX_PAYLOAD = 1 shl 20
        private const val MAX_XML = 4096
        private val TAG = Regex("""<(response|log)((?:\s+[\w:.-]+\s*=\s*"[^"]*")*)\s*/?>""")
        private val ATTR = Regex("""([\w:.-]+)\s*=\s*"([^"]*)"""")
        private val NUM = Regex("&#(x?)([0-9a-fA-F]+);")

        fun xml(tag: String, vararg a: Pair<String, Any>) =
            "<?xml version=\"1.0\" ?><data><$tag${a.joinToString("") { " ${it.first}=\"${it.second}\"" }} /></data>".toByteArray()

        fun parse(data: ByteArray): Reply {
            val rsp = HashMap<String, String>()
            val logs = ArrayList<String>()
            for (m in TAG.findAll(data.text())) {
                val attrs = ATTR.findAll(m.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
                if (m.groupValues[1] == "response") rsp += attrs else attrs["value"]?.let(logs::add)
            }
            return Reply(rsp, logs)
        }

        private fun unescape(s: String) = if ('&' !in s) s else s
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(NUM) { it.groupValues[2].toInt(if (it.groupValues[1].isEmpty()) 10 else 16).toChar().toString() }
            .replace("&amp;", "&")
    }
}
