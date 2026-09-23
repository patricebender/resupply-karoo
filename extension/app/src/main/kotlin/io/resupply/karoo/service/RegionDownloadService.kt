package io.resupply.karoo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.resupply.karoo.R
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.RegionCatalogClient
import io.resupply.karoo.data.RegionDownloadStatus
import io.resupply.karoo.data.RegionDownloader
import io.resupply.karoo.util.Connectivity
import io.resupply.karoo.util.withKarooConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that downloads a region over a direct WiFi connection (see
 * [RegionDownloader]) while holding a partial wake lock, so a large download survives the
 * app being backgrounded and the Karoo's aggressive screen sleep. It's the single writer of
 * download state:
 *  - **live** (byte progress + measured rate/ETA) in [liveDownload], a process-wide flow the
 *    picker observes for its progress row + ETA;
 *  - **durable** (active/failed) in [ConfigStore], so an interrupted download is retryable
 *    after process death.
 *
 * Started with [start]; only one download runs at a time (a second start while busy is
 * ignored — the picker disables other Get buttons meanwhile).
 */
class RegionDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var configStore: ConfigStore
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(applicationContext)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val regionId = intent?.getStringExtra(EXTRA_REGION_ID)
        val label = intent?.getStringExtra(EXTRA_REGION_LABEL) ?: regionId ?: "region"
        if (regionId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Ignore a second request while one is actively running (a lingering DONE/FAILED from
        // a previous download doesn't block a new one).
        val active = _liveDownload.value?.phase
        if (active == LivePhase.DOWNLOADING || active == LivePhase.INSTALLING) {
            Timber.d("download already in progress; ignoring request for $regionId")
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(label, 0, null))
        acquireWakeLock()
        _liveDownload.value = LiveDownload(regionId, label, 0f, 0L, null, LivePhase.DOWNLOADING)

        scope.launch {
            configStore.setDownloadStatus(
                regionId,
                RegionDownloadStatus(regionId, RegionDownloadStatus.Phase.ACTIVE),
            )
            runDownload(regionId, label)
            finishIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(regionId: String, label: String) {
        // Direct download needs WiFi; refuse early with a clear reason if it's not there.
        if (!Connectivity.isOnWifi(applicationContext)) {
            fail(regionId, "Connect to Wi‑Fi to download this region")
            return
        }

        val manifest = withKarooConnection(applicationContext) { system ->
            RegionCatalogClient(system).fetchManifest()
        }
        if (manifest == null) {
            fail(regionId, "Couldn't reach the region list")
            return
        }
        val entry = manifest.regions.firstOrNull { it.id == regionId }
        if (entry == null) {
            fail(regionId, "Region not available")
            return
        }

        val result = RegionDownloader().downloadAndInstall(
            context = applicationContext,
            manifest = manifest,
            entry = entry,
            scratchDir = cacheDir,
            onProgress = { p ->
                // Bail cleanly if WiFi drops mid-download rather than stalling on a dead socket.
                _liveDownload.value = LiveDownload(
                    regionId, label, p.fraction, p.bytesPerSec, p.etaSeconds, LivePhase.DOWNLOADING,
                )
                updateNotification(label, (p.fraction * 100).toInt(), p.etaSeconds)
            },
        )

        when (result) {
            is RegionDownloader.Result.Installed -> {
                _liveDownload.value = _liveDownload.value?.copy(phase = LivePhase.INSTALLING)
                // Record the region as installed and clear the durable download status.
                configStore.addInstalledRegion(regionId)
                configStore.clearDownloadStatus(regionId)
                _liveDownload.value = LiveDownload(
                    regionId, label, 1f, 0L, 0L, LivePhase.DONE, result.poiCount,
                )
            }
            is RegionDownloader.Result.SchemaMismatch ->
                fail(regionId, "Update the app to download regions")
            is RegionDownloader.Result.Failed -> {
                // A WiFi drop surfaces as a download failure — give the actionable reason.
                val reason = if (!Connectivity.isOnWifi(applicationContext)) {
                    "Wi‑Fi lost — reconnect and try again"
                } else {
                    result.reason
                }
                fail(regionId, reason)
            }
        }
    }

    private suspend fun fail(regionId: String, reason: String) {
        Timber.w("region $regionId download failed: $reason")
        configStore.setDownloadStatus(
            regionId,
            RegionDownloadStatus(regionId, RegionDownloadStatus.Phase.FAILED, reason),
        )
        _liveDownload.value = _liveDownload.value?.copy(phase = LivePhase.FAILED, reason = reason)
            ?: LiveDownload(regionId, regionId, 0f, 0L, null, LivePhase.FAILED, reason = reason)
    }

    /**
     * The download reached a terminal state (DONE/FAILED). Leave that in [liveDownload] so the
     * picker can show the result + row status; the UI clears it via [clearLive] (or the next
     * [start] overwrites it). Release the wake lock and stop the service.
     */
    private fun finishIfIdle(startId: Int) {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf(startId)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30 min safety cap; released on finish
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // --- Notification ---------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Region downloads", NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Progress while downloading offline POI regions." },
                )
            }
        }
    }

    private fun buildNotification(label: String, percent: Int, etaSeconds: Long?): Notification {
        val text = when {
            etaSeconds != null && etaSeconds > 0 -> "$percent% · ${formatEta(etaSeconds)} left · keep Wi‑Fi on"
            else -> "$percent% · keep Wi‑Fi on"
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Downloading $label")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()
    }

    private fun updateNotification(label: String, percent: Int, etaSeconds: Long?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(label, percent, etaSeconds))
    }

    /** Live download state, process-wide (UI + service share the process). */
    enum class LivePhase { DOWNLOADING, INSTALLING, DONE, FAILED }

    data class LiveDownload(
        val regionId: String,
        val label: String,
        val fraction: Float,
        val bytesPerSec: Long,
        val etaSeconds: Long?,
        val phase: LivePhase,
        val poiCount: Int = 0,
        val reason: String? = null,
    )

    companion object {
        private const val CHANNEL_ID = "region_downloads"
        private const val NOTIF_ID = 42
        private const val WAKELOCK_TAG = "resupply:region-download"
        private const val EXTRA_REGION_ID = "region_id"
        private const val EXTRA_REGION_LABEL = "region_label"

        private val _liveDownload = MutableStateFlow<LiveDownload?>(null)

        /** The in-flight (or just-finished) download, for the picker's live progress + ETA. */
        val liveDownload: StateFlow<LiveDownload?> = _liveDownload.asStateFlow()

        /** Kick off a download of [regionId]. Safe to call from the UI. */
        fun start(context: Context, regionId: String, label: String) {
            val intent = Intent(context, RegionDownloadService::class.java)
                .putExtra(EXTRA_REGION_ID, regionId)
                .putExtra(EXTRA_REGION_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Clear a lingering terminal (DONE/FAILED) live state once the UI has shown it. */
        fun clearLive() {
            val p = _liveDownload.value?.phase
            if (p == LivePhase.DONE || p == LivePhase.FAILED) _liveDownload.value = null
        }

        /** Human "2 min" / "45 sec" from seconds. */
        fun formatEta(seconds: Long): String = when {
            seconds >= 90 -> "${(seconds + 30) / 60} min"
            seconds >= 60 -> "1 min"
            else -> "$seconds sec"
        }
    }
}
