package com.qemusdl.app.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.qemusdl.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service (`dataSync`) that keeps ISO downloads running reliably while the
 * device screen is turned off or the app is backgrounded, holding a partial CPU wake lock
 * and a Wi-Fi lock until all active downloads complete or are cancelled.
 */
class IsoDownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotificationUpdateMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            IsoDownloader.getInstance(applicationContext).cancelAllDownloads()
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification(
            title = "Downloading OS image",
            detail = "Keeping connection active in background...",
            progressPercent = null
        )
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }.onFailure {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        startObservingDownloads()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync FGS timeout safeguard: release cleanly; partial files remain resumable.
        stopSelfCleanly()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun startObservingDownloads() {
        if (observerJob?.isActive == true) return
        val downloader = IsoDownloader.getInstance(applicationContext)
        observerJob = serviceScope.launch {
            downloader.states.collectLatest { stateMap ->
                val activeEntry = stateMap.entries.firstOrNull { (_, st) -> st.isActiveDownload }
                if (activeEntry == null) {
                    stopSelfCleanly()
                    return@collectLatest
                }

                val now = System.currentTimeMillis()
                val state = activeEntry.value
                val shouldThrottle = state is IsoDownloadState.Downloading &&
                    (now - lastNotificationUpdateMs) < NOTIFICATION_THROTTLE_MS
                if (shouldThrottle) return@collectLatest
                lastNotificationUpdateMs = now

                val title = downloader.getDownloadTitle(activeEntry.key)
                val activeCount = stateMap.values.count { it.isActiveDownload }
                val titleSuffix = if (activeCount > 1) " (+${activeCount - 1} more)" else ""

                val (detail, percent) = when (state) {
                    is IsoDownloadState.Resolving -> state.status to null
                    is IsoDownloadState.Extracting -> state.status to null
                    is IsoDownloadState.Downloading -> {
                        val pct = state.progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
                        val progressText = if (state.totalBytes > 0L) {
                            "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                        } else {
                            formatBytes(state.bytesDownloaded)
                        }
                        val speedText = if (state.speedBytesPerSec > 0L) {
                            " • ${formatBytes(state.speedBytesPerSec)}/s"
                        } else {
                            ""
                        }
                        "$progressText$speedText" to pct
                    }
                    else -> "Downloading..." to null
                }

                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification("$title$titleSuffix", detail, percent))
            }
        }
    }

    private fun stopSelfCleanly() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(LOCK_TIMEOUT_MS)
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OS & ISO Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while QemuSDL downloads OS ISO images in the background"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        detail: String,
        progressPercent: Int?
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, IsoDownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelPending = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)

        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    companion object {
        private const val CHANNEL_ID = "qemusdl_iso_downloads"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_CANCEL_ALL = "com.qemusdl.app.action.CANCEL_ISO_DOWNLOADS"
        private const val WAKE_LOCK_TAG = "QemuSDL:IsoDownloadWakeLock"
        private const val WIFI_LOCK_TAG = "QemuSDL:IsoDownloadWifiLock"
        private const val LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 hours safety cap
        private const val NOTIFICATION_THROTTLE_MS = 900L

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, IsoDownloadForegroundService::class.java)
                ContextCompat.startForegroundService(appContext, intent)
            }
        }
    }
}
