package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.service.notification.StatusBarNotification
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import hk.uwu.reareye.ui.config.ConfigKeys
import java.util.concurrent.ConcurrentHashMap

class SystemUiNotificationBridgeHook : YukiBaseHooker() {
    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val TAG = "REAREye-NotifBridge"
    }

    private val activeSnapshots = ConcurrentHashMap<String, NotificationRouteSnapshot>()
    private val routeClient = NotificationRouteBridgeClient()

    @Volatile
    private var hostContext: Context? = null

    override fun onHook() {
        loadApp(SYSTEM_UI_PACKAGE) {
            debugLog("loadApp process=$processName package=$packageName")

            onAppLifecycle {
                onCreate {
                    val context = appContext ?: return@onCreate
                    hostContext = context.applicationContext ?: context
                    debugLog(
                        "onCreate hostContext=${hostContext?.packageName} action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER} target=${NotificationRouteBridgeContract.HOOK_HOST_PACKAGE}"
                    )
                    bindRouteBridge("app_create")
                }
            }

            val postedHook = runCatching {
                "com.android.systemui.statusbar.notification.MiuiNotificationListener".toClass()
                    .resolve().firstMethod {
                        name = "onNotificationPosted"
                        parameterCount = 2
                    }.hook().after {
                        handleNotificationPosted(args.getOrNull(0) as? StatusBarNotification)
                    }
                "listener_method"
            }.recoverCatching {
                val runnableClass =
                    $$$"com.android.systemui.statusbar.notification.MiuiNotificationListener$$ExternalSyntheticLambda2".toClass()
                        .resolve()
                runnableClass.firstMethod {
                    name = "run"
                }.hook().after {
                    val sbn = instance.asResolver().firstField {
                        type(StatusBarNotification::class.java)
                    }.get<StatusBarNotification>()
                    handleNotificationPosted(sbn)
                }
                "synthetic_fallback"
            }
            postedHook.onSuccess { strategy ->
                YLog.info("[$TAG] capability notificationPosted=true strategy=$strategy")
            }.onFailure { error ->
                YLog.warn("[$TAG] capability notificationPosted=false reason=${error.message}")
                YLog.warn(error)
            }

            val removedHook = runCatching {
                "com.android.systemui.statusbar.notification.MiuiNotificationListener".toClass()
                    .resolve().firstMethod {
                        name = "onNotificationRemoved"
                        parameterCount = 3
                    }.hook().after {
                        handleNotificationRemoved(
                            sbn = args.getOrNull(0) as? StatusBarNotification,
                            removeReason = args.getOrNull(2) as? Int ?: 1,
                        )
                    }
                "listener_method"
            }.recoverCatching {
                val runnableClass =
                    $$$"com.android.systemui.statusbar.notification.MiuiNotificationListener$$ExternalSyntheticLambda1".toClass()
                        .resolve()
                runnableClass.firstMethod {
                    name = "run"
                }.hook().after {
                    val sbn = instance.asResolver().firstField {
                        type(StatusBarNotification::class.java)
                    }.get<StatusBarNotification>()
                    handleNotificationRemoved(
                        sbn = sbn,
                        removeReason = instance.asResolver().lastField {
                            type(Int::class.java)
                        }.get<Int>() ?: 1,
                    )
                }
                "synthetic_fallback"
            }
            removedHook.onSuccess { strategy ->
                YLog.info("[$TAG] capability notificationRemoved=true strategy=$strategy")
            }.onFailure { error ->
                YLog.warn("[$TAG] capability notificationRemoved=false reason=${error.message}")
                YLog.warn(error)
            }
        }
    }

    private fun bindRouteBridge(reason: String): Boolean {
        val context = hostContext ?: run {
            debugLog("route bridge bind skipped reason=$reason hostContext=null")
            return false
        }
        debugLog(
            "route bridge bind start reason=$reason connected=${routeClient.isConnected()} action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
        )
        val ok = routeClient.bind(
            context = context,
            onConnected = {
                debugLog("route bridge connected reason=$reason")
            },
            onClosed = {
                debugLog("route bridge closed reason=$it")
            },
            timeoutMs = NOTIFICATION_ROUTE_BIND_TIMEOUT_MS,
        )
        if (!ok) {
            debugLog(
                "route bridge handshake pending reason=$reason action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
            )
        }
        return ok
    }

    private fun handleNotificationPosted(sbn: StatusBarNotification?) {
        val current = sbn ?: return
        val snapshot = NotificationRouteSnapshot.fromStatusBarNotification(current)
        if (snapshot == null) {
            NotificationRouteSnapshot.identityKeyFor(current)?.let { key ->
                activeSnapshots.remove(key)?.let { removed ->
                    dispatchRemoved(removed, removeReason = 1, reason = "filtered_post")
                }
            }
            return
        }

        activeSnapshots[snapshot.stableKey()] = snapshot
        debugLog(
            "posted accepted pkg=${snapshot.packageName} cacheSize=${activeSnapshots.size}"
        )
        dispatchPosted(snapshot, reason = "live_post")
    }

    private fun handleNotificationRemoved(sbn: StatusBarNotification?, removeReason: Int) {
        val current = sbn ?: return
        val snapshot = NotificationRouteSnapshot.identityKeyFor(current)
            ?.let(activeSnapshots::remove)
            ?: NotificationRouteSnapshot.fromStatusBarNotification(
                current,
                requirePlainExtras = false,
            )
            ?: run {
                return
            }
        debugLog(
            "removed accepted pkg=${snapshot.packageName} cacheSize=${activeSnapshots.size} reason=$removeReason"
        )
        dispatchRemoved(snapshot, removeReason, reason = "live_remove")
    }

    private fun dispatchPosted(snapshot: NotificationRouteSnapshot, reason: String) {
        val ok = routeClient.dispatch(
            NotificationRouteBridgeContract.Subchannel.NOTIFICATION_POSTED,
            snapshot.toBundle(),
        )
        if (!ok) {
            debugLog("dispatch posted failed pkg=${snapshot.packageName} reason=$reason")
        }
    }

    private fun dispatchRemoved(
        snapshot: NotificationRouteSnapshot,
        removeReason: Int,
        reason: String,
    ) {
        val ok = routeClient.dispatch(
            NotificationRouteBridgeContract.Subchannel.NOTIFICATION_REMOVED,
            snapshot.toRemovalBundle(removeReason),
        )
        if (!ok) {
            debugLog("dispatch removed failed pkg=${snapshot.packageName} reason=$reason")
        }
    }

    private fun debugLog(message: String) {
        if (prefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            YLog.debug("[$TAG] $message")
        }
    }
}
