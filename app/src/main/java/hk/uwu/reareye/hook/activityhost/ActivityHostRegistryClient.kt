package hk.uwu.reareye.hook.activityhost

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeContract
import hk.uwu.reareye.internal.activityhost.IActivityHostTaskRegistry
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Per-launch discovery: no cached stale Binder and no sensitive operation replay. */
internal object ActivityHostRegistryClient {
    fun connect(context: Context): IActivityHostTaskRegistry {
        val expectedUid = context.packageManager.getApplicationInfo("android", 0).uid
        val response = AtomicReference<IActivityHostTaskRegistry?>()
        val latch = CountDownLatch(1)
        val request = object : IHookHostBridgeBootstrap.Stub() {
            override fun onBinderReady(binder: IBinder?) {
                // This callback's unguessable Binder is the request nonce. Identity is checked
                // before clearing it or crossing an executor boundary.
                if (Binder.getCallingUid() != expectedUid || binder == null) return
                response.compareAndSet(null, IActivityHostTaskRegistry.Stub.asInterface(binder))
                latch.countDown()
            }
        }
        val extras = Bundle().apply { putBinder(HookHostBridgeContract.Extras.BINDER, request) }
        context.sendBroadcast(
            Intent(ActivityHostContract.REGISTRY_ACTION).setPackage("android")
                .putExtra(HookHostBridgeContract.Extras.BUNDLE, extras)
        )
        check(
            latch.await(
                2,
                TimeUnit.SECONDS
            )
        ) { "ActivityHost task configuration service unavailable" }
        return requireNotNull(response.get()).also {
            check(it.protocolVersion == ActivityHostContract.VERSION) { "ActivityHost registry version mismatch" }
        }
    }
}
