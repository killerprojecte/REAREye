package hk.uwu.reareye.hook.scopes.system.modules

import android.os.Build
import android.os.Handler
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

/** Keeps HyperOS zero-launch behavior, but bounds waits that lost their native timeout. */
class TopResumedActivityFixHook : RoxyHooker() {
    private val timeoutRecovery = ThreadLocal<Boolean>()
    private var installed = false
    private var failureLogged = false

    private fun warnOnce(error: Throwable) {
        if (failureLogged) return
        failureLogged = true
        YLog.warn("Top-resumed recovery failed; leaving system state unchanged", error)
    }

    override fun PackageScope.onHook() {
        if (Build.VERSION.SDK_INT < 37 || !"xiaomi".equals(Build.MANUFACTURER, true)) return
        loadSystem {
            fun enabled() = installed && hookPrefs.getBoolean(
                ConfigKeys.MISC_HOOK_TOP_RESUMED_FIX, false
            )

            runCatching {
                val supervisorClass = "com.android.server.wm.ActivityTaskSupervisor".toClass()
                val recordClass = "com.android.server.wm.ActivityRecord".toClass()
                val implClass = "com.android.server.wm.ActivityTaskSupervisorImpl".toClass()
                val waitingField =
                    supervisorClass.getDeclaredField("mWaitingTopResumedLostActivity")
                        .apply { isAccessible = true; check(type == recordClass) }
                val handlerField = supervisorClass.getDeclaredField("mHandler")
                    .apply {
                        isAccessible = true; check(Handler::class.java.isAssignableFrom(type))
                    }
                val timeoutMessage = supervisorClass
                    .getDeclaredField("TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG")
                    .apply { isAccessible = true }
                    .getInt(null)
                val scheduleTimeout = supervisorClass.getDeclaredMethod(
                    "scheduleTopResumedStateLossTimeout", recordClass
                ).apply { isAccessible = true; check(returnType == Void.TYPE) }
                val gain = supervisorClass.resolve().firstMethod {
                    name = "scheduleTopResumedStateGainIfNeeded"
                    parameterCount = 0
                    returnType = Void.TYPE
                }
                val release = supervisorClass.resolve().firstMethod {
                    name = "handleTopResumedStateReleasedIfNeeded"
                    parameters(recordClass, Boolean::class.java)
                    returnType = Void.TYPE
                }
                val optimization = implClass.resolve().firstMethod {
                    name = "scheduleTopResumedActivity"
                    parameters(Boolean::class.java, recordClass)
                    returnType = Boolean::class.java
                }

                optimization.hook {
                    hotReloadPolicy = HotReloadPolicy.KEEP
                    after {
                        // Only bypass re-entry while the native timeout is releasing this wait.
                        if (enabled() && timeoutRecovery.get() == true) result = false
                    }
                }
                release.hook {
                    hotReloadPolicy = HotReloadPolicy.KEEP
                    replaceUnit {
                        val recovering = if (enabled() && args(1).boolean()) {
                            runCatching {
                                val waiting = waitingField.get(instance)
                                waiting != null && waiting === args(0).value
                            }.onFailure(::warnOnce).getOrDefault(false)
                        } else false
                        if (!recovering) {
                            callOriginal(*args)
                            return@replaceUnit
                        }
                        val previous = timeoutRecovery.get()
                        timeoutRecovery.set(true)
                        try {
                            YLog.info("Top-resumed native timeout recovery")
                            callOriginal(*args)
                        } finally {
                            if (previous == null) timeoutRecovery.remove()
                            else timeoutRecovery.set(previous)
                        }
                    }
                }
                gain.hook {
                    hotReloadPolicy = HotReloadPolicy.KEEP
                    after {
                        if (!enabled()) return@after
                        runCatching {
                            val supervisor = instance ?: return@runCatching
                            val waiting = waitingField.get(supervisor) ?: return@runCatching
                            val handler = handlerField.get(supervisor) as Handler
                            // Do not reset an existing timeout or extend its deadline.
                            if (!handler.hasMessages(timeoutMessage)) {
                                scheduleTimeout.invoke(supervisor, waiting)
                            }
                        }.onFailure(::warnOnce)
                    }
                }
                // Partially installed hooks remain inert if any registration failed.
                installed = true
                YLog.info("Top-resumed recovery hook installed (controlled by Misc setting)")
            }.onFailure {
                YLog.warn("Top-resumed recovery unavailable on this system", it)
            }
        }
    }
}
