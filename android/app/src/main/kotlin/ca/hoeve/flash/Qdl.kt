package ca.hoeve.flash

import java.io.File
import java.io.RandomAccessFile

/** Device-level operations (port of qdl.js qdl.js). */
class Qdl(private val usb: Link, private val programmer: ByteArray, private val log: (String) -> Unit) {
    lateinit var fh: Firehose
        private set
    var serial = ""
        private set
    private val cache = HashMap<Int, Gpt>()

    fun connect() {
        val s = Sahara(usb, programmer)
        var mode = s.connect()
        if (mode == "sahara") {
            mode = s.uploadLoader()
            serial = s.serial
        }
        check(mode == "firehose") { "Device is in mode \"$mode\". Unplug it and start again." }
        fh = Firehose(usb, log).apply { configure() }
    }

    private fun read(lun: Int, lba: Long): Gpt {
        val g = Gpt(fh.readBuffer(lun, lba, 1))
        if (g.valid) g.entries = fh.readBuffer(lun, g.partEntriesStartLba, g.entriesSectors).copyOf(g.entriesBytes)
        return g
    }

    fun gpt(lun: Int): Gpt = cache.getOrPut(lun) {
        val p = read(lun, 1)
        check(p.valid) { "LUN $lun: primary GPT header invalid" }
        val b = read(lun, p.alternateLba)
        when {
            !p.ok -> { check(b.ok) { "LUN $lun: primary and backup GPT corrupted" }; log("LUN $lun: primary GPT corrupted, using backup"); b }
            else -> { if (!b.ok || b.entriesCrc != p.entriesCrc) log("LUN $lun: backup GPT inconsistent, using primary"); p }
        }
    }

    fun repairGpt(lun: Int, src: Src) {
        log("Repairing GPT on LUN $lun")
        check(fh.program(lun, 0, src)) { "Failed to write primary GPT" }
        fh.fixGpt(lun, 1)
        val b = read(lun, 1).asAlternate()
        check(fh.program(lun, b.partEntriesStartLba, BytesSrc(b.entries))) { "Failed to write backup partition table" }
        check(fh.program(lun, b.currentLba, BytesSrc(b.buildHeader()))) { "Failed to write backup GPT header" }
        cache.remove(lun)
    }

    fun eraseLun(lun: Int, preserve: List<String>) {
        val g = gpt(lun)
        val keep = ArrayList<LongArray>()
        if ("mbr" in preserve) keep += longArrayOf(0, 0)
        if ("gpt" in preserve) {
            keep += longArrayOf(g.currentLba, g.firstUsableLba - 1)
            keep += longArrayOf(g.lastUsableLba + 1, g.alternateLba)
        }
        for (n in preserve) if (n != "mbr" && n != "gpt") {
            val p = g.find(n) ?: error("Partition $n not found in GPT")
            keep += longArrayOf(p.start, p.end)
        }
        keep.sortBy { it[0] }
        var last = -1L
        val gaps = ArrayList<LongArray>()
        for ((s, e) in keep) {
            if (s > last + 1) gaps += longArrayOf(last + 1, s - 1)
            last = maxOf(last, e)
        }
        if (last < g.alternateLba) gaps += longArrayOf(last + 1, g.alternateLba)
        for ((s, e) in gaps) {
            var x = s
            while (x <= e) { // erase times out on larger ranges
                val n = minOf(e - x + 1, 512L * 1024).toInt()
                fh.erase(lun, x, n)
                x += n
            }
        }
        log("Erased LUN $lun (kept ${preserve.joinToString()})")
    }

    fun detect(name: String): Pair<Int, Part>? {
        for (lun in LUNS) gpt(lun).find(name)?.let { return lun to it }
        return null
    }

    fun flash(name: String, file: File, onProgress: (Long) -> Unit) {
        val (lun, p) = detect(name) ?: error("Can't find partition $name")
        RandomAccessFile(file, "r").channel.use { ch ->
            val chunks = Sparse.chunks(ch)
            if (chunks == null) {
                check((ch.size() + Firehose.SECTOR - 1) / Firehose.SECTOR <= p.sectors) { "Image too large for $name" }
                check(fh.program(lun, p.start, FileSrc(ch, 0, ch.size()), onProgress)) { "Flashing $name failed" }
            } else for ((off, src) in chunks) {
                check(off % Firehose.SECTOR == 0L) { "Sparse offset not sector aligned" }
                check(fh.program(lun, p.start + off / Firehose.SECTOR, src) { onProgress(off + it) }) { "Flashing $name failed" }
            }
        }
    }

    fun setActiveSlot(slot: Char) {
        for (lun in LUNS) {
            val p = read(lun, 1)
            write(lun, p, slot)
            write(lun, read(lun, p.alternateLba), slot)
        }
        fh.setBootLun(if (slot == 'a') 1 else 2)
        cache.clear()
        log("Slot $slot active")
    }

    private fun write(lun: Int, g: Gpt, slot: Char) {
        g.setActiveSlot(slot)
        check(fh.program(lun, g.partEntriesStartLba, BytesSrc(g.entries))) { "Failed to write LUN $lun partition table" }
        check(fh.program(lun, g.currentLba, BytesSrc(g.buildHeader()))) { "Failed to write LUN $lun GPT header" }
    }

    companion object {
        val LUNS = 0 until 6
    }
}
