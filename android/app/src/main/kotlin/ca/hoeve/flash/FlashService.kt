package ca.hoeve.flash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/** Foreground service that keeps the process, CPU and Wi-Fi alive while [Session] runs a job. */
class FlashService : Service() {
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var wifi: WifiManager.WifiLock

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, note())
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flash:job").apply { acquire(6 * 3600_000L) }
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flash:job").apply { acquire() }
        Session.notifier = { nm.notify(ID, note()) }
        Session.stopper = ::stopSelf
        if (!Session.running) stopSelf() // job already finished before we started
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        Session.notifier = null
        Session.stopper = null
        if (wake.isHeld) wake.release()
        if (wifi.isHeld) wifi.release()
        if (Session.status.isNotEmpty()) nm.notify(DONE_ID, builder().setContentTitle(Session.status).setAutoCancel(true).build())
        super.onDestroy()
    }

    private fun builder() = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_comma)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

    private fun note(): Notification {
        val pm = Session.permille
        return builder().setContentTitle(Session.status.ifEmpty { "Starting" }).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(1000, pm.coerceAtLeast(0), pm < 0).build()
    }

    private companion object {
        const val CHANNEL = "job"
        const val ID = 1
        const val DONE_ID = 2
    }
}
