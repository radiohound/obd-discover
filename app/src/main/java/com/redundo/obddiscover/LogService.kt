package com.redundo.obddiscover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process alive for the whole drive.
 *
 * WHY THIS IS NOT OPTIONAL. A cold-start log runs 20-30 minutes with the phone in a pocket
 * or on the passenger seat and the screen off. Without a foreground service Android is free
 * to freeze the process, and the failure is silent: the CSV simply stops growing partway
 * through the one measurement that cannot be repeated for six hours. The notification is the
 * price of the guarantee, and it doubles as the only at-a-glance sign that logging is alive.
 *
 * The partial wake lock is belt-and-braces on top: the foreground service stops the process
 * being frozen, the wake lock stops the CPU sleeping between BLE round-trips.
 */
class LogService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Logging"
        startForeground(NOTIF_ID, notification(label))
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObdDiscover:log").apply {
                setReferenceCounted(false)
                acquire(90 * 60 * 1000L)   // hard ceiling; a drive is 20-30 min
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "OBD logging", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("OBD Discover")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "obdlog"
        private const val NOTIF_ID = 1
        const val EXTRA_LABEL = "label"

        fun start(ctx: Context, label: String) {
            val i = Intent(ctx, LogService::class.java).putExtra(EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, LogService::class.java))
    }
}
