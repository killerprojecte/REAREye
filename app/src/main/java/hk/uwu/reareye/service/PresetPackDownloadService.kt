package hk.uwu.reareye.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import hk.uwu.reareye.repository.presetpack.PresetPackManifest
import hk.uwu.reareye.repository.presetpack.PresetPackRelease
import hk.uwu.reareye.repository.presetpack.PresetPackRepository
import hk.uwu.reareye.ui.MainActivity
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class PresetPackDownloadState(
    val running: Boolean = false,
    val release: PresetPackRelease? = null,
    val downloaded: Long = 0,
    val total: Long = -1,
    val manifest: PresetPackManifest? = null,
    val message: String? = null,
)

/** Download lifetime belongs to this foreground service, not the Compose dialog. */
class PresetPackDownloadService : Service() {
    companion object {
        private const val CHANNEL = "preset_pack_download"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_PAUSE = "hk.uwu.reareye.PAUSE_PRESET_DOWNLOAD"
        private const val MAX_DURATION_MS = 6 * 60 * 60 * 1000L
        private val mutableState = MutableStateFlow(PresetPackDownloadState())
        val state = mutableState.asStateFlow()

        fun clearResult() {
            if (!state.value.running) mutableState.value = PresetPackDownloadState()
        }

        // Called on the UI thread. Mark pending immediately to prevent duplicate taps.
        fun start(context: Context, release: PresetPackRelease) {
            if (state.value.running) return
            mutableState.value = PresetPackDownloadState(running = true, release = release)
            try {
                context.startForegroundService(
                    Intent(
                        context,
                        PresetPackDownloadService::class.java
                    ).apply {
                        putExtra("version", release.version)
                        putExtra("tag", release.tagName)
                        putExtra("url", release.assetUrl)
                        putExtra("name", release.assetName)
                        putExtra("size", release.assetSize)
                        putExtra("releaseUrl", release.releaseUrl)
                    })
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        running = false,
                        message = "无法启动后台下载：${error.message}"
                    )
                }
            }
        }

        fun pause(context: Context) {
            if (state.value.running) {
                context.startService(
                    Intent(
                        context,
                        PresetPackDownloadService::class.java
                    ).setAction(ACTION_PAUSE)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var download: Job? = null
    private var lastNotification = 0L
    private var pauseMessage = "下载已暂停，已保留进度，点击下载可继续"

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "预设资源包下载", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            download?.cancel()
            if (download == null) stopSelf()
            return START_NOT_STICKY
        }
        if (download?.isCompleted == false) return START_REDELIVER_INTENT
        val url = intent?.getStringExtra("url")
        if (url == null) {
            mutableState.update { it.copy(running = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        val release = PresetPackRelease(
            version = intent.getStringExtra("version").orEmpty(),
            tagName = intent.getStringExtra("tag").orEmpty(),
            assetUrl = url,
            assetName = intent.getStringExtra("name").orEmpty(),
            assetSize = intent.getLongExtra("size", -1),
            releaseUrl = intent.getStringExtra("releaseUrl").orEmpty(),
        )
        mutableState.value = PresetPackDownloadState(running = true, release = release)
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    running = false,
                    message = "无法启动后台下载：${error.message}"
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }
        download = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "REAREye:PresetPackDownload")
            try {
                wakeLock.acquire(MAX_DURATION_MS + 60_000)
                val manifest = withTimeout(MAX_DURATION_MS) {
                    withContext(Dispatchers.IO) {
                        PresetPackRepository(
                            applicationContext,
                            applicationContext.getPrefsManager()
                        )
                            .downloadLatest(release) { done, total ->
                                mutableState.update { it.copy(downloaded = done, total = total) }
                                // Downloader serializes callbacks; avoid flooding the notification manager.
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastNotification >= 1000 || done == total) {
                                    lastNotification = now
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, notification(state.value))
                                }
                            }
                    }
                }
                mutableState.update {
                    it.copy(
                        manifest = manifest,
                        message = "已下载 ${manifest.packVersion}，点击应用"
                    )
                }
            } catch (error: TimeoutCancellationException) {
                mutableState.update { it.copy(message = "下载超时，已保留进度，点击下载可继续") }
            } catch (error: CancellationException) {
                mutableState.update { it.copy(message = pauseMessage) }
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(message = "下载未完成：${error.message}。重试时将续传可用分段")
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                mutableState.update { it.copy(running = false) }
                stopForeground(STOP_FOREGROUND_DETACH)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(state.value))
                stopSelf()
            }
        }
        // If Android recreates this service, the intent identifies the same durable download.
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        pauseMessage = "后台下载已达到系统时限，已保留进度，打开应用后可继续"
        download?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: PresetPackDownloadState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (state.running) {
            if (state.total > 0) "已下载 ${state.downloaded * 100 / state.total}% · 可息屏或关闭弹窗"
            else "正在连接，已有下载进度将自动恢复"
        } else state.message.orEmpty()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("预设资源包 ${state.release?.version.orEmpty()}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)
            .setAutoCancel(!state.running)
            .apply {
                if (state.running) {
                    setProgress(
                        100,
                        if (state.total > 0) (state.downloaded * 100 / state.total).toInt() else 0,
                        state.total <= 0
                    )
                    val pause = PendingIntent.getService(
                        this@PresetPackDownloadService, 1,
                        Intent(
                            this@PresetPackDownloadService,
                            PresetPackDownloadService::class.java
                        ).setAction(ACTION_PAUSE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(Notification.Action.Builder(null, "暂停", pause).build())
                }
            }
            .build()
    }
}
