package hk.uwu.reareye.hook.hostbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import hk.uwu.reareye.hook.support.ReloadableReceiverRegistration
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap

class HookHostBridgeBootstrapRegistry(
    private val action: String,
    private val binderProvider: () -> IBinder?,
    private val onRequest: (Intent) -> Unit = {},
    private val logger: ((String) -> Unit)? = null,
) {
    private val registration = ReloadableReceiverRegistration(
        label = "host bridge bootstrap receiver action=$action",
        logger = { message, throwable ->
            logger?.invoke("$message err=${throwable?.message}")
        },
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != action) return

            onRequest(intent)

            val callbackBinder = intent
                .getBundleExtra(HookHostBridgeContract.Extras.BUNDLE)
                ?.getBinder(HookHostBridgeContract.Extras.BINDER)
            val callback = IHookHostBridgeBootstrap.Stub.asInterface(callbackBinder)

            runCatching {
                callback?.onBinderReady(binderProvider())
            }.onFailure {
                logger?.invoke(
                    "host bridge bootstrap reply failed action=$action err=${it.message}"
                )
            }
        }
    }

    fun register(
        context: Context,
        requiredPermission: String? = null,
    ): Boolean {
        return registration.register(context) { registrationContext ->
            ContextCompat.registerReceiver(
                registrationContext,
                receiver,
                IntentFilter(action),
                requiredPermission,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    /**
     * 解除旧代 Hook 的动态 Receiver，避免热重载后旧模块继续接收 Binder 引导广播。
     *
     * 注册组件持有注册时使用的同一 Context；未注册时返回 true，便于冻结流程幂等执行。
     */
    fun isRegistered(): Boolean = registration.isRegistered()

    fun isRegistrationConsistent(): Boolean = registration.isConsistent()

    fun unregister(): Boolean {
        return registration.unregister { context ->
            context.unregisterReceiver(receiver)
        }
    }
}
