package hk.uwu.reareye.hook.activityhost

import java.lang.reflect.Method

/** Resolves a complete lifecycle before hooks are installed; invocation never retries another ABI. */
internal class ActivityHostSurfaceAbi private constructor(
    private val created: Method,
    private val destroyed: Method,
    private val holder: Method?
) {
    fun attach(view: Any, controller: Any, surface: Any) {
        if (holder == null) created.invoke(controller, surface)
        else created.invoke(view, holder.invoke(view))
    }

    fun detach(view: Any, controller: Any) {
        if (holder == null) destroyed.invoke(controller)
        else destroyed.invoke(view, holder.invoke(view))
    }

    companion object {
        fun resolve(
            viewClass: Class<*>,
            controllerClass: Class<*>,
            surfaceClass: Class<*>,
            holderClass: Class<*>
        ): ActivityHostSurfaceAbi {
            val reflect = ActivityHostReflection
            val direct = runCatching {
                ActivityHostSurfaceAbi(
                    reflect.method(controllerClass, "surfaceCreated", surfaceClass),
                    reflect.method(controllerClass, "surfaceDestroyed"),
                    null
                )
            }.getOrNull()
            if (direct != null) return direct
            // R8 may inline the controller lifecycle into TaskView's real callbacks. The
            // carrier's getSurfaceControl hook supplies our host surface to that native code.
            return ActivityHostSurfaceAbi(
                reflect.method(viewClass, "surfaceCreated", holderClass),
                reflect.method(viewClass, "surfaceDestroyed", holderClass),
                reflect.method(viewClass, "getHolder")
            )
        }
    }
}
