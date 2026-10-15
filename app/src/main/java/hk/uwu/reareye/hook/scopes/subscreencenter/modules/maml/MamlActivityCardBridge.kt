package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import hk.uwu.reareye.hook.activityhost.ActivityHostSpec
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/** Activity-only ABI adapter. It never reads or modifies native Widget ConfigFile. */
internal class MamlActivityCardBridge(loader: ClassLoader) {
    private val rootClass = loader.loadClass("com.miui.maml.ScreenElementRoot")
    private val rawAttr = MamlHostBridge.method(rootClass, "getRawAttr", String::class.java)
    private val getContext = MamlHostBridge.method(rootClass, "getContext")
    private val contextField = field(getContext.returnType, "mContext")
    private val variables = MamlHostBridge.method(rootClass, "getVariables")
    private val putText =
        MamlHostBridge.method(variables.returnType, "put", String::class.java, Any::class.java)
    private val putNumber = MamlHostBridge.method(
        variables.returnType,
        "put",
        String::class.java,
        Double::class.javaPrimitiveType!!
    )
    private val getText = MamlHostBridge.method(variables.returnType, "get", String::class.java)
    private val mutex = MamlHostBridge.method(rootClass, "getRenderMutex")
    private val update = MamlHostBridge.method(rootClass, "requestUpdate")
    private val bindings = Collections.synchronizedMap(WeakHashMap<Any, Binding>())
    private val retiredRoots = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private val issuedLaunches = WeakHashMap<Any, ActivityHostLaunchPermit>()

        fun getIssuedLaunch(
            root: Any, instance: String, component: String, dpi: Int,
            request: String
        ): ActivityHostLaunchPermit? {
            val issued = synchronized(issuedLaunches) { issuedLaunches[root] }
            if (issued != null && !issued.isValid()) {
                // Called while collecting native inputs. A closed/paused task must return
                // the passive template to its picker, not keep an expired launch string.
                // Render mutex precedes the capability map lock, as in the publisher.
                val mutex = requireNotNull(
                    MamlHostBridge.method(root.javaClass, "getRenderMutex").invoke(root)
                )
                synchronized(mutex) {
                    val expired = synchronized(issuedLaunches) {
                        if (issuedLaunches[root] === issued && !issued.isValid()) {
                            issuedLaunches.remove(root)
                            true
                        } else false
                    }
                    if (expired) {
                        val vars = requireNotNull(
                            MamlHostBridge.method(root.javaClass, "getVariables").invoke(root)
                        )
                        val current =
                            MamlHostBridge.method(vars.javaClass, "get", String::class.java)
                                .invoke(vars, "ra_launchRequest")
                        if (current == issued.request) {
                            MamlHostBridge.method(
                                vars.javaClass,
                                "put",
                                String::class.java,
                                Any::class.java
                            )
                                .invoke(vars, "ra_launchRequest", "")
                            MamlHostBridge.method(root.javaClass, "requestUpdate").invoke(root)
                        }
                    }
                }
            }
            return synchronized(issuedLaunches) {
                issuedLaunches[root]?.takeIf {
                    it.isValid() && it.instance == instance && it.component == component &&
                        it.dpi == dpi && it.request == request
                }
            }
        }

        private fun revokeLaunch(root: Any) = synchronized(issuedLaunches) {
            issuedLaunches.remove(root)?.revoke()
        }

        private fun issueLaunch(
            root: Any, state: ActivityHostSelectionState, component: String,
            dpi: Int, request: String, acceptedOrder: Long
        ) = synchronized(issuedLaunches) {
            issuedLaunches.remove(root)?.revoke()
            issuedLaunches[root] =
                ActivityHostLaunchPermit(state.instance, component, dpi, request, acceptedOrder)
        }
    }

    private class Binding(
        val path: String, val store: ActivityHostSelectionStore,
        var state: ActivityHostSelectionState
    ) {
        var sequence = 0L
        val replacing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        var close: (() -> Unit)? = null
    }

    fun enabled(root: Any): Boolean = rawAttr.invoke(root, "rearActivityPicker") == "true" &&
            rawAttr.invoke(root, "rearWidgetPicker") != "true"

    @Suppress("UNUSED_PARAMETER")
    fun prepare(root: Any, file: File) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun commit(root: Any, file: File) = Unit

    fun initialize(root: Any, file: File): String = locked(root) {
        check(enabled(root)) { "Activity picker is disabled or conflicts with Widget picker" }
        check(retiredRoots[root] != true) { "Retired root cannot restore a card" }
        ActivityHostSelectionStore.selectionFile(file)
        bindings[root]?.let {
            check(it.path == file.canonicalPath && it.store.isActive())
            number(root, "ra_actionSequence", it.sequence.toDouble())
            publish(root, it.state, "")
            return@locked it.state.instance
        }
        number(root, "ra_ready", 0.0)
        text(root, "ra_launchRequest", "")
        val store = ActivityHostSelectionStore.open(file)
        val restored = store.restore()
        val state = prune(context(root), restored)
        if (state != restored) store.write(state)
        val binding = Binding(file.canonicalPath, store, state)
        bindings[root] = binding
        try {
            subscribe(root, binding)
            number(root, "ra_actionSequence", 0.0)
            clearDraft(root)
            text(root, "ra_action", "")
            text(root, "ra_launchRequest", "")
            publish(
                root,
                state,
                if (restored != state) "Saved Activity is no longer available" else ""
            )
            state.instance
        } catch (error: Throwable) {
            bindings.remove(root)
            store.retire()
            binding.close?.invoke()
            throw error
        }
    }

    fun action(
        root: Any,
        file: File,
        acceptedOrder: Long = ActivityHostLaunchPermit.newBoundary()
    ) = locked(root) {
        val binding = bindings[root] ?: return@locked
        if (!enabled(root) || retiredRoots[root] == true || !binding.store.isActive()) return@locked
        if (file.canonicalPath != binding.path) return@locked
        val raw = getText.invoke(variables.invoke(root), "ra_action") as? String ?: return@locked
        text(root, "ra_action", "")
        try {
            val command = ActivityHostSelectionState.Action.parse(raw)
            require(command.instance == binding.state.instance && command.sequence > binding.sequence) {
                "Stale Activity action"
            }
            binding.sequence = command.sequence
            number(root, "ra_actionSequence", binding.sequence.toDouble())
            binding.store.active {
                when (command.operation) {
                    "load" -> {
                        require(validPackage(command.pkg))
                        val previous = binding.state
                        val next = prune(context(root), previous, binding.replacing)
                        if (next != previous) {
                            binding.store.write(next); binding.state = next
                        }
                        val saved = next.entries[command.pkg]
                        text(root, "ra_savedComponent", saved?.component ?: "")
                        text(
                            root, "ra_savedActivityClass", saved?.component
                                ?.let(ComponentName::unflattenFromString)?.className ?: ""
                        )
                        number(root, "ra_savedDpi", (saved?.dpi ?: 0).toDouble())
                        number(root, "ra_savedFound", if (saved == null) 0.0 else 1.0)
                        text(root, "ra_loadedPackage", command.pkg)
                        number(root, "ra_loadRevision", command.sequence.toDouble())
                        text(root, "ra_error", "")
                        if (next != previous) publish(
                            root,
                            next,
                            "Saved Activity is no longer available"
                        )
                    }

                    "save" -> {
                        require(
                            valid(
                                context(root),
                                command.pkg,
                                ActivityHostSelectionState.Entry(command.component, command.dpi)
                            )
                        ) {
                            "Activity is unavailable, disabled, private, permission-protected or an alias"
                        }
                        val next = binding.state.save(command.pkg, command.component, command.dpi)
                        binding.store.write(next)
                        binding.state = next
                        publish(root, next, "")
                        val request =
                            "${next.instance}|${command.component}|${command.dpi}|${command.sequence}"
                        issueLaunch(
                            root,
                            next,
                            command.component,
                            command.dpi,
                            request,
                            acceptedOrder
                        )
                        text(root, "ra_launchRequest", request)
                    }

                    "reset" -> {
                        val next = binding.state.reset()
                        binding.store.write(next)
                        binding.state = next
                        clearDraft(root)
                        publish(root, next, "")
                    }
                }
                refresh(root)
            }
        } catch (error: Throwable) {
            text(root, "ra_launchRequest", "")
            text(root, "ra_error", error.message ?: "Cannot save Activity selection")
            refresh(root)
        }
    }

    fun fail(root: Any, message: String) = locked(root) {
        number(root, "ra_ready", 0.0)
        text(root, "ra_launchRequest", "")
        text(root, "ra_error", message)
        refresh(root)
    }

    /** Retire a root (including temporary native removal), but retain its disk selection. */
    fun detachDeleted(root: Any) = locked(root) {
        retiredRoots[root] = true
        bindings.remove(root)?.let { it.store.retire(); it.close?.invoke() }
        number(root, "ra_ready", 0.0)
        number(root, "ra_selected", 0.0)
        text(root, "ra_instance", "")
        text(root, "ra_package", "")
        text(root, "ra_component", "")
        number(root, "ra_dpi", 0.0)
        text(root, "ra_launchRequest", "")
        text(root, "ra_action", "")
        clearDraft(root)
        refresh(root)
    }

    fun delete(file: File) {
        val path = file.canonicalPath
        // Never hold the store lock while acquiring a root render lock.
        val roots = synchronized(bindings) {
            bindings.entries.filter { it.value.path == path }.map { it.key }
        }
        roots.forEach(::detachDeleted)
        ActivityHostSelectionStore.delete(file)
    }

    private fun subscribe(root: Any, binding: Binding) {
        val weak = WeakReference(root)
        val ctx = context(root)
        var closed = false
        val replacing = binding.replacing
        lateinit var sweep: Runnable
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val current = weak.get()
                if (current == null || !binding.store.isActive()) {
                    binding.close?.invoke(); return
                }
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (!binding.state.entries.containsKey(pkg)) return
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(
                        Intent.EXTRA_REPLACING,
                        false
                    )
                ) {
                    replacing.add(pkg)
                    return
                }
                if (intent.action == Intent.ACTION_PACKAGE_ADDED || intent.action == Intent.ACTION_PACKAGE_REPLACED) replacing.remove(
                    pkg
                )
                else if (pkg in replacing) return
                locked(current) {
                    if (bindings[current] !== binding || !binding.store.isActive()) return@locked
                    try {
                        binding.store.active {
                            val next =
                                if (intent.action == Intent.ACTION_PACKAGE_REMOVED) binding.state.remove(
                                    pkg
                                )
                                else prune(ctx, binding.state, binding.replacing)
                            if (next != binding.state) {
                                binding.store.write(next)
                                binding.state = next
                                clearDraft(current)
                                publish(current, next, "Saved Activity is no longer available")
                            }
                        }
                    } catch (error: Throwable) {
                        fail(current, error.message ?: "Cannot update Activity selection")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(
            receiver,
            filter,
            Context.RECEIVER_NOT_EXPORTED
        )
        else @Suppress("DEPRECATION") ctx.registerReceiver(receiver, filter)
        sweep = Runnable {
            if (weak.get() == null || !binding.store.isActive()) binding.close?.invoke()
            else handler.postDelayed(sweep, 60_000L)
        }
        binding.close = {
            synchronized(receiver) {
                if (!closed) {
                    closed = true
                    handler.removeCallbacks(sweep)
                    runCatching { ctx.unregisterReceiver(receiver) }
                    binding.store.retire()
                }
            }
        }
        handler.postDelayed(sweep, 60_000L)
    }

    private fun prune(
        ctx: Context,
        state: ActivityHostSelectionState,
        replacing: Set<String> = emptySet()
    ): ActivityHostSelectionState {
        var next = state
        state.entries.forEach { (pkg, entry) ->
            if (pkg !in replacing && !valid(
                    ctx,
                    pkg,
                    entry
                )
            ) next = next.remove(pkg)
        }
        return next
    }

    private fun validPackage(pkg: String): Boolean =
        ActivityHostSpec.parseComponent("$pkg/.Activity")?.packageName == pkg

    private fun valid(ctx: Context, pkg: String, entry: ActivityHostSelectionState.Entry): Boolean {
        val parsed = ActivityHostSpec.parseComponent(entry.component) ?: return false
        if (parsed.packageName != pkg || !ActivityHostSpec.isValidDpi(entry.dpi)) return false
        val pm = ctx.packageManager
        val info = try {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(
                ComponentName(parsed.packageName, parsed.className),
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val appSetting = pm.getApplicationEnabledSetting(pkg)
        val componentSetting =
            pm.getComponentEnabledSetting(ComponentName(parsed.packageName, parsed.className))

        fun enabled(setting: Int, manifest: Boolean) =
            setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && manifest)
        return enabled(appSetting, info.applicationInfo.enabled) && enabled(
            componentSetting,
            info.enabled
        ) &&
                info.exported && info.permission.isNullOrEmpty() && info.targetActivity == null
    }

    private fun publish(root: Any, state: ActivityHostSelectionState, error: String) {
        val selected = state.activePackage?.let(state.entries::get)
        text(root, "ra_instance", state.instance)
        text(root, "ra_package", state.activePackage ?: "")
        text(root, "ra_component", selected?.component ?: "")
        number(root, "ra_dpi", (selected?.dpi ?: 0).toDouble())
        number(root, "ra_selected", if (selected == null) 0.0 else 1.0)
        text(root, "ra_error", error)
        text(root, "ra_launchRequest", "")
        number(root, "ra_ready", 1.0)
        refresh(root)
    }

    private fun clearDraft(root: Any) {
        text(root, "ra_savedComponent", "")
        text(root, "ra_savedActivityClass", "")
        text(root, "ra_loadedPackage", "")
        number(root, "ra_savedDpi", 0.0)
        number(root, "ra_savedFound", 0.0)
        number(root, "ra_loadRevision", 0.0)
    }

    private fun refresh(root: Any) {
        update.invoke(root); MamlElementHook.refreshInputs(root)
    }

    private fun context(root: Any): Context =
        (contextField.get(getContext.invoke(root)) as Context).let { it.applicationContext ?: it }

    private fun <T> locked(root: Any, block: () -> T): T =
        synchronized(requireNotNull(mutex.invoke(root)), block)

    private fun text(root: Any, name: String, value: String) {
        if (name == "ra_launchRequest" && value.isEmpty()) revokeLaunch(root)
        putText.invoke(variables.invoke(root), name, value)
    }

    private fun number(root: Any, name: String, value: Double) {
        putNumber.invoke(variables.invoke(root), name, value)
    }

    private fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            val owner = current
            runCatching { owner.getDeclaredField(name) }.getOrNull()
                ?.let { return it.apply { isAccessible = true } }
            current = owner.superclass
        }
        error("Missing host field ${type.name}.$name")
    }
}
