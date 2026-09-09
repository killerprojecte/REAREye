package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

/** Shared wire constants for the subscreen client and SystemUI AppEmbed broker. */
object AppEmbedContract {
    /** Explicit broadcast used to request the current SystemUI broker binder. */
    const val REQUEST_ACTION = "hk.uwu.reareye.action.APP_EMBED_BRIDGE_REQUEST"

    /** Explicit SystemUI broadcast announcing that a replacement broker can accept bootstrap. */
    const val BROKER_READY_ACTION = "hk.uwu.reareye.action.APP_EMBED_BROKER_READY"

    /** The only package allowed to publish the broker. */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** The only package allowed to call the broker. */
    const val SUBSCREEN_PACKAGE = "com.xiaomi.subscreencenter"

    /** Bundle entry containing SurfaceControlViewHost.SurfacePackage. */
    const val SURFACE_PACKAGE_KEY = "surface_package"

    /** Maximum wait for a SystemUI bootstrap reply before reporting a terminal connection error. */
    const val CONNECT_TIMEOUT_MILLIS = 3_000L
}
