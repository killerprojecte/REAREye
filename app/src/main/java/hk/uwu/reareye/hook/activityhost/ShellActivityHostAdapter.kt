package hk.uwu.reareye.hook.activityhost

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import hk.uwu.roxyhook.PackageScope
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer

internal interface ShellActivityHostOwner {
    val surface: SurfaceControl?
    val bounds: Rect
    fun initialized()
    fun prepareLaunch(options: ActivityOptions)
    fun dispatchLaunch(submit: () -> Unit)
    fun taskChanged(info: ActivityManager.RunningTaskInfo)
    fun taskClosed(reason: String)
}

/** Adapts ONLY the private, unattached Shell carriers belonging to our broker. */
internal class ShellActivityHostAdapter(private val loader: ClassLoader) {
    private val reflect = ActivityHostReflection
    private val viewClass = loader.loadClass("com.android.wm.shell.taskview.TaskView")
    private val controllerClass =
        loader.loadClass("com.android.wm.shell.taskview.TaskViewTaskController")
    private val listenerClass = loader.loadClass("com.android.wm.shell.taskview.TaskView\$Listener")
    private val factoryClass =
        loader.loadClass("com.android.wm.shell.taskview.TaskViewFactoryController")
    private val transitionsClass =
        loader.loadClass("com.android.wm.shell.taskview.TaskViewTransitions")
    private val views = Collections.synchronizedMap(IdentityHashMap<Any, ShellActivityHostOwner>())
    private val controllers =
        Collections.synchronizedMap(IdentityHashMap<Any, ShellActivityHostOwner>())
    private val surfaceAbi = ActivityHostSurfaceAbi.resolve(
        viewClass, controllerClass, SurfaceControl::class.java, SurfaceHolder::class.java
    )
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    @Volatile
    private var factory: Any? = null

    fun hasHosts() = views.isNotEmpty()
    fun available(context: Context): Boolean {
        if (factory == null) runCatching {
            // Hook installation may happen after asTaskViewFactory was already called. Resolve
            // the same live DI graph instead of requiring a SystemUI restart or making a dummy.
            val component =
                requireNotNull(reflect.invoke(context.applicationContext, "getWMComponent"))
            val optional = requireNotNull(reflect.invoke(component, "getTaskViewFactory"))
            factory = reflect.invoke(optional, "orElse", null)
        }
        return factory != null
    }

    fun PackageScope.install() {
        // Resolve every hook target before installing any hook. Only use the live DI factory.
        reflect.method(viewClass, "getController")
        reflect.method(
            viewClass, "startActivity", PendingIntent::class.java,
            android.content.Intent::class.java, ActivityOptions::class.java, Rect::class.java
        )
        reflect.method(viewClass, "onLocationChanged", Rect::class.java)
        reflect.field(controllerClass, "mTaskViewController")
        reflect.field(controllerClass, "mSurfaceControl")
        reflect.field(controllerClass, "mShellExecutor")
        reflect.field(controllerClass, "mTaskInfo")
        reflect.method(
            transitionsClass, "prepareActivityOptions", ActivityOptions::class.java,
            Rect::class.java, controllerClass
        )
        loader.loadClass("android.window.WindowContainerTransaction").getConstructor()
        val factoryMethod = reflect.method(factoryClass, "asTaskViewFactory")
        val boundsMethod = reflect.method(viewClass, "getCurrentBoundsOnScreen")
        val surfaceMethod = reflect.method(SurfaceView::class.java, "getSurfaceControl")
        val resizeMethod = reflect.method(
            viewClass, "setResizeBgColor", SurfaceControl.Transaction::class.java,
            Int::class.javaPrimitiveType!!
        )
        val infoMethod = reflect.method(
            viewClass, "onTaskInfoChanged", ActivityManager.RunningTaskInfo::class.java
        )
        factoryMethod.hook {
            after { if (!hasThrowable) factory = result }
        }
        boundsMethod.hook {
            before { views[instance]?.let { result = Rect(it.bounds) } }
        }
        // Only our unattached carriers borrow a host surface, including inside ROM callbacks.
        surfaceMethod.hook {
            before { views[instance]?.let { result = it.surface } }
        }
        resizeMethod.hook {
            before { if (views.containsKey(instance)) result = null }
        }
        infoMethod.hook {
            before {
                views[instance]?.let { owner ->
                    (args(0).value as? ActivityManager.RunningTaskInfo)?.let(owner::taskChanged)
                    result = null
                }
            }
        }
    }

    fun create(context: Context, owner: ShellActivityHostOwner, ready: (Any) -> Unit) {
        val liveFactory =
            factory ?: error("ActivityHost: live TaskViewFactory unavailable; restart SystemUI")
        reflect.invoke(liveFactory, "create", context, mainExecutor, Consumer<Any> { view ->
            try {
                require(viewClass.isInstance(view)) { "Unexpected TaskView ABI" }
                val controller =
                    reflect.invoke(view, "getController") ?: error("No TaskView controller")
                requireNotNull(
                    reflect.field(controllerClass, "mTaskViewController").get(controller)
                ) {
                    "TaskViewController is unavailable"
                }
                views[view] = owner
                controllers[controller] = owner
                val listener =
                    Proxy.newProxyInstance(loader, arrayOf(listenerClass)) { proxy, method, args ->
                        try {
                            when (method.name) {
                            "toString" -> "REAREye ActivityHost listener"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.getOrNull(0)
                                "onInitialized", "onSurfaceAlreadyCreated" -> {
                                owner.initialized(); null
                            }

                            "onTaskCreated" -> {
                                val info =
                                    reflect.field(controllerClass, "mTaskInfo").get(controller)
                                if (info is ActivityManager.RunningTaskInfo) owner.taskChanged(info)
                                null
                            }

                            "onTaskInfoChanged" -> {
                                (args?.getOrNull(0) as? ActivityManager.RunningTaskInfo)?.let(owner::taskChanged)
                                null
                            }

                            "onTaskRemovalStarted", "onReleased" -> {
                                owner.taskClosed(method.name); null
                            }

                            "onBackPressedOnTaskRoot" -> {
                                owner.taskClosed("back_at_task_root"); null
                            }

                            else -> null
                            }
                        } catch (error: Throwable) {
                            runCatching { owner.taskClosed("listener_${method.name}_failed:${error.javaClass.simpleName}") }
                            null
                        }
                    }
                reflect.invoke(view, "setListener", mainExecutor, listener)
                ready(view)
            } catch (error: Throwable) {
                // Even a failed carrier constructor registers a native listener.
                runCatching { release(view, false) }
                owner.taskClosed("carrier_initialization_failed:${error.javaClass.simpleName}")
            }
        })
    }

    fun attachSurface(view: Any, surface: SurfaceControl) {
        check(surface.isValid) { "Host surface was destroyed before native attachment" }
        val controller = requireNotNull(reflect.invoke(view, "getController"))
        // On inlined ROMs, the real carrier callback reads the host surface through our hook.
        // No synthetic holder or attached SurfaceView is needed.
        surfaceAbi.attach(view, controller, surface)
        check(isSurfaceAttached(view, surface)) { "Native surface attachment was not retained" }
    }

    fun isSurfaceAttached(view: Any, surface: SurfaceControl): Boolean {
        val controller = reflect.invoke(view, "getController") ?: return false
        return surface.isValid && reflect.field(controllerClass, "mSurfaceControl")
            .get(controller) === surface
    }

    fun retireSurface(view: Any?, surface: SurfaceControl) {
        val controller = view?.let { reflect.invoke(it, "getController") }
        val executor = controller?.let {
            reflect.field(controllerClass, "mShellExecutor").get(it) as Executor
        }
        // Native surfaceDestroyed/surfaceCreated enqueue leash transactions. Keep the old
        // reference valid until those earlier Shell-executor operations have completed.
        if (executor == null) surface.release() else executor.execute { surface.release() }
    }

    fun afterSurfaceTransactions(view: Any, applied: () -> Unit) {
        val controller = requireNotNull(reflect.invoke(view, "getController"))
        val executor = reflect.field(controllerClass, "mShellExecutor").get(controller) as Executor
        // Surface callbacks/onLocationChanged enqueue native leash work. Publish the ACK snapshot only
        // after the earlier Shell operations and after returning to the session's main queue.
        executor.execute { main.post { applied() } }
    }

    fun updateBounds(view: Any, rect: Rect) {
        reflect.invoke(view, "onLocationChanged", Rect(rect))
    }

    fun launch(view: Any, pendingIntent: PendingIntent, options: ActivityOptions) {
        val controller = reflect.invoke(view, "getController") ?: error("Missing task controller")
        val nativeController = reflect.field(controllerClass, "mTaskViewController").get(controller)
            ?: error("Missing native transitions")
        val owner = requireNotNull(controllers[controller])
        val executor = reflect.field(controllerClass, "mShellExecutor").get(controller) as Executor
        executor.execute {
            runCatching {
                val requestedBounds = Rect(owner.bounds).also {
                    check(!it.isEmpty) { "Launch bounds became empty" }
                }
                // Execute the native preparation, then register its cookie explicitly. This is
                // not an after-hook whose exception could be swallowed while the launch continues.
                reflect.invoke(
                    nativeController,
                    "prepareActivityOptions",
                    options,
                    requestedBounds,
                    controller
                )
                owner.prepareLaunch(options)
                val transaction = loader.loadClass("android.window.WindowContainerTransaction")
                    .getConstructor().newInstance()
                reflect.invoke(
                    transaction,
                    "sendPendingIntent",
                    pendingIntent,
                    null,
                    options.toBundle()
                )
                val cookie =
                    reflect.invoke(options, "getLaunchCookie") ?: error("Missing launch cookie")
                owner.dispatchLaunch {
                    reflect.invoke(
                        nativeController,
                        "startTaskView",
                        transaction,
                        controller,
                        cookie
                    )
                }
            }.onFailure { owner.taskClosed("native_launch_failed:${it.javaClass.simpleName}") }
        }
    }

    fun setVisible(view: Any, value: Boolean) {
        val controller = reflect.invoke(view, "getController") ?: return
        val taskController =
            reflect.field(controllerClass, "mTaskViewController").get(controller) ?: return
        val executor = reflect.field(controllerClass, "mShellExecutor").get(controller) as Executor
        val owner = controllers[controller] ?: return
        executor.execute {
            runCatching { reflect.invoke(taskController, "setTaskViewVisible", controller, value) }
                .onFailure { owner.taskClosed("native_visibility_failed:${it.javaClass.simpleName}") }
        }
    }

    fun detachSurface(view: Any) {
        surfaceAbi.detach(view, requireNotNull(reflect.invoke(view, "getController")))
    }


    fun release(view: Any, removeTask: Boolean) {
        val controller = runCatching { reflect.invoke(view, "getController") }.getOrNull()
        // Native callbacks can outlive release; remove identity mappings before they arrive.
        views.remove(view)
        if (controller != null) controllers.remove(controller)
        if (removeTask) runCatching { reflect.invoke(view, "removeTask") }
        reflect.invoke(view, "release")
    }
}
