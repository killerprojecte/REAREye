package hk.uwu.reareye.hook.activityhost

/** Registration may block in Binder; publication/dispatch/cancel have one short commit boundary. */
internal class ActivityHostLaunchGuard<C : Any> {
    internal data class Cancellation<C>(val cookie: C?)

    private var cancelled = false
    private var cookie: C? = null

    fun register(value: C, register: () -> Unit, unregister: (C) -> Unit) {
        // Never hold the commit monitor across the external registration Binder call.
        register()
        val published = synchronized(this) {
            if (cancelled) false else {
                check(cookie == null) { "Launch cookie already registered" }
                cookie = value
                true
            }
        }
        if (!published) {
            unregister(value)
            error("Session closed during launch registration")
        }
    }

    @Synchronized
    fun cookie(): C? = cookie

    @Synchronized
    fun dispatch(action: () -> Unit) {
        check(!cancelled && cookie != null) { "Session closed before native launch dispatch" }
        action()
    }

    @Synchronized
    fun cancel(): Cancellation<C>? {
        if (cancelled) return null
        cancelled = true
        return Cancellation(cookie).also { cookie = null }
    }
}
