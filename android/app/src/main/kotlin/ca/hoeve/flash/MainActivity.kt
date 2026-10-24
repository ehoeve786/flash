package ca.hoeve.flash

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity(), Session.Ui {
    /** Selectable devices: Firehose device type, picker button and the website's images/steps. */
    private enum class Model(val type: Int, val btn: Int, val product: Int, val ports: Int, val steps: Int) {
        C3(32, R.id.c3, R.drawable.comma3x, R.drawable.qdl_ports_three, R.string.steps_three),
        C3X(33, R.id.c3x, R.drawable.comma3x, R.drawable.qdl_ports_three, R.string.steps_three),
        C4(34, R.id.c4, R.drawable.four_screen_on, R.drawable.qdl_ports_four, R.string.steps_four),
    }

    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private lateinit var controls: List<View>
    private lateinit var bar: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var logView: TextView
    private lateinit var logScroll: NestedScrollView
    private var model = Model.C3X
    private var pulse: ValueAnimator? = null
    private var shown: String? = null
    private var logGen = -1
    private var logLen = 0
    private var pending: ((UsbDevice) -> Unit)? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val a = pending.also { pending = null }
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && a != null) start(a) else idle("USB permission denied")
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        bar = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        statusIcon = findViewById<ImageView>(R.id.statusIcon).apply { background = background.mutate() }
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        // Bottom tabs: menu item ids match the tab container ids.
        val tabs = listOf(R.id.tabDevice, R.id.tabFlash, R.id.tabLog).map { findViewById<View>(it) }
        findViewById<BottomNavigationView>(R.id.nav).setOnItemSelectedListener { item ->
            tabs.forEach { it.visibility = if (it.id == item.itemId) View.VISIBLE else View.GONE }
            true
        }
        val devices = findViewById<MaterialButtonToggleGroup>(R.id.devices)
        controls = listOf<Pair<Int, (UsbDevice) -> Unit>>(
            R.id.flash to ::flash, R.id.rebootEdl to { reboot(it, true) }, R.id.rebootFastboot to { reboot(it, false) },
        ).map { (id, a) -> findViewById<View>(id).apply { setOnClickListener { start(a) } } } +
            listOf<Pair<Int, () -> Unit>>(
                R.id.agnosDownload to ::agnosDownload, R.id.agnosCheck to ::agnosCheck, R.id.agnosDelete to ::agnosDelete,
            ).map { (id, a) -> findViewById<View>(id).apply { setOnClickListener { a() } } } +
            List(devices.childCount) { devices.getChildAt(it) }
        devices.addOnButtonCheckedListener { _, id, checked -> if (checked) select(Model.entries.first { it.btn == id }) }
        devices.check((Model.entries.getOrNull(prefs.getInt(PREF_MODEL, Model.C3X.ordinal)) ?: Model.C3X).btn)
        findViewById<View>(R.id.logCopy).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("comma flash log", logReport()))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show() // 13+ shows its own
        }
        findViewById<View>(R.id.logShare).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "comma flash log").putExtra(Intent.EXTRA_TEXT, logReport()), null))
        }
        Session.verbose = prefs.getBoolean(PREF_VERBOSE, false)
        findViewById<CompoundButton>(R.id.verbose).apply {
            isChecked = Session.verbose
            setOnCheckedChangeListener { _, on -> Session.verbose = on; prefs.edit().putBoolean(PREF_VERBOSE, on).apply() }
        }
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        } else registerReceiver(permReceiver, f)
    }

    override fun onStart() {
        super.onStart()
        Session.ui = this
        logGen = -1 // full redraw
        onLog()
        onProgress()
    }

    override fun onStop() {
        Session.ui = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!Session.running && Session.status.isEmpty()) idle()
    }

    override fun onDestroy() {
        unregisterReceiver(permReceiver)
        super.onDestroy()
    }

    override fun onLog() {
        val (g, len, text) = Session.readLog(logGen, logLen)
        if (g == logGen) logView.append(text) else logView.text = text
        logGen = g
        logLen = len
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onProgress() {
        val busy = Session.running
        controls.forEach { it.isEnabled = !busy }
        if (!busy) agnosInfo()
        val s = Session.status
        if (s.isEmpty()) return
        val pm = Session.permille
        setBar(pm)
        status.text = if (pm in 1..999) "$s… ${pm / 10}%" else s
        if (s == shown && busy) return
        shown = s
        when {
            s.startsWith("Failed") -> state(R.drawable.ic_device_exclamation_c3, R.color.state_error, false)
            s.startsWith("Done") || s.startsWith("Reboot sent") || s.startsWith("AGNOS") -> state(R.drawable.ic_done, R.color.state_done, false)
            s.startsWith("Update available") -> state(R.drawable.ic_system_update_c3, R.color.state_wait, false)
            s.startsWith("Connecting") || s.startsWith("Rebooting") -> state(R.drawable.ic_cable, R.color.state_wait, busy)
            else -> state(R.drawable.ic_system_update_c3, R.color.state_work, busy)
        }
    }

    private fun select(m: Model) {
        model = m
        prefs.edit().putInt(PREF_MODEL, m.ordinal).apply()
        findViewById<ImageView>(R.id.product).setImageResource(m.product)
        findViewById<ImageView>(R.id.ports).setImageResource(m.ports)
        findViewById<TextView>(R.id.steps).setText(m.steps)
        agnosInfo()
    }

    /** Log with enough context to debug from. */
    private fun logReport() = "comma flash ${packageManager.getPackageInfo(packageName, 0).versionName} · " +
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · selected ${Images.NAMES[model.type]} · " +
        "verbose ${if (Session.verbose) "on" else "off"}\nStatus: ${Session.status.ifEmpty { "idle" }}\n\n" +
        Session.readLog(-1, 0).third

    private fun store(m: Model = model) = Images(noBackupFilesDir, m.type, Session::progress, Session::log)

    private fun agnosInfo() {
        findViewById<TextView>(R.id.agnosInfo).text = "${Images.NAMES[model.type]}: ${store().summary()}"
    }

    private fun agnosDownload() {
        if (Session.running) return
        val s = store()
        shown = null
        val dir = noBackupFilesDir
        Session.run(this) { Loader.get(dir, Session::log); s.downloadAll(); Session.progress("AGNOS ready for ${Images.NAMES[s.type]} (${Images.gb(s.bytes())})", 1.0) }
    }

    private fun agnosCheck() {
        if (Session.running) return
        val s = store()
        shown = null
        Session.run(this) {
            Session.progress("Checking for AGNOS updates", -1.0)
            val n = s.missing()
            Session.progress(if (n == 0) "AGNOS is up to date" else "Update available: $n image(s) to download. Tap Download.", 1.0)
        }
    }

    private fun agnosDelete() {
        if (Session.running) return
        val s = store()
        confirm("Delete the downloaded AGNOS for ${Images.NAMES[s.type]} (${Images.gb(s.bytes())})?") { s.clear(); agnosInfo() }
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
        bar.visibility = View.INVISIBLE
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
        }
        if (pm >= 0) bar.setProgressCompat(pm, true)
        bar.visibility = View.VISIBLE
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
        if (Session.running) return
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

    private fun launch(d: UsbDevice, job: (Link) -> Unit) {
        shown = null
        val intf = d.adb ?: d.getInterface(0)
        val um = usb
        Session.run(this) {
            val conn = checkNotNull(um.openDevice(d)) { "Could not open USB device" }
            try { job(UsbLink(conn, intf)) } finally { conn.close() }
        }
    }

    private fun flash(d: UsbDevice) {
        if (!d.edl) return idle("Device is not in EDL mode. Tap Reboot to EDL first.")
        val m = model
        val app = applicationContext
        val s = store(m)
        confirm("Erase and reflash this ${findViewById<TextView>(m.btn).text}? Everything except the persist partition is erased.") {
            launch(d) {
                val programmer = Loader.get(app.noBackupFilesDir, Session::log)
                Flasher(Qdl(it, programmer, Session::log), s, Session::progress, Session::log).run()
            }
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
        val app = applicationContext
        val isAdb = d.adb != null
        launch(d) {
            Session.progress("Rebooting to $target", -1.0)
            when {
                isAdb -> Adb(it, Adb.key(app.filesDir)).run { connect(Session::log); service(if (edl) "reboot:edl" else "reboot:bootloader") }
                d.fastboot -> if (edl) Fastboot.rebootEdl(it, Session::log) else check(Fastboot.command(it, "reboot-bootloader")) { "reboot-bootloader failed" }
                else -> Qdl(it, Loader.get(app.noBackupFilesDir, Session::log), Session::log).apply { connect() }.fh.reset("reset_to_edl")
            }
            Session.progress("Reboot sent. Wait for the device to reconnect in $target mode", 1.0)
        }
    }

    private companion object {
        const val ACTION_PERM = "ca.hoeve.flash.USB_PERMISSION"
        const val PREF_MODEL = "model"
        const val PREF_VERBOSE = "verbose"
    }
}
