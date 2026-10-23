package ca.hoeve.flash

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class Part(val name: String, val start: Long, val end: Long, val off: Int) {
    val sectors get() = end - start + 1
}

/** GPT header (92 bytes) plus raw partition entry array (port of qdl.js gpt.js). */
class Gpt(header: ByteArray) {
    private val h = header.copyOf(HEADER_SIZE)
    var entries = ByteArray(0)

    private fun hb() = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
    private fun eb() = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)

    var currentLba: Long
        get() = hb().getLong(24)
        set(v) { hb().putLong(24, v) }
    var alternateLba: Long
        get() = hb().getLong(32)
        set(v) { hb().putLong(32, v) }
    val firstUsableLba get() = hb().getLong(40)
    val lastUsableLba get() = hb().getLong(48)
    var partEntriesStartLba: Long
        get() = hb().getLong(72)
        set(v) { hb().putLong(72, v) }
    val numEntries get() = hb().getInt(80)
    val entrySize get() = hb().getInt(84)
    val entriesCrc get() = hb().getInt(88)
    val entriesBytes get() = numEntries * entrySize
    val entriesSectors get() = ((entriesBytes.toLong() + Firehose.SECTOR - 1) / Firehose.SECTOR).toInt()

    /** Signature, revision and header size are sane. */
    val valid get() = String(h, 0, 8, Charsets.US_ASCII) == "EFI PART" && hb().getInt(8) == 0x10000 &&
        hb().getInt(12) in HEADER_SIZE..Firehose.SECTOR && numEntries in 1..4096 && entrySize in 128..4096

    /** Valid with matching header and entries CRCs. */
    val ok get() = valid && crc(h.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0) }) == hb().getInt(16) &&
        crc(entries) == entriesCrc

    fun partitions(): List<Part> {
        val e = eb()
        return (0 until numEntries).mapNotNull { i ->
            val o = i * entrySize
            if ((o until o + 16).all { entries[it].toInt() == 0 }) null
            else Part(name(o), e.getLong(o + 32), e.getLong(o + 40), o)
        }
    }

    fun find(name: String) = partitions().firstOrNull { it.name == name }

    private fun name(o: Int): String {
        val sb = StringBuilder()
        var i = o + 56
        while (i < o + 128) {
            val c = (entries[i].toInt() and 0xFF) or ((entries[i + 1].toInt() and 0xFF) shl 8)
            if (c == 0) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    fun asAlternate() = Gpt(h).also {
        it.entries = entries.copyOf()
        it.currentLba = alternateLba
        it.alternateLba = currentLba
        it.partEntriesStartLba = alternateLba - entriesSectors
    }

    /** Updates both CRCs and returns the header bytes. */
    fun buildHeader(): ByteArray {
        val b = hb()
        b.putInt(88, crc(entries))
        b.putInt(16, 0)
        b.putInt(16, crc(h))
        return h.copyOf()
    }

    fun setActiveSlot(slot: Char) {
        val e = eb()
        for (p in partitions()) {
            val s = p.name.takeLast(2)
            if (s != "_a" && s != "_b") continue
            val boot = p.name == "boot$s"
            e.putLong(p.off + 48, abFlags(e.getLong(p.off + 48), s == "_$slot", boot, !boot))
        }
    }

    companion object {
        const val HEADER_SIZE = 92
        fun crc(x: ByteArray) = CRC32().apply { update(x) }.value.toInt()

        /** A/B attribute update; mask expression intentionally mirrors qdl.js (`~mask << 54`). */
        fun abFlags(a: Long, active: Boolean, successful: Boolean, unbootable: Boolean): Long {
            var r = a and (0xFC4L.inv() shl 54)
            if (active) r = r or (0x4L shl 54)
            if (successful) r = r or (0x40L shl 54)
            if (unbootable) r = r or (0x80L shl 54)
            return r
        }
    }
}
