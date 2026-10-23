package ca.hoeve.flash

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private lateinit var btn: Button
    private lateinit var rebootBtn: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    @Volatile private var running = false
    private var lastStatus = ""
    private var lastPermille = Int.MIN_VALUE

    private var pending: ((UsbDevice) -> Unit)? = null
    private val programmer by lazy { assets.open("programmer.bin").use { it.readBytes() } }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val a = pending.also { pending = null }
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && a != null) start(a) else setStatus("USB permission denied")
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        btn = Button(this).apply { text = "Flash"; setOnClickListener { start(::flash) } }
        rebootBtn = Button(this).apply { text = "Reboot to EDL"; setOnClickListener { start(::rebootEdl) } }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        status = TextView(this).apply { textSize = 18f }
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = HELP })
            addView(btn)
            addView(rebootBtn)
            addView(bar)
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(permReceiver, f)
    }

    override fun onResume() {
        super.onResume()
        if (!running) setStatus(when (val d = device()) {
            null -> "Waiting for device in EDL or fastboot mode"
            else -> if (d.fastboot) "Device in fastboot mode. Tap Reboot to EDL." else "Device in EDL mode. Tap Flash."
        })
    }

    override fun onDestroy() {
        unregisterReceiver(permReceiver)
        super.onDestroy()
    }

    private val UsbDevice.fastboot get() = vendorId == 0x18D1 && productId == 0xD00D

    private fun device(): UsbDevice? = usb.deviceList.values.firstOrNull {
        it.fastboot || it.productId == 0x9008 && (it.vendorId == 0x05C6 || it.vendorId == 0x3801)
    }

    private fun start(action: (UsbDevice) -> Unit) {
        if (running) return
        val d = device() ?: return setStatus("No device in EDL or fastboot mode found. Follow the steps above.")
        if (usb.hasPermission(d)) action(d)
        else {
            pending = action
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    private fun flash(d: UsbDevice) =
        if (d.fastboot) setStatus("Device is in fastboot mode. Tap Reboot to EDL first.")
        else work(d) { Flasher(Qdl(it, programmer, ::log), noBackupFilesDir, ::progress, ::log).run() }

    private fun rebootEdl(d: UsbDevice) = work(d) {
        progress("Rebooting to EDL", -1.0)
        if (d.fastboot) Fastboot.rebootEdl(it, ::log) else Qdl(it, programmer, ::log).apply { connect() }.fh.reset("reset_to_edl")
        progress("Reboot sent. Wait for the device to reconnect in EDL mode", 1.0)
    }

    private fun work(d: UsbDevice, job: (Link) -> Unit) {
        running = true
        btn.isEnabled = false
        rebootBtn.isEnabled = false
        logView.text = ""
        thread {
            val conn = usb.openDevice(d)
            try {
                checkNotNull(conn) { "Could not open USB device" }
                job(UsbLink(conn, d.getInterface(0)))
            } catch (e: Throwable) {
                Log.e(TAG, "Failed", e)
                log("ERROR: ${e.message ?: e}")
                progress("Failed: ${e.message ?: e}", -1.0)
            } finally {
                conn?.close()
                runOnUiThread { running = false; btn.isEnabled = true; rebootBtn.isEnabled = true }
            }
        }
    }

    /** Called from the worker thread; only posts when the visible value changes. */
    private fun progress(s: String, p: Double) {
        val pm = if (p < 0) -1 else (p.coerceAtMost(1.0) * 1000).toInt()
        if (s == lastStatus && pm == lastPermille) return
        lastStatus = s
        lastPermille = pm
        runOnUiThread {
            bar.isIndeterminate = pm < 0
            if (pm >= 0) bar.progress = pm
            status.text = if (pm >= 0) "$s… ${pm / 10}%" else "$s…"
        }
    }

    private fun setStatus(s: String) { status.text = s }

    private fun log(m: String) {
        Log.i(TAG, m)
        // Device INFO chatter goes to logcat only.
        if (m.startsWith("device: ") && "ERROR" !in m && "WARN" !in m) return
        runOnUiThread {
            logView.append(m + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val TAG = "flash"
        const val ACTION_PERM = "ca.hoeve.flash.USB_PERMISSION"
        const val HELP = """Erases the comma device and installs the latest AGNOS + openpilot release.

1. Unplug the device. On a comma 3/3X, wait for the light on the back to turn off.
2. Connect port 1 to this phone (USB-C OTG adapter recommended).
3. Connect port 2 to a power brick. The device's screen stays blank; that's normal.
4. Tap Flash and keep this app open until it finishes. Needs Wi-Fi/data and a few GB free."""
    }
}
