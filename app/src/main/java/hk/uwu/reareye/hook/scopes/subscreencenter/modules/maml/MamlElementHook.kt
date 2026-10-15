package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.HookHandle
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.w3c.dom.Element
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Injects only registered leaf tags. Ordinary host elements and callbacks are untouched. */
class MamlElementHook(
    private val registerElements: MamlElementRegistry.() -> Unit = {},
) : RoxyHooker() {
    @Volatile
    private var hasCarriers: () -> Boolean = { false }

    companion object {
        private val rootInputs = Collections.synchronizedMap(
            WeakHashMap<Any, MutableList<WeakReference<MamlElementSession>>>(),
        )

        internal fun registerInputs(root: Any, session: MamlElementSession) =
            synchronized(rootInputs) {
                val inputs = rootInputs.getOrPut(root) { mutableListOf() }
                inputs.removeAll { it.get() == null }
                inputs.add(WeakReference(session))
                Unit
            }

        /** Call under the native render mutex after the complete config has been published. */
        internal fun refreshInputs(root: Any) {
            val sessions =
                synchronized(rootInputs) { rootInputs[root]?.mapNotNull { it.get() }.orEmpty() }
            sessions.forEach { it.tickInputs() }
        }
    }

    // Existing native Layer instances cannot be re-associated with a new module class loader.
    // Refuse a live hot reload rather than leave detached widgets or blank carrier elements.
    override fun onHotReloadPreflight(): Boolean = !hasCarriers()

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val handles = mutableListOf<HookHandle>()
            val sessions = Collections.synchronizedMap(WeakHashMap<Any, MamlElementSession>())
            hasCarriers = { synchronized(sessions) { sessions.isNotEmpty() } }
            val main = Handler(Looper.getMainLooper())
            val installed = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val bridge = MamlHostBridge(appClassLoader)
                val launcher = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
                val launcherResume = MamlHostBridge.method(launcher, "onResume")
                val launcherPause = MamlHostBridge.method(launcher, "onPause")
                val launcherDestroy = MamlHostBridge.method(launcher, "onDestroy")
                val launcherResult = MamlHostBridge.method(
                    launcher, "onActivityResult", Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Intent::class.java,
                )
                val registry = MamlElementRegistry().apply {
                    register(AppWidgetElementFactory.TAG, AppWidgetElementFactory())
                    register(ActivityHostElementFactory.TAG, ActivityHostElementFactory())
                    registerElements()
                }

                // Patch only the options produced by an interaction inside our Widget host.
                // Do not replace PendingIntents or intercept ordinary card/Activity launches.
                handles += MamlHostBridge.method(
                    "android.widget.RemoteViews\$RemoteResponse".toClass(), "getLaunchOptions",
                    android.view.View::class.java,
                ).hook {
                    after {
                        if (hasThrowable) return@after
                        val view = args[0] as? android.view.View ?: return@after
                        val options = (result as? android.util.Pair<*, *>)?.second
                                as? android.app.ActivityOptions ?: return@after
                        if (WidgetLaunchPolicy.routeToMainDisplay(view, options)) {
                            YLog.info("MAML widget click routed to main display=0")
                        }
                    }
                }
                handles += bridge.getView.hook {
                    before { sessions[instance]?.view?.let { result = it } }
                }
                handles += bridge.init.hook {
                    before {
                        val element = instance ?: return@before
                        val session = sessions[element] ?: return@before
                        result = null
                        session.init {
                            try {
                                runtime.platform.invokeOriginal(
                                    bridge.holderInit,
                                    element,
                                    emptyArray()
                                )
                            } catch (error: Throwable) {
                                runCatching {
                                    runtime.platform.invokeOriginal(
                                        bridge.finish,
                                        element,
                                        emptyArray()
                                    )
                                }
                                throw error
                            }
                        }
                    }
                }
                handles += bridge.tick.hook {
                    before {
                        val element = instance ?: return@before
                        val session = sessions[element] ?: return@before
                        result = null
                        if (session.canTick()) {
                            // Invoke the exact superclass body, not Method.invoke (virtual recursion).
                            runtime.platform.invokeOriginal(bridge.animationTick, element, args)
                            val changed = session.geometry(
                                bridge.geometry(element),
                                bridge.layoutParams(element)
                            )
                            if (bridge.updateOutline(element, changed)) session.outlineChanged()
                            session.tickInputs()
                        }
                    }
                }
                handles += bridge.render.hook {
                    before { if (sessions.containsKey(instance)) result = null }
                }
                handles += bridge.updateView.hook {
                    before {
                        val element = instance ?: return@before
                        val session = sessions[element] ?: return@before
                        result = null
                        session.geometry(bridge.geometry(element), bridge.layoutParams(element))
                    }
                }
                handles += bridge.visibility.hook {
                    before {
                        val session = sessions[instance] ?: return@before
                        result = null
                        val visible = args[0] as Boolean
                        // A hidden carrier may never reach doTick. Sample on the native
                        // visibility transition, after ScreenElement updates its visible flag.
                        session.visibility(visible)
                        if (visible) session.tickInputs()
                    }
                }
                handles += bridge.resume.hook {
                    after { if (!hasThrowable) sessions[instance]?.resume() }
                }
                handles += bridge.pause.hook {
                    after { if (!hasThrowable) sessions[instance]?.pause() }
                }
                handles += bridge.finish.hook {
                    before {
                        val element = instance ?: return@before
                        val session = sessions[element] ?: return@before
                        result = null
                        session.finish {
                            runtime.platform.invokeOriginal(bridge.finish, element, emptyArray())
                        }
                    }
                }
                handles += launcherResume.hook {
                    after {
                        (instance as? Activity)?.takeIf { launcher.isInstance(it) }
                            ?.let(AppWidgetActivityBridge::onResumed)
                    }
                }
                handles += launcherPause.hook {
                    after {
                        (instance as? Activity)?.takeIf { launcher.isInstance(it) }
                            ?.let(AppWidgetActivityBridge::onPaused)
                    }
                }
                handles += launcherDestroy.hook {
                    before {
                        (instance as? Activity)?.takeIf { launcher.isInstance(it) }
                            ?.let(AppWidgetActivityBridge::onDestroyed)
                    }
                }
                handles += launcherResult.hook {
                    before {
                        val activity = instance as? Activity ?: return@before
                        if (launcher.isInstance(activity) && AppWidgetActivityBridge.onActivityResult(
                                activity, args[0] as Int, args[1] as Int, args[2] as? Intent,
                            )
                        ) result = null
                    }
                }
                // Install factory last: no custom element can escape before every bridge is ready.
                handles += bridge.create.hook {
                    before {
                        if (!installed.get()) return@before
                        val source = args[0] as? Element ?: return@before
                        val factory = registry.find(source.tagName) ?: return@before
                        // Own this tag even on error: never invoke a host callback for it.
                        result = null
                        try {
                            val xml = copyMamlCarrierXml(source)
                            val root = args[1] ?: return@before
                            val carrier = bridge.createCarrier(xml, root)
                            val weakCarrier = WeakReference(carrier)
                            val binding = (factory as? MamlExpressionBackendFactory)
                                ?.prepare(source, bridge.expressions(root))
                            val session = MamlElementSession(
                                bridge.context(root),
                                binding?.createBackend ?: { factory.create(xml) },
                                main,
                                applyOutline = {
                                    weakCarrier.get()?.let(bridge::applyOutline)
                                },
                                evaluateInputs = binding?.let { prepared ->
                                    {
                                        prepared.evaluateInputs().copy(
                                            visible = weakCarrier.get()
                                                ?.let(bridge::visible) == true,
                                        )
                                    }
                                },
                            )
                            sessions[carrier] = session
                            registerInputs(root, session)
                            result = carrier
                        } catch (error: Throwable) {
                            YLog.warn("Could not create MAML tag ${source.tagName}", error)
                        }
                    }
                }
                runtime.onClose {
                    installed.set(false)
                    val snapshot = synchronized(sessions) { sessions.values.toList() }
                    snapshot.forEach { it.finish() }
                    sessions.clear()
                }
                installed.set(true)
                YLog.info("MAML View backends registered: ${AppWidgetElementFactory.TAG}, ${ActivityHostElementFactory.TAG}; ActivityHost requires explicit user launch and verified SystemUI ABI")
            } catch (error: Throwable) {
                installed.set(false)
                handles.asReversed().forEach { runCatching { it.close() } }
                YLog.warn("MAML extensions disabled: host ABI or hook installation failed", error)
            }
        }
    }
}
