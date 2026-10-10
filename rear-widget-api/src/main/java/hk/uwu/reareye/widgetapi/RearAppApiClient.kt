package hk.uwu.reareye.widgetapi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class RearAppApiClient(
    private val hookHostPackage: String = RearAppApiContract.HOOK_HOST_PACKAGE,
) {
    @Volatile
    private var remote: IRearAppApiService? = null

    open fun bind(context: Context, onConnected: (() -> Unit)? = null): Boolean {
        remote?.let {
            onConnected?.invoke()
            return true
        }
        val appContext = context.applicationContext
        if (bindOnce(appContext, false, 1500L) || bindOnce(appContext, true, 2500L)) {
            onConnected?.invoke()
            return true
        }
        return false
    }

    private fun bindOnce(context: Context, forceSync: Boolean, timeoutMs: Long): Boolean {
        remote?.let { return true }
        val latch = CountDownLatch(1)
        val callback = object : IRearAppApiConnection.Stub() {
            override fun onServiceConnected(service: IRearAppApiService?) {
                remote = service
                latch.countDown()
            }
        }
        requestHookServiceBootstrap(context, callback, forceSync)
        return runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false) && remote != null
    }

    open fun unbind() {
        remote = null
    }

    open fun getCatalog(): Bundle = requireRemote().getCatalog() ?: Bundle()

    open fun registerAppCard(title: String, componentBusiness: String): Bundle {
        return requireRemote().registerAppCard(title, componentBusiness)
            ?: failureBundle("hook service returned an empty register result")
    }

    open fun renameAppCard(appId: String, title: String): Bundle {
        return requireRemote().renameAppCard(appId, title)
            ?: failureBundle("hook service returned an empty rename result")
    }

    open fun renderAppCardPreview(appId: String): Bundle {
        return requireRemote().renderAppCardPreview(appId)
            ?: failureBundle("hook service returned an empty preview result")
    }

    open fun deleteAppCard(appId: String): Bundle {
        return requireRemote().deleteAppCard(appId)
            ?: failureBundle("hook service returned an empty delete result")
    }

    open fun reorderAppCards(appIds: List<String>): Bundle {
        val request = Bundle().apply {
            putStringArrayList(
                RearAppApiContract.BundleKeys.ORDERED_APP_IDS,
                ArrayList(appIds),
            )
        }
        return requireRemote().reorderAppCards(request)
            ?: failureBundle("hook service returned an empty reorder result")
    }

    private fun requireRemote(): IRearAppApiService {
        return remote ?: error("RearApp API service is not connected")
    }

    private fun failureBundle(message: String): Bundle = Bundle().apply {
        putBoolean(RearAppApiContract.BundleKeys.SUCCESS, false)
        putString(RearAppApiContract.BundleKeys.ERROR, message)
    }

    private fun requestHookServiceBootstrap(
        context: Context,
        callback: IRearAppApiConnection,
        forceSync: Boolean,
    ) {
        val bundle = Bundle().apply {
            putBinder(RearAppApiContract.Extras.BINDER, callback.asBinder())
        }
        val intent = Intent(RearAppApiContract.ACTION_REQUEST_HOOK_SERVICE)
            .setPackage(hookHostPackage)
            .putExtra(RearAppApiContract.Extras.BUNDLE, bundle)
            .putExtra(RearAppApiContract.Extras.FORCE_SYNC, forceSync)
        context.sendBroadcast(intent)
    }
}
