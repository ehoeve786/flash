package ca.hoeve.flash

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/** Process-wide job state, so work outlives the activity. Observed by the UI and the service notification. */
object Session {
    interface Ui {
        fun onProgress()
        fun onLog()
    }

    @Volatile var running = false
        private set
    @Volatile var status = ""
        private set
    @Volatile var permille = 0
        private set
    private val buf = StringBuilder()
    val logText: String get() = synchronized(buf) { buf.toString() }

    /** Main thread only. */
    var ui: Ui? = null
    var notifier: (() -> Unit)? = null
    var stopper: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var notified = 0

    fun run(ctx: Context, d: UsbDevice, intf: UsbInterface, job: (Link) -> Unit) {
        val app = ctx.applicationContext
        running = true
        status = ""
        synchronized(buf) { buf.setLength(0) }
        ui?.onLog()
        app.startForegroundService(Intent(app, FlashService::class.java))
        thread {
            val conn = app.getSystemService(UsbManager::class.java).openDevice(d)
            try {
                checkNotNull(conn) { "Could not open USB device" }
                job(UsbLink(conn, intf))
            } catch (e: Throwable) {
                Log.e(TAG, "Failed", e)
                log("ERROR: ${e.message ?: e}")
                progress("Failed: ${e.message ?: e}", 0.0)
            } finally {
                conn?.close()
                running = false
                main.post { ui?.onProgress(); stopper?.invoke() }
            }
        }
    }

    /** [p] 0..1, or -1 for indeterminate. Called from the worker; posts only visible changes. */
    fun progress(s: String, p: Double) {
        val pm = if (p < 0) -1 else (p.coerceAtMost(1.0) * 1000).toInt()
        if (s == status && pm == permille) return
        status = s
        permille = pm
        main.post { ui?.onProgress() }
        val key = s.hashCode() * 31 + pm / 10 // notifications: at most once per percent
        if (key != notified) {
            notified = key
            main.post { notifier?.invoke() }
        }
    }

    fun log(m: String) {
        Log.i(TAG, m)
        if (m.startsWith("device: ") && "ERROR" !in m && "WARN" !in m) return // device INFO chatter: logcat only
        synchronized(buf) { buf.append(m).append('\n') }
        main.post { ui?.onLog() }
    }

    private const val TAG = "flash"
}
