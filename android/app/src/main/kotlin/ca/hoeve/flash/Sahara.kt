package ca.hoeve.flash

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun pack(vararg v: Int): ByteArray =
    ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach(::putInt) }.array()

internal fun ByteArray.u32(o: Int) = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getInt(o)
internal fun ByteArray.u64(o: Int) = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getLong(o)
internal fun ByteArray.text() = String(this, Charsets.UTF_8)

/** Sahara protocol: uploads the Firehose programmer (port of qdl.js sahara.js). */
class Sahara(private val usb: Link, private val programmer: ByteArray) {
    var serial = ""
        private set

    /** @return "sahara", "firehose" or "error" */
    fun connect(): String {
        var r = usb.readFully(48, 500)
        if (r.size > 1) {
            if (r[0].toInt() == HELLO_REQ) {
                val cmd = r.u32(0)
                if (cmd == HELLO_REQ || cmd == END_TRANSFER) return "sahara"
                error("Sahara - Connect failed: unknown command")
            }
            if ("<?xml" in r.text()) return "firehose"
        } else {
            r = runCatching { usb.write(Firehose.xml("nop"), timeout = 1000); usb.read(2000) }.getOrDefault(r)
            if ("<?xml" in r.text()) return "firehose"
            if (r.isNotEmpty() && r[0].toInt() == END_TRANSFER) return "sahara"
        }
        return "error"
    }

    private fun hello(mode: Int) = usb.write(pack(HELLO_RSP, 0x30, 2, 1, 0, mode, 1, 2, 3, 4, 5, 6))

    private fun response(): ByteArray? {
        val d = usb.read(5000)
        return if (d.size < 8 || "<?xml" in d.text()) null else d
    }

    private fun exec(cmd: Int): ByteArray {
        usb.write(pack(EXECUTE_REQ, 0xC, cmd))
        val r = response()
        check(r?.u32(0) == EXECUTE_RSP) { "Sahara - error while executing command" }
        val n = r!!.u32(12)
        check(n in 1..4096) { "Sahara - bad execute response length $n" }
        usb.write(pack(EXECUTE_DATA, 0xC, cmd))
        return usb.readFully(n)
    }

    fun uploadLoader(): String {
        hello(MODE_COMMAND)
        check(response()?.u32(0) == CMD_READY) { "Sahara - Failed to enter command mode" }
        serial = "0x%08x".format(exec(SERIAL_NUM_READ).u32(0))
        usb.write(pack(SWITCH_MODE, 0xC, MODE_COMMAND))
        // Wait for the fresh hello. qdl.js re-runs connect() here, which can send an XML nop into Sahara if the hello is slow.
        usb.readFully(48, 5000)
        hello(MODE_IMAGE_TX_PENDING)
        while (true) {
            val r = response() ?: error("Sahara - Timeout while uploading loader. Wrong loader?")
            when (r.u32(0)) {
                READ_DATA_64 -> {
                    check(r.u64(8) >= 0xC) { "Sahara - Unknown sahara id" }
                    val off = r.u64(16)
                    val len = r.u64(24)
                    check(off in 0..Int.MAX_VALUE && len in 1..MAX_READ) { "Sahara - bad read request ($off, $len)" }
                    if (off + len <= programmer.size) usb.write(programmer, off.toInt(), len.toInt())
                    else usb.write(programmer.copyOfRange(minOf(off.toInt(), programmer.size), programmer.size).copyOf(len.toInt()))
                }
                END_TRANSFER -> {
                    check(r.u32(12) == 0) { "Sahara - Loader transfer failed (status ${r.u32(12)})" }
                    usb.write(pack(DONE_REQ, 0x8))
                    check(response()?.u32(0) == DONE_RSP) { "Sahara - Failed to upload loader" }
                    return "firehose"
                }
            }
        }
    }

    private companion object {
        const val HELLO_REQ = 0x1
        const val HELLO_RSP = 0x2
        const val END_TRANSFER = 0x4
        const val DONE_REQ = 0x5
        const val DONE_RSP = 0x6
        const val CMD_READY = 0xB
        const val SWITCH_MODE = 0xC
        const val EXECUTE_REQ = 0xD
        const val EXECUTE_RSP = 0xE
        const val EXECUTE_DATA = 0xF
        const val READ_DATA_64 = 0x12
        const val SERIAL_NUM_READ = 0x1
        const val MODE_IMAGE_TX_PENDING = 0x0
        const val MODE_COMMAND = 0x3
        const val MAX_READ = 1L shl 20
    }
}
