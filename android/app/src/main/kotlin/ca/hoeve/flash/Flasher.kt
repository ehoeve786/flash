package ca.hoeve.flash

import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Full flash sequence (port of flash.comma.ai FlashManager). [status] progress is 0..1, or -1 for indeterminate. */
class Flasher(
    private val qdl: Qdl,
    private val dir: File,
    private val status: (String, Double) -> Unit,
    private val log: (String) -> Unit,
) {
    private class Image(j: JSONObject) {
        val name: String = j.getString("name")
        val size = j.getLong("size")
        val hasAB = j.getBoolean("has_ab")
        val gptLun = j.optJSONObject("gpt")?.getInt("lun")
        val url: String = j.getString("url")
    }

    fun run() {
        dir.listFiles()?.forEach(File::delete)

        status("Connecting", -1.0)
        qdl.connect()
        log("Connected ${qdl.serial}")
        val type = qdl.fh.deviceType()
        val url = MANIFESTS[type] ?: error("Unknown device type $type")
        val images = JSONArray(URL(url).readText()).let { a -> List(a.length()) { Image(a.getJSONObject(it)) } }
        check(images.isNotEmpty()) { "Manifest is empty" }

        val info = JSONObject(qdl.fh.storageInfo()).getJSONObject("storage_info")
        val userdata = userdataImage(info)
        log("Device %08x (type $type), $userdata".format(info.getLong("serial_num")))

        // Repair partition tables
        val gpts = images.filter { it.gptLun != null }
        check(gpts.isNotEmpty()) { "No GPT images found" }
        for (im in gpts) {
            val f = download(im)
            RandomAccessFile(f, "r").channel.use { qdl.repairGpt(im.gptLun!!, FileSrc(it, 0, it.size())) }
            f.delete()
        }

        // Erase everything except mbr, gpt and persist
        status("Erasing device", -1.0)
        val (persistLun, persist) = qdl.detect("persist") ?: error("Could not find \"persist\" partition")
        check(persistLun == 0 && persist.start == 8L && persist.sectors == 8192L) { "\"persist\" partition has unexpected layout" }
        for (lun in Qdl.LUNS) qdl.eraseLun(lun, if (lun == persistLun) listOf("mbr", "gpt", "persist") else listOf("mbr", "gpt"))

        // Flash system images
        val sys = images.filter { it.gptLun == null && it.name != "persist" && (!it.name.startsWith("userdata_") || it.name == userdata) }
        check(sys.any { it.name == userdata }) { "Did not find userdata image \"$userdata\"" }
        for (im in sys) {
            val f = download(im)
            val part = if (im.name.startsWith("userdata_")) "userdata" else im.name
            for (slot in if (im.hasAB) listOf("_a", "_b") else listOf("")) {
                val label = "Flashing $part$slot"
                status(label, 0.0)
                qdl.flash(part + slot, f) { status(label, it.toDouble() / im.size) }
                log("Flashed $part$slot")
            }
            f.delete()
        }

        status("Finalizing", -1.0)
        qdl.setActiveSlot('a')
        status("Rebooting", -1.0)
        qdl.fh.reset()
        status("Done — the device will reboot into setup", 1.0)
    }

    private fun download(im: Image): File {
        val f = File(dir, "${im.name}.img")
        val label = "Downloading ${im.name}"
        status(label, 0.0)
        val http = HttpIn(im.url) { status(label, it) }
        (if (im.url.endsWith(".xz")) XZInputStream(http.buffered(1 shl 16)) else http).use { inp ->
            f.outputStream().use { inp.copyTo(it, 1 shl 20) }
        }
        return f
    }

    /** Resumes with HTTP Range requests on network errors (like stream.js). */
    private class HttpIn(private val url: String, private val onProgress: (Double) -> Unit) : InputStream() {
        private var pos = 0L
        private var total = -1L
        private var s: InputStream? = null

        private fun open(): InputStream {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (pos > 0) c.setRequestProperty("Range", "bytes=$pos-")
            val code = c.responseCode
            if (code != 200 && code != 206 || pos > 0 && code != 206) throw IOException("HTTP $code for $url")
            if (total < 0) total = c.contentLengthLong
            return c.inputStream.also { s = it }
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var attempt = 0
            while (true) {
                try {
                    val n = (s ?: open()).read(b, off, len)
                    if (n > 0) {
                        pos += n
                        if (total > 0) onProgress(pos.toDouble() / total)
                    }
                    return n
                } catch (e: IOException) {
                    runCatching { s?.close() }
                    s = null
                    if (++attempt > 3) throw e
                    Thread.sleep(1000L * attempt)
                }
            }
        }

        override fun close() { s?.close() }
    }

    companion object {
        private const val BASE = "https://raw.githubusercontent.com/commaai/openpilot"
        private val MANIFESTS = mapOf(
            32 to "$BASE/release-tici/system/hardware/tici/all-partitions.json",
            33 to "$BASE/release-tizi/system/hardware/tici/all-partitions.json",
            34 to "$BASE/release-mici/system/hardware/tici/all-partitions.json",
        )
        private val SANDISK_128 = Regex("^SDINDDH4-128G(?: +\\d+)?$")

        /** Picks the userdata image for the UFS chip (checkCompatibleDevice). */
        fun userdataImage(i: JSONObject): String {
            check(i.getInt("block_size") == 4096 && i.getInt("page_size") == 4096 && i.getInt("num_physical") == 6 &&
                i.getString("mem_type") == "UFS") { "UFS chip parameters mismatch" }
            val prod = i.getString("prod_name")
            val mid = i.getInt("manufacturer_id")
            val blocks = i.getLong("total_blocks")
            return when {
                mid == 429 && blocks == 14145536L && (prod == "H28S7Q302BMR" || prod == "H28U74301AMR") -> "userdata_30"
                mid == 300 && blocks == 14143488L && Regex("64GB-UFS-MT( +)8QSP").containsMatchIn(prod) -> "userdata_30"
                mid == 325 && blocks == 29605888L && SANDISK_128.matches(prod) -> "userdata_89"
                mid == 325 && blocks == 29775872L && SANDISK_128.matches(prod) -> "userdata_90"
                else -> error("Could not identify UFS chip ($prod)")
            }
        }
    }
}
