package ca.hoeve.flash

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Android sparse image reader (port of qdl.js sparse.js). */
object Sparse {
    private const val MAGIC = 0xed26ff3a.toInt()
    private const val RAW = 0xCAC1
    private const val FILL = 0xCAC2
    private const val SKIP = 0xCAC3
    private const val CRC32 = 0xCAC4

    private fun read(ch: FileChannel, pos: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        while (b.hasRemaining() && ch.read(b, pos + b.position()) > 0) Unit
        return b
    }

    /** (byte offset, data) of every chunk that must be written; zero fills and skips are omitted. Null if not sparse. */
    fun chunks(ch: FileChannel): List<Pair<Long, Src>>? {
        val size = ch.size()
        if (size < 28) return null
        val h = read(ch, 0, 28)
        if (h.getInt(0) != MAGIC || h.getShort(8).toInt() != 28 || h.getShort(10).toInt() != 12) return null
        val blockSize = h.getInt(12).toLong() and 0xFFFFFFFFL
        val out = ArrayList<Pair<Long, Src>>()
        var pos = 28L
        var offset = 0L
        repeat(h.getInt(20)) {
            check(pos + 12 <= size) { "Sparse - Chunk header out of bounds" }
            val c = read(ch, pos, 12)
            val len = (c.getInt(4).toLong() and 0xFFFFFFFFL) * blockSize
            val total = c.getInt(8).toLong() and 0xFFFFFFFFL
            check(total >= 12 && pos + total <= size) { "Sparse - Chunk data out of bounds" }
            when (val type = c.getShort(0).toInt() and 0xFFFF) {
                RAW -> {
                    check(total - 12 == len) { "Sparse - Raw chunk size mismatch" }
                    out += offset to FileSrc(ch, pos + 12, len)
                    offset += len
                }
                FILL -> {
                    check(total == 16L) { "Sparse - Bad fill chunk" }
                    val p = read(ch, pos + 12, 4).array()
                    if (p.any { it.toInt() != 0 }) out += offset to FillSrc(p, len)
                    offset += len
                }
                SKIP -> offset += len
                CRC32 -> Unit
                else -> error("Sparse - Unhandled chunk type 0x%x".format(type))
            }
            pos += total
        }
        return out
    }
}
