package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

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
import java.util.concurrent.atomic.AtomicBoolean

/** Register additional definitions here; neither the Hook nor session knows any catalog fields. */
class MamlVariableBinderHook(
    private val registerSources: MamlDataSourceRegistry.() -> Unit = {},
) : RoxyHooker() {
    private data class Binding(
        val session: MamlDataBinderSession,
        val parameters: MamlBinderParameters
    )

    @Volatile
    private var hasCarriers: () -> Boolean = { false }
    override fun onHotReloadPreflight(): Boolean = !hasCarriers()

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val handles = mutableListOf<HookHandle>()
            val bindings = Collections.synchronizedMap(WeakHashMap<Any, Binding>())
            val claims =
                Collections.synchronizedMap(WeakHashMap<Any, MutableMap<String, WeakReference<Any>>>())
            hasCarriers = { synchronized(bindings) { bindings.isNotEmpty() } }
            val main = Handler(Looper.getMainLooper())
            val installed = AtomicBoolean(false)
            try {
                val bridge = MamlBinderHostBridge(appClassLoader)
                val registry = MamlDataSourceRegistry().apply {
                    register(AppWidgetCatalogDataSource.definition())
                    register(AppWidgetAppsDataSource.definition())
                    register(RearDisplayDataSource.definition())
                    register(RearActivityAppsDataSource.definition())
                    register(RearActivitiesDataSource.definition())
                    registerSources()
                }

                fun query(binding: Binding) {
                    try {
                        binding.session.startQuery(binding.parameters.evaluate())
                    } catch (error: Exception) {
                        binding.session.reportFailure(
                            error.message ?: "Invalid Binder parameters",
                            queryRequested = true
                        )
                    }
                }
                handles += bridge.carrierInit.hook {
                    before {
                        val owner = instance ?: return@before
                        val binding = bindings[owner] ?: return@before
                        result = null
                        binding.session.init()
                        // Parent init owns queryAtStart/dependency and invokes our startQuery hook.
                        runtime.platform.invokeOriginal(bridge.baseInit, owner, emptyArray())
                    }
                }
                handles += bridge.startQuery.hook {
                    before {
                        val binding = bindings[instance] ?: return@before
                        result = null
                        query(binding)
                    }
                }
                handles += bridge.refresh.hook {
                    before {
                        val binding = bindings[instance] ?: return@before
                        result = null
                        query(binding)
                    }
                }
                handles += bridge.tick.hook {
                    before {
                        val binding = bindings[instance] ?: return@before
                        result = null
                        try {
                            binding.session.tick(binding.parameters.evaluate())
                        } catch (error: Exception) {
                            binding.session.reportFailure(
                                error.message ?: "Invalid Binder expression"
                            )
                        }
                    }
                }
                handles += bridge.pause.hook {
                    after { if (!hasThrowable) bindings[instance]?.session?.pause() }
                }
                handles += bridge.resume.hook {
                    after { if (!hasThrowable) bindings[instance]?.session?.resume() }
                }
                handles += bridge.carrierFinish.hook {
                    before {
                        val owner = instance ?: return@before
                        val binding = bindings[owner] ?: return@before
                        result = null
                        binding.session.finish()
                        binding.parameters.clearExpressions()
                        runtime.platform.invokeOriginal(bridge.baseFinish, owner, emptyArray())
                    }
                }
                // Factory is last so a registered instance never lacks a lifecycle hook.
                handles += bridge.create.hook {
                    before {
                        if (!installed.get()) return@before
                        val xml = args[0] as? Element ?: return@before
                        val definition = registry.find(xml.tagName) ?: return@before
                        result = null
                        try {
                            val root = args[1] ?: return@before
                            val manager = args[2] ?: return@before
                            val name = xml.getAttribute("name").trim()
                            require(validMamlDataName(name)) { "A data Binder requires a valid non-empty name" }
                            bridge.requireAvailableName(manager, name)
                            synchronized(claims) {
                                val rootClaims = claims.getOrPut(root) { mutableMapOf() }
                                check(rootClaims[name]?.get() == null) { "Duplicate data Binder name: $name" }
                                val weakRoot = WeakReference(root)
                                val parameters =
                                    MamlBinderParameters.parse(xml, definition) { text, type ->
                                        bridge.compile(
                                            requireNotNull(weakRoot.get()) { "MAML root was released" },
                                            text,
                                            type
                                        )
                                    }
                                val carrier = bridge.carrier(xml, root, manager)
                                val context = bridge.context(root)
                                val session = MamlDataBinderSession(
                                    name,
                                    definition,
                                    context,
                                    bridge.sink(root, carrier, name, definition.schema),
                                    main,
                                )
                                bindings[carrier] = Binding(session, parameters)
                                rootClaims[name] = WeakReference(carrier)
                                result = carrier
                            }
                        } catch (error: Exception) {
                            YLog.warn("Could not create data Binder ${xml.tagName}", error)
                        }
                    }
                }
                runtime.onClose {
                    installed.set(false)
                    val snapshot = synchronized(bindings) { bindings.values.toList() }
                    snapshot.forEach {
                        it.parameters.clearExpressions()
                        it.session.finish()
                    }
                    bindings.clear()
                    claims.clear()
                }
                installed.set(true)
                YLog.info("MAML data source registered: ${AppWidgetCatalogDataSource.TAG}")
            } catch (error: Throwable) {
                installed.set(false)
                handles.asReversed().forEach { runCatching { it.close() } }
                YLog.warn("MAML data sources disabled: incompatible host ABI", error)
            }
        }
    }
}
