package ca.hoeve.flash

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.ByteArrayOutputStream
import java.io.IOException

private val EMPTY = ByteArray(0)

/** Bulk pipe to the device; abstracted so the protocol code can be unit tested. */
interface Link {
    /** One transfer of up to [len] bytes (0 = one packet). Empty on timeout or ZLP. */
    fun read(timeout: Int = 5000, len: Int = 0): ByteArray

    /** Writes [len] bytes; len 0 sends a zero-length packet. */
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size, timeout: Int = 10000)
}

/** Reads until [len] bytes arrive or a transfer comes back empty. */
fun Link.readFully(len: Int, timeout: Int = 5000): ByteArray {
    val out = ByteArrayOutputStream(len)
    while (out.size() < len) {
        val c = read(timeout, len - out.size())
        if (c.isEmpty()) break
        out.write(c)
    }
    return out.toByteArray()
}

fun Link.zlp() = write(EMPTY)

class UsbLink(private val conn: UsbDeviceConnection, intf: UsbInterface) : Link {
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint

    init {
        require(intf.endpointCount == 2) { "USB interface is not a QDL interface" }
        val eps = List(2, intf::getEndpoint)
        require(eps.all { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }) { "USB endpoints are not bulk" }
        epIn = eps.single { it.direction == UsbConstants.USB_DIR_IN }
        epOut = eps.single { it.direction == UsbConstants.USB_DIR_OUT }
        check(conn.claimInterface(intf, true)) { "Could not claim USB interface" }
    }

    private val pkt = epIn.maxPacketSize

    override fun read(timeout: Int, len: Int): ByteArray {
        val n = if (len <= 0) pkt else minOf((len + pkt - 1) / pkt * pkt, CHUNK)
        val b = ByteArray(n)
        val r = conn.bulkTransfer(epIn, b, n, timeout)
        return if (r > 0) b.copyOf(r) else EMPTY
    }

    override fun write(b: ByteArray, off: Int, len: Int, timeout: Int) {
        if (len == 0) {
            if (conn.bulkTransfer(epOut, b, 0, 0, timeout) < 0) throw IOException("USB write failed")
            return
        }
        var o = off
        val end = off + len
        while (o < end) {
            val n = conn.bulkTransfer(epOut, b, o, minOf(end - o, CHUNK), timeout)
            if (n <= 0) throw IOException("USB write failed (device disconnected?)")
            o += n
        }
    }

    private companion object {
        // Same transfer size as qdl.js; larger URBs need large kernel allocations.
        const val CHUNK = 16384
    }
}
