package ca.hoeve.flash

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    /** Selectable devices: Firehose device type, picker button and the website's images/steps. */
    private enum class Model(val type: Int, val btn: Int, val product: Int, val ports: Int, val steps: Int) {
        C3(32, R.id.c3, R.drawable.comma3x, R.drawable.qdl_ports_three, R.string.steps_three),
        C3X(33, R.id.c3x, R.drawable.comma3x, R.drawable.qdl_ports_three, R.string.steps_three),
        C4(34, R.id.c4, R.drawable.four_screen_on, R.drawable.qdl_ports_four, R.string.steps_four),
    }

    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val programmer by lazy { assets.open("programmer.bin").use { it.readBytes() } }
    private lateinit var controls: List<View>
    private lateinit var bar: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var logView: TextView
    private lateinit var logScroll: NestedScrollView
    private var model = Model.C3X
    private var pulse: ValueAnimator? = null
    @Volatile private var running = false
    private var lastStatus = ""
    private var lastPermille = Int.MIN_VALUE
    private var pending: ((UsbDevice) -> Unit)? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val a = pending.also { pending = null }
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && a != null) start(a) else idle("USB permission denied")
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bar = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        statusIcon = findViewById<ImageView>(R.id.statusIcon).apply { background = background.mutate() }
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        val devices = findViewById<MaterialButtonToggleGroup>(R.id.devices)
        controls = listOf<Pair<Int, (UsbDevice) -> Unit>>(
            R.id.flash to ::flash, R.id.rebootEdl to { reboot(it, true) }, R.id.rebootFastboot to { reboot(it, false) },
        ).map { (id, a) -> findViewById<View>(id).apply { setOnClickListener { start(a) } } } +
            List(devices.childCount) { devices.getChildAt(it) }
        devices.addOnButtonCheckedListener { _, id, checked -> if (checked) select(Model.entries.first { it.btn == id }) }
        devices.check((Model.entries.getOrNull(prefs.getInt(PREF_MODEL, Model.C3X.ordinal)) ?: Model.C3X).btn)
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(permReceiver, f)
    }

    override fun onResume() {
        super.onResume()
        if (!running) idle()
    }

    override fun onDestroy() {
        unregisterReceiver(permReceiver)
        super.onDestroy()
    }

    private fun select(m: Model) {
        model = m
        prefs.edit().putInt(PREF_MODEL, m.ordinal).apply()
        findViewById<ImageView>(R.id.product).setImageResource(m.product)
        findViewById<ImageView>(R.id.ports).setImageResource(m.ports)
        findViewById<TextView>(R.id.steps).setText(m.steps)
    }

    private val UsbDevice.fastboot get() = vendorId == 0x18D1 && productId == 0xD00D
    private val UsbDevice.edl get() = productId == 0x9008 && (vendorId == 0x05C6 || vendorId == 0x3801)
    private val UsbDevice.adb get() = List(interfaceCount) { getInterface(it) }.firstOrNull {
        it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 1
    }

    private fun device(): UsbDevice? = usb.deviceList.values.firstOrNull { it.edl || it.fastboot || it.adb != null }

    /** Shows the waiting/ready state for whatever is plugged in. */
    private fun idle(msg: String? = null) {
        val d = device()
        setBar(0)
        status.text = msg ?: when {
            d == null -> "Waiting for device (EDL, fastboot or ADB)"
            d.fastboot -> "Device in fastboot mode. Tap Reboot to EDL."
            d.adb != null -> "Device running with ADB. Tap Reboot to EDL or fastboot."
            else -> "Device in EDL mode. Tap Flash."
        }
        if (d == null) state(R.drawable.ic_device_question_c3, R.color.state_idle, false)
        else state(R.drawable.ic_bolt, R.color.state_work, false)
    }

    /** [pm] 0..1000, or negative for indeterminate. */
    private fun setBar(pm: Int) {
        if (bar.isIndeterminate != pm < 0) { // M3 refuses mode switches while visible
            bar.visibility = View.INVISIBLE
            bar.isIndeterminate = pm < 0
            bar.visibility = View.VISIBLE
        }
        if (pm >= 0) bar.setProgressCompat(pm, true)
    }

    /** Website-style status circle; pulses while busy. */
    private fun state(icon: Int, color: Int, busy: Boolean) {
        statusIcon.setImageResource(icon)
        statusIcon.background.setTint(getColor(color))
        pulse?.cancel()
        statusIcon.alpha = 1f
        pulse = if (!busy) null else ObjectAnimator.ofFloat(statusIcon, View.ALPHA, 1f, 0.45f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun start(action: (UsbDevice) -> Unit) {
        if (running) return
        val d = device() ?: return idle("No device in EDL, fastboot or ADB mode found. Follow the steps below.")
        if (usb.hasPermission(d)) action(d)
        else {
            pending = action
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    private fun confirm(msg: String, go: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(msg).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> go() }.show()
    }

    private fun flash(d: UsbDevice) {
        if (!d.edl) return idle("Device is not in EDL mode. Tap Reboot to EDL first.")
        val m = model
        confirm("Erase and reflash this ${findViewById<TextView>(m.btn).text}? Everything except the persist partition is erased.") {
            work(d) { Flasher(Qdl(it, programmer, ::log), noBackupFilesDir, m.type, ::progress, ::log).run() }
        }
    }

    private fun reboot(d: UsbDevice, edl: Boolean) {
        val target = if (edl) "EDL" else "fastboot"
        if (d.edl && !edl) return idle("Fastboot can't be reached from EDL. Replug: power on port 1, then port 2 to the phone.")
        // ADB matches any Android device, so make sure it's the comma and not e.g. another phone.
        if (d.adb != null) confirm("Reboot \"${listOfNotNull(d.manufacturerName, d.productName).joinToString(" ")}\" into $target over ADB?") { rebootNow(d, edl, target) }
        else rebootNow(d, edl, target)
    }

    private fun rebootNow(d: UsbDevice, edl: Boolean, target: String) {
        work(d) {
            progress("Rebooting to $target", -1.0)
            when {
                d.adb != null -> Adb(it, Adb.key(filesDir)).run { connect(::log); service(if (edl) "reboot:edl" else "reboot:bootloader") }
                d.fastboot -> if (edl) Fastboot.rebootEdl(it, ::log) else check(Fastboot.command(it, "reboot-bootloader")) { "reboot-bootloader failed" }
                else -> Qdl(it, programmer, ::log).apply { connect() }.fh.reset("reset_to_edl")
            }
            progress("Reboot sent. Wait for the device to reconnect in $target mode", 1.0)
        }
    }

    private fun work(d: UsbDevice, job: (Link) -> Unit) {
        running = true
        controls.forEach { it.isEnabled = false }
        logView.text = ""
        lastStatus = ""
        thread {
            val conn = usb.openDevice(d)
            try {
                checkNotNull(conn) { "Could not open USB device" }
                job(UsbLink(conn, d.adb ?: d.getInterface(0)))
            } catch (e: Throwable) {
                Log.e(TAG, "Failed", e)
                log("ERROR: ${e.message ?: e}")
                progress("Failed: ${e.message ?: e}", 0.0)
            } finally {
                conn?.close()
                runOnUiThread { running = false; controls.forEach { it.isEnabled = true } }
            }
        }
    }

    /** Called from the worker thread; only posts when the visible value changes. */
    private fun progress(s: String, p: Double) {
        val pm = if (p < 0) -1 else (p.coerceAtMost(1.0) * 1000).toInt()
        val changed = s != lastStatus
        if (!changed && pm == lastPermille) return
        lastStatus = s
        lastPermille = pm
        runOnUiThread {
            setBar(pm)
            status.text = if (pm in 1..999) "$s… ${pm / 10}%" else s
            if (changed) when {
                s.startsWith("Failed") -> state(R.drawable.ic_device_exclamation_c3, R.color.state_error, false)
                s.startsWith("Done") || s.startsWith("Reboot sent") -> state(R.drawable.ic_done, R.color.state_done, false)
                s.startsWith("Connecting") || s.startsWith("Rebooting") -> state(R.drawable.ic_cable, R.color.state_wait, true)
                else -> state(R.drawable.ic_system_update_c3, R.color.state_work, true)
            }
        }
    }

    private fun log(m: String) {
        Log.i(TAG, m)
        // Device INFO chatter goes to logcat only.
        if (m.startsWith("device: ") && "ERROR" !in m && "WARN" !in m) return
        runOnUiThread {
            logView.append(m + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val TAG = "flash"
        const val ACTION_PERM = "ca.hoeve.flash.USB_PERMISSION"
        const val PREF_MODEL = "model"
    }
}
