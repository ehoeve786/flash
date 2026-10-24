package ca.hoeve.flash

import org.json.JSONObject
import java.io.RandomAccessFile

/** Full flash sequence (port of flash.comma.ai FlashManager). [status] progress is 0..1, or -1 for indeterminate. */
class Flasher(
    private val qdl: Qdl,
    /** AGNOS store for the device type picked in the UI. */
    private val store: Images,
    private val status: (String, Double) -> Unit,
    private val log: (String) -> Unit,
) {
    fun run() {
        // Download and verify everything before touching the device, so a network failure can't leave it half-erased.
        val images = store.manifest()
        images.filter { it.needed }.forEach { store.ensure(it) }

        status("Connecting", -1.0)
        qdl.connect()
        log("Connected ${qdl.serial}")
        val type = qdl.fh.deviceType()
        check(type == store.type) { "Selected ${Images.NAMES[store.type]} but the connected device is ${Images.NAMES[type] ?: "unknown (type $type)"}" }

        val info = JSONObject(qdl.fh.storageInfo()).getJSONObject("storage_info")
        val userdata = userdataImage(info)
        log("Device %08x (type $type), $userdata".format(info.getLong("serial_num")))

        // Repair partition tables
        val gpts = images.filter { it.gptLun != null }
        check(gpts.isNotEmpty()) { "No GPT images found" }
        for (im in gpts) {
            RandomAccessFile(store.ensure(im), "r").channel.use { qdl.repairGpt(im.gptLun!!, FileSrc(it, 0, it.size())) }
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
            val f = store.ensure(im)
            val part = if (im.name.startsWith("userdata_")) "userdata" else im.name
            for (slot in if (im.hasAB) listOf("_a", "_b") else listOf("")) {
                val label = "Flashing $part$slot"
                status(label, 0.0)
                qdl.flash(part + slot, f) { status(label, it.toDouble() / im.size) }
                log("Flashed $part$slot")
            }
        }

        status("Finalizing", -1.0)
        qdl.setActiveSlot('a')
        status("Rebooting", -1.0)
        qdl.fh.reset()
        status("Done — the device will reboot into setup", 1.0)
    }

    companion object {
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
