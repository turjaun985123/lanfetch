package com.lanfetch.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the download queue alive.
 *
 * The service owns no queue state: it observes the coordinator, aggregates all
 * active transfers into one notification, holds the wake/Wi-Fi locks while
 * work exists and stops itself as soon as the queue is idle. Android 14/15
 * dataSync timeouts pause the queue instead of being killed silently.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var stopping = false

    private val coordinator: DownloadCoordinator
        get() = (application as LanFetchApp).container.downloadCoordinator

    override fun onCreate() {
        super.onCreate()
        createChannel()
        enterForeground()
        acquireLocks()
        startCollector()
    }

    private fun enterForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(emptyList()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(emptyList()))
        }
    }

    private fun startCollector() {
        if (collector?.isActive == true) return
        var lastNotifyAt = 0L
        var lastCount = -1
        collector = scope.launch {
            coordinator.observeActive().collectLatest { active ->
                if (active.isEmpty()) {
                    stopIfIdle()
                } else {
                    val now = System.currentTimeMillis()
                    if (active.size != lastCount || now - lastNotifyAt >= 900) {
                        lastCount = active.size
                        lastNotifyAt = now
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification(active))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            coordinator.pauseAll("Paused from notification")
            return START_NOT_STICKY
        }
        if (stopping) {
            stopping = false
            enterForeground()
            startCollector()
        }
        return START_NOT_STICKY
    }

    /** Android 15+ dataSync timeout. */
    override fun onTimeout(startId: Int) {
        coordinator.pauseAll("Foreground time limit reached — tap Resume")
        stopIfIdle()
    }

    /** Android 14 per-type timeout. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopIfIdle() {
        if (stopping) return
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(active: List<DownloadUi>): Notification {
        val total = active.sumOf { if (it.totalBytes > 0) it.totalBytes else 0 }
        val done = active.sumOf { it.downloadedBytes }
        val speed = active.sumOf { it.speedBps }
        val indeterminate = active.isEmpty() || total <= 0
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0

        val text = buildString {
            if (active.isEmpty()) {
                append("Preparing download…")
            } else {
                append(active.size).append(if (active.size == 1) " file" else " files")
                if (total > 0) append("  •  ").append(Format.bytes(done)).append(" / ").append(Format.bytes(total))
                if (speed > 1) {
                    append("  •  ").append(Format.speed(speed))
                    if (total > done) {
                        val eta = Format.eta(((total - done) / speed).toLong())
                        if (eta.isNotEmpty()) append("  •  ").append(eta)
                    }
                }
            }
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active.size > 1) "Downloading ${active.size} files" else "Downloading")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, pct, false)
        }
        if (active.isNotEmpty()) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause all", pause)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LanFetch:downloads").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LanFetch:wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_PAUSE_ALL = "com.lanfetch.app.action.PAUSE_ALL"

        /**
         * Starts the foreground service. Returns false when Android refuses
         * the start (audit B4): on Android 12+ a background start — e.g.
         * auto-resume firing when Wi-Fi returns while the app is closed —
         * throws ForegroundServiceStartNotAllowedException. Callers must not
         * run transfers unprotected in that case.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, DownloadService::class.java)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }

        @Volatile private var lastRefusalNotifiedAt = 0L

        /** Tells the user downloads are waiting on them opening the app (B4). Throttled to once per 5 min. */
        fun notifyStartRefused(context: Context) {
            val now = System.currentTimeMillis()
            if (now - lastRefusalNotifiedAt < 5 * 60 * 1000L) return
            lastRefusalNotifiedAt = now
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloads are waiting")
                .setContentText("Android needs LanFetch open to restart downloads. Tap to open.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(REFUSAL_NOTIFICATION_ID, notification) }
        }

        private const val REFUSAL_NOTIFICATION_ID = 43
    }
}
