package hk.uwu.reareye.ui.config

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.reareye.hook.support.ModuleActivationState
import hk.uwu.reareye.hook.support.XposedModuleStatus

/**
 * 监听 libxposed service 绑定、死亡和页面恢复事件，驱动一次性的远程偏好重载。
 *
 * 这里不做定时轮询：配置页面只有在 service 状态事件或 ON_RESUME 到来时增加 revision，
 * 具体页面再在 IO 线程确认 [PrefsManager.isRemoteReady] 后读取配置。
 */
@Composable
internal fun rememberRemotePrefsStatusRevision(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val mainHandler = Handler(Looper.getMainLooper())
        var active = true
        lateinit var refreshRunnable: Runnable

        fun scheduleRefresh() {
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.post(refreshRunnable)
        }

        refreshRunnable = Runnable {
            if (!active) return@Runnable
            XposedModuleStatus.refresh()
            revision++
        }
        val statusObserver: (ModuleActivationState) -> Unit = { scheduleRefresh() }
        val generationObserver: (Long) -> Unit = { scheduleRefresh() }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scheduleRefresh()
        }

        XposedModuleStatus.observe(statusObserver)
        XposedModuleStatus.observeRemotePreferences(generationObserver)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        scheduleRefresh()

        onDispose {
            active = false
            mainHandler.removeCallbacks(refreshRunnable)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            XposedModuleStatus.removeObserver(statusObserver)
            XposedModuleStatus.removeRemotePreferencesObserver(generationObserver)
        }
    }

    return revision
}
