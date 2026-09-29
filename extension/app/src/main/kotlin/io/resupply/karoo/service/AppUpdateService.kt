package io.resupply.karoo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.resupply.karoo.R
import io.resupply.karoo.util.Connectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Foreground service that downloads the latest release APK over a direct WiFi connection and
 * hands it to the OS [PackageInstaller] so the rider can update the sideloaded app from
 * inside Settings. Modelled on [RegionDownloadService]: single-flight, foreground +
 * partial wake lock (so the download survives the Karoo's screen sleep), and the single
 * writer of a process-wide [progress] flow the Settings screen observes.
 *
 * The APK download uses OkHttp directly (not the Karoo bridge) — the bridge caps responses
 * at ~100 KB, hopeless for a multi-MB APK — so it needs WiFi, gated up front. The install
 * itself is a [PackageInstaller.Session]: the bytes are streamed into the session and
 * committed; the OS then shows its own confirm-install dialog (we're an unprivileged app),
 * which the [InstallReceiver] launches on [PackageInstaller.STATUS_PENDING_USER_ACTION].
 */
class AppUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apkUrl = intent?.getStringExtra(EXTRA_APK_URL)
        if (apkUrl == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Ignore a second request while one is actively running (a lingering DONE/FAILED
        // doesn't block a fresh attempt).
        val active = _progress.value?.phase
        if (active == UpdatePhase.DOWNLOADING || active == UpdatePhase.INSTALLING) {
            Timber.d("app update already in progress; ignoring")
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(0, null))
        acquireWakeLock()
        _progress.value = UpdateProgress(0f, 0L, null, UpdatePhase.DOWNLOADING)

        scope.launch {
            runUpdate(apkUrl)
            finish(startId)
        }
        return START_NOT_STICKY
    }

    private fun runUpdate(apkUrl: String) {
        // Direct download needs WiFi; refuse early with a clear, actionable reason.
        if (!Connectivity.isOnWifi(applicationContext)) {
            fail("Connect to Wi‑Fi to update")
            return
        }

        val apk = File(cacheDir, "app-update.apk")
        apk.delete()
        val ok = runCatching { download(apkUrl, apk) }
            .onFailure { Timber.e(it, "app update download failed") }
            .getOrDefault(false)
        if (!ok) {
            apk.delete()
            // A WiFi drop mid-download surfaces here — give the actionable reason.
            fail(if (!Connectivity.isOnWifi(applicationContext)) "Wi‑Fi lost — reconnect and try again" else "Download failed")
            return
        }

        _progress.value = _progress.value?.copy(
            phase = UpdatePhase.INSTALLING, fraction = 1f, bytesPerSec = 0L, etaSeconds = null,
        )
        updateInstallingNotification()

        val installOk = runCatching { install(apk) }
            .onFailure { Timber.e(it, "app update install session failed") }
            .getOrDefault(false)
        if (!installOk) {
            apk.delete()
            fail("Couldn't start the installer")
            return
        }
        // The session is committed; the OS confirm dialog now takes over (launched by
        // InstallReceiver). We're done — leave DONE in the flow for the UI.
        _progress.value = _progress.value?.copy(phase = UpdatePhase.DONE, fraction = 1f)
    }

    /** Stream [url] into [dest], emitting download progress with rolling rate + ETA. */
    private fun download(url: String, dest: File): Boolean {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("app update HTTP ${response.code} for $url")
                return false
            }
            val body = response.body ?: return false
            val total = body.contentLength().takeIf { it > 0 } ?: 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var windowStartMs = System.currentTimeMillis()
                    var windowStartBytes = 0L
                    var bytesPerSec = 0L
                    var lastEmitMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n

                        val now = System.currentTimeMillis()
                        val windowMs = now - windowStartMs
                        if (windowMs >= 1_000) {
                            bytesPerSec = ((done - windowStartBytes) * 1_000) / windowMs
                            windowStartMs = now
                            windowStartBytes = done
                        }
                        if (now - lastEmitMs >= 200) {
                            emitDownload(done, total, bytesPerSec)
                            lastEmitMs = now
                        }
                    }
                    out.flush()
                    emitDownload(done, total.takeIf { it > 0 } ?: done, bytesPerSec)
                }
            }
        }
        return dest.length() > 0
    }

    private fun emitDownload(done: Long, total: Long, bytesPerSec: Long) {
        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val eta = if (bytesPerSec > 0 && total > done) (total - done) / bytesPerSec else null
        _progress.value = UpdateProgress(fraction, bytesPerSec, eta, UpdatePhase.DOWNLOADING)
        updateNotification((fraction * 100).toInt(), eta)
    }

    /** Push [apk] through a [PackageInstaller.Session] and commit; the OS confirms the install. */
    private fun install(apk: File): Boolean {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app-update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(applicationContext, InstallReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(applicationContext, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        return true
    }

    private fun fail(reason: String) {
        Timber.w("app update failed: $reason")
        _progress.value = _progress.value?.copy(phase = UpdatePhase.FAILED, reason = reason)
            ?: UpdateProgress(0f, 0L, null, UpdatePhase.FAILED, reason)
    }

    private fun finish(startId: Int) {
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
            acquire(10 * 60 * 1000L) // 10 min safety cap; released on finish
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
                        CHANNEL_ID, "App update", NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Progress while downloading an app update." },
                )
            }
        }
    }

    private fun buildNotification(percent: Int, etaSeconds: Long?): Notification {
        val text = when {
            etaSeconds != null && etaSeconds > 0 ->
                "$percent% · ${RegionDownloadService.formatEta(etaSeconds)} left · keep Wi‑Fi on"
            else -> "$percent% · keep Wi‑Fi on"
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Downloading update")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()
    }

    private fun updateNotification(percent: Int, etaSeconds: Long?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(percent, etaSeconds))
    }

    private fun updateInstallingNotification() {
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("Installing update")
            .setContentText("Finishing up…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true) // indeterminate
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    /** Live update state, process-wide (UI + service share the process). */
    enum class UpdatePhase { DOWNLOADING, INSTALLING, DONE, FAILED }

    data class UpdateProgress(
        val fraction: Float,
        val bytesPerSec: Long,
        val etaSeconds: Long?,
        val phase: UpdatePhase,
        val reason: String? = null,
    )

    /**
     * Receives the [PackageInstaller] commit result. On an unprivileged app the first
     * result is [PackageInstaller.STATUS_PENDING_USER_ACTION] carrying the system
     * confirm-install intent — launch it (with NEW_TASK, since we're a receiver). Success
     * replaces the app (this process dies); failures land in [progress] so the UI can retry.
     */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Timber.e(it, "couldn't launch install confirm dialog") }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    // App is being replaced; nothing to do.
                    _progress.value = _progress.value?.copy(phase = UpdatePhase.DONE)
                }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Timber.w("install failed: status=$status msg=$msg")
                    _progress.value = _progress.value?.copy(
                        phase = UpdatePhase.FAILED,
                        reason = msg ?: "Install failed",
                    ) ?: UpdateProgress(0f, 0L, null, UpdatePhase.FAILED, msg ?: "Install failed")
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "app_update"
        private const val NOTIF_ID = 43
        private const val WAKELOCK_TAG = "resupply:app-update"
        private const val EXTRA_APK_URL = "apk_url"

        private val _progress = MutableStateFlow<UpdateProgress?>(null)

        /** The in-flight (or just-finished) update, for the Settings screen. */
        val progress: StateFlow<UpdateProgress?> = _progress.asStateFlow()

        /** Kick off downloading + installing the APK at [apkUrl]. Safe to call from the UI. */
        fun start(context: Context, apkUrl: String) {
            val intent = Intent(context, AppUpdateService::class.java)
                .putExtra(EXTRA_APK_URL, apkUrl)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Clear a lingering terminal (DONE/FAILED) state once the UI has shown it. */
        fun clear() {
            val p = _progress.value?.phase
            if (p == UpdatePhase.DONE || p == UpdatePhase.FAILED) _progress.value = null
        }
    }
}
