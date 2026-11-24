package com.lanfetch.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.lanfetch.app.MainActivity

/** Foreground service that keeps downloads alive and shows aggregate progress. */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "LanFetch"
        val downloaded = intent?.getLongExtra(EXTRA_DOWNLOADED, 0) ?: 0
        val total = intent?.getLongExtra(EXTRA_TOTAL, -1) ?: -1
        val speed = intent?.getDoubleExtra(EXTRA_SPEED, 0.0) ?: 0.0
        startForeground(NOTIF_ID, buildNotification(title, downloaded, total, speed))
        return START_STICKY
    }

    private fun buildNotification(title: String, downloaded: Long, total: Long, speed: Double): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val text = buildString {
            append(Format.bytes(downloaded))
            if (total > 0) append(" / ").append(Format.bytes(total)).append("  •  ").append(pct).append("%")
            if (speed > 1) append("  •  ").append(Format.speed(speed))
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $title")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (total > 0) builder.setProgress(100, pct, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DOWNLOADED = "downloaded"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_SPEED = "speed"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }

        fun update(context: Context, title: String, downloaded: Long, total: Long, speed: Double) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DOWNLOADED, downloaded)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_SPEED, speed)
            }
            context.startForegroundService(intent)
        }
    }
}

/** Small formatting helpers shared by UI + notifications. */
object Format {
    fun bytes(n: Long): String {
        if (n < 0) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return if (i == 0) "${n} B" else String.format("%.2f %s", v, units[i])
    }

    fun speed(bps: Double): String = if (bps <= 0) "" else "${bytes(bps.toLong())}/s"

    fun eta(seconds: Long): String = when {
        seconds < 0 -> ""
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
    }
}
