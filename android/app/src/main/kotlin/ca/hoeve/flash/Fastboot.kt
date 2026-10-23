package ca.hoeve.flash

/** Minimal fastboot client: enough to send reboot commands. */
object Fastboot {
    /** True on OKAY, or if the device dropped off without replying (it rebooted). */
    fun command(usb: Link, cmd: String): Boolean {
        usb.write(cmd.toByteArray(), timeout = 3000)
        while (true) {
            val r = usb.read(3000, 256).text()
            if (r.isEmpty() || r.startsWith("OKAY")) return true
            if (r.startsWith("FAIL")) return false
        }
    }

    /** Tries the EDL reboot commands bootloaders commonly implement. */
    fun rebootEdl(usb: Link, log: (String) -> Unit) = check(EDL.any { c ->
        runCatching { command(usb, c) }.getOrDefault(true).also { log("fastboot $c: ${if (it) "OK" else "FAIL"}") }
    }) { "Bootloader refused all EDL reboot commands" }

    private val EDL = listOf("reboot-edl", "oem edl", "oem reboot-edl")
}
