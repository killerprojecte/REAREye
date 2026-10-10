package hk.uwu.reareye.hook.scopes

import hk.uwu.roxyhook.PackageScope
import hk.uwu.reareye.hook.support.isRearDevice as detectRearDevice

/** Common entry point and rear-device policy for REAREye business scopes. */
interface Scope {
    /** Installs this scope in the current RoxyHook package context. */
    fun PackageScope.onHook()

    /** Whether the current target device exposes the rear display. */
    val PackageScope.isRearDevice: Boolean
        get() = detectRearDevice
}
