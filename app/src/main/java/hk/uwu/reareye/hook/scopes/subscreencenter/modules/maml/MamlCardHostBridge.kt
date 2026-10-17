package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import java.io.File
import java.lang.reflect.Field

/** ABI-only adapter for the host's own ConfigFile and Variables implementations. */
internal class MamlCardHostBridge(loader: ClassLoader) {
    val rootClass = loader.loadClass("com.miui.maml.ScreenElementRoot")
    val viewClass = loader.loadClass("com.miui.maml.component.MamlView")
    private val configClass = loader.loadClass("com.miui.maml.util.ConfigFile")
    private val activityCards by lazy { MamlActivityCardBridge(loader) }

    fun activityPicker(root: Any): Boolean =
        rawAttribute.invoke(root, "rearActivityPicker") == "true"

    fun activityAction(
        root: Any,
        file: File,
        acceptedOrder: Long = ActivityHostLaunchPermit.newBoundary()
    ) =
        activityCards.action(root, file, acceptedOrder)

    fun deleteActivity(file: File) = activityCards.delete(file)

    private data class Initialized(val path: String, val config: Any, val key: String)

    private val initialized =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Initialized>())

    fun nativeInitializing(root: Any) =
        synchronized(rootMutex(root)) { initialized.remove(root); Unit }

    private val buttonClass by lazy { loader.loadClass("com.miui.maml.elements.ButtonScreenElement") }
    val setLocalId = MamlHostBridge.method(
        loader.loadClass("com.miui.maml.widget.edit.WidgetEditSave"),
        "setLocalId", viewClass, String::class.java
    )
    val init = MamlHostBridge.method(rootClass, "init")
    val saveString =
        MamlHostBridge.method(rootClass, "saveVar", String::class.java, String::class.java)
    val saveNumber = MamlHostBridge.method(
        rootClass,
        "saveVar",
        String::class.java,
        Double::class.javaObjectType
    )
    private val viewRoot = field(viewClass, "mRoot")
    private val config = field(rootClass, "mConfig")
    private val configPath = field(rootClass, "mConfigPath")
    private val rawAttribute = MamlHostBridge.method(rootClass, "getRawAttr", String::class.java)
    private val getContext = MamlHostBridge.method(rootClass, "getContext")
    private val contextField = field(getContext.returnType, "mContext")
    private val getVariables = MamlHostBridge.method(rootClass, "getVariables")
    private val getNumber =
        MamlHostBridge.method(getVariables.returnType, "getDouble", String::class.java)
    private val getObject =
        MamlHostBridge.method(getVariables.returnType, "get", String::class.java)
    private val putNumber = MamlHostBridge.method(
        getVariables.returnType,
        "put",
        String::class.java,
        Double::class.javaPrimitiveType!!
    )
    private val putObject =
        MamlHostBridge.method(getVariables.returnType, "put", String::class.java, Any::class.java)
    private val setConfig = MamlHostBridge.method(rootClass, "setConfig", String::class.java)
    private val setSaveViaProvider = MamlHostBridge.method(
        rootClass,
        "setSaveConfigViaProvider",
        Boolean::class.javaPrimitiveType!!
    )
    private val loadConfig = MamlHostBridge.method(rootClass, "loadConfig")
    private val post = MamlHostBridge.method(rootClass, "postRunnable", Runnable::class.java)
    private val update = MamlHostBridge.method(rootClass, "requestUpdate")
    private val mutex = MamlHostBridge.method(rootClass, "getRenderMutex")
    private val constructor = configClass.getConstructor()
    private val load = MamlHostBridge.method(configClass, "load", String::class.java)
    private val getVariable = MamlHostBridge.method(configClass, "getVariable", String::class.java)
    private val putString =
        MamlHostBridge.method(configClass, "putString", String::class.java, String::class.java)
    private val putNumeric = MamlHostBridge.method(
        configClass,
        "putNumber",
        String::class.java,
        Double::class.javaPrimitiveType!!
    )
    private val save =
        MamlHostBridge.method(configClass, "save", String::class.java, Context::class.java)

    fun root(view: Any): Any? = viewRoot.get(view)
    fun enabled(root: Any): Boolean {
        val widget = rawAttribute.invoke(root, "rearWidgetPicker") == "true"
        val activity = activityPicker(root)
        // Conflicting root capabilities must never share a Widget config namespace.
        return widget.xor(activity)
    }
    fun pickerVisible(root: Any): Boolean = enabled(root) && synchronized(rootMutex(root)) {
        val variables = getVariables.invoke(root)
        val prefix = if (activityPicker(root)) "ra" else "rw"
        (getNumber.invoke(variables, "${prefix}_ready") as Number).toDouble() == 1.0 &&
                (getNumber.invoke(variables, "${prefix}_selected") as Number).toDouble() == 0.0
    }

    /** Preserve native availability/lock gates even when Android RemoteViews owns the gesture. */
    fun appWidgetTouchable(root: Any): Boolean = runCatching {
        if (!enabled(root) || activityPicker(root)) return@runCatching false
        synchronized(rootMutex(root)) {
            if (MamlHostBridge.method(rootClass, "isFinished").invoke(root) == true ||
                !field(rootClass, "mLoaded").getBoolean(root) ||
                !field(rootClass, "mTouchable").getBoolean(root)
            ) return@synchronized false
            val variables = getVariables.invoke(root)
            fun value(name: String) = (getNumber.invoke(variables, name) as Number).toDouble()
            value("rw_ready") == 1.0 && value("rw_selected") == 1.0 && value("displayReady") == 1.0
        }
    }.getOrDefault(false)

    /** MamlView supplies physical local coordinates; native root.onTouch uses descale(double). */
    fun pickerHit(root: Any, x: Float, y: Float): Boolean = runCatching {
        if (!x.isFinite() || !y.isFinite() || !enabled(root)) return@runCatching false
        synchronized(rootMutex(root)) {
            if (MamlHostBridge.method(rootClass, "isFinished").invoke(root) == true ||
                !field(rootClass, "mLoaded").getBoolean(root) ||
                !field(rootClass, "mTouchable").getBoolean(root)
            ) return@synchronized false
            val variables = getVariables.invoke(root)
            fun value(name: String) = (getNumber.invoke(variables, name) as Number).toDouble()
            val activity = activityPicker(root)
            val prefix = if (activity) "ra" else "rw"
            val selected = value("${prefix}_selected")
            // Restoring a selection is not a launch. Match the template's live-request
            // state so the saved selection cannot leave its visible picker untouchable.
            val menuOpen = selected == 0.0 || (activity && selected == 1.0 &&
                    (getObject.invoke(variables, "ra_launchRequest") as? String).isNullOrEmpty())
            if (value("${prefix}_ready") != 1.0 || !menuOpen ||
                value("displayReady") != 1.0
            ) return@synchronized false
            if (!activity) {
                val gesture = MamlHostBridge.method(rootClass, "findElement", String::class.java)
                    .invoke(root, "gesture") ?: return@synchronized false
                if (!buttonClass.isInstance(gesture) ||
                    MamlHostBridge.method(gesture.javaClass, "isVisible").invoke(gesture) != true ||
                    !field(gesture.javaClass, "mTouchable").getBoolean(gesture)
                ) return@synchronized false
            }
            val scale =
                (MamlHostBridge.method(rootClass, "getScale").invoke(root) as Number).toDouble()
            if (!scale.isFinite() || scale <= 0.0) return@synchronized false
            val left = value(if (activity) "pickerX" else "safeX")
            val top = value(if (activity) "pickerY" else "safeY")
            val width = value(if (activity) "pickerW" else "safeW")
            val height = value(if (activity) "pickerH" else "safeH")
            val right = left + width
            val bottom = top + height
            if (!listOf(left, top, width, height, right, bottom).all { it.isFinite() } ||
                width <= 0.0 || height <= 0.0) return@synchronized false
            val localX = x.toDouble() / scale
            val localY = y.toDouble() / scale
            localX >= left && localX < right && localY >= top && localY < bottom
        }
    }.getOrDefault(false)

    fun context(root: Any): Context =
        (contextField.get(getContext.invoke(root)) as Context).let { it.applicationContext ?: it }

    fun enqueue(root: Any, block: () -> Unit) {
        post.invoke(root, Runnable(block))
        update.invoke(root)
    }

    fun <T> withRootLock(root: Any, block: () -> T): T = synchronized(rootMutex(root), block)

    /** Both clocks are uptime milliseconds; native animation timestamps use another axis. */
    fun publishFrameTime(root: Any, uptime: Long) = synchronized(rootMutex(root)) {
        number(
            root,
            if (activityPicker(root)) "ra_frame_time" else "rw_frame_time",
            uptime.toDouble()
        )
    }

    fun publishTouchTime(root: Any, eventTime: Long) = synchronized(rootMutex(root)) {
        number(
            root,
            if (activityPicker(root)) "ra_touch_time" else "rw_touch_time",
            eventTime.toDouble()
        )
    }

    /** Compatibility entry point: queued restoration must not hide an already chosen widget. */
    fun loading(root: Any) = synchronized(rootMutex(root)) { text(root, "rw_error", "") }

    /** Bind before native root.init so its own loadConfig restores variables before child init. */
    fun prepare(root: Any, file: File) = synchronized(rootMutex(root)) {
        if (activityPicker(root)) return@synchronized activityCards.prepare(root, file)
        if (file.isFile) read(file)
        if (configPath.get(root) != file.absolutePath) config.set(root, null)
        setSaveViaProvider.invoke(root, false)
        setConfig.invoke(root, file.absolutePath)
    }

    /** Restore atomically; native root re-init may have replaced ConfigFile, which must be honored. */
    fun initialize(root: Any, file: File): String = synchronized(rootMutex(root)) {
        if (activityPicker(root)) return@synchronized activityCards.initialize(root, file)
        val current = config.get(root)
        initialized[root]?.let { previous ->
            if (previous.path == file.absolutePath && previous.config === current &&
                configPath.get(root) == file.absolutePath
            ) return@synchronized previous.key
        }
        // Native loadConfig tolerates malformed XML; never replace a corrupt existing binding.
        if (file.isFile) read(file)
        if (current == null || configPath.get(root) != file.absolutePath) {
            prepare(root, file)
            loadConfig.invoke(root)
        }
        val native = requireNotNull(config.get(root)) { "Host did not initialize MAML ConfigFile" }
        val stored = getVariable.invoke(native, "rw_instance") as? String
        val selectedText = getVariable.invoke(native, "rw_selected") as? String
        val selected = if (selectedText == null) 0.0 else selectedText.toDoubleOrNull()
        check(selected == 0.0 || selected == 1.0) { "Invalid saved selection state" }
        check(stored == null || MamlCardScopeFiles.validInstance(stored)) { "Invalid per-card instance token" }
        check(selected != 1.0 || stored != null) { "Saved selection has no card instance token" }
        check(
            selected != 1.0 || !(getVariable.invoke(
                native,
                "rw_provider"
            ) as? String).isNullOrBlank()
        ) {
            "Saved selection has no widget Provider"
        }
        val key = stored ?: MamlCardScopeFiles.newInstance()
        if (stored == null) {
            putString.invoke(native, "rw_instance", key)
            putNumeric.invoke(native, "rw_selected", 0.0)
            putString.invoke(native, "rw_provider", "")
            putString.invoke(native, "rw_package", "")
            check(
                save.invoke(
                    native,
                    file.absolutePath,
                    context(root)
                ) == true
            ) { "Cannot persist per-card MAML config" }
        }
        // Publish the complete final state under the render mutex, never a transient empty selection.
        text(root, "rw_instance", key)
        text(root, "rw_provider", getVariable.invoke(native, "rw_provider") as? String ?: "")
        text(root, "rw_package", getVariable.invoke(native, "rw_package") as? String ?: "")
        number(root, "rw_selected", selected ?: 0.0)
        text(root, "rw_error", "")
        number(root, "rw_ready", 1.0)
        update.invoke(root)
        initialized[root] = Initialized(file.absolutePath, native, key)
        // Bootstrap immutable inputs even when the hidden native element never ticks.
        MamlElementHook.refreshInputs(root)
        key
    }

    fun commit(root: Any, file: File) = synchronized(rootMutex(root)) {
        if (activityPicker(root)) return@synchronized activityCards.commit(root, file)
        val native = requireNotNull(config.get(root)) { "Missing per-card MAML config" }
        check(configPath.get(root) == file.absolutePath) { "Card config path changed" }
        check(
            save.invoke(
                native,
                file.absolutePath,
                context(root)
            ) == true
        ) { "Cannot save card selection" }
    }

    fun fail(root: Any, message: String) = synchronized(rootMutex(root)) {
        if (activityPicker(root)) return@synchronized activityCards.fail(root, message)
        number(root, "rw_ready", 0.0)
        text(root, "rw_error", message)
        update.invoke(root)
    }

    /** Clearing mConfig prevents a late pause/finish from recreating a deleted file. */
    fun detachDeleted(root: Any) = synchronized(rootMutex(root)) {
        if (activityPicker(root)) return@synchronized activityCards.detachDeleted(root)
        initialized.remove(root)
        number(root, "rw_ready", 0.0)
        number(root, "rw_selected", 0.0)
        text(root, "rw_provider", "")
        config.set(root, null)
        configPath.set(root, null)
        update.invoke(root)
    }

    fun savedKey(file: File): String? {
        if (!file.isFile) return null
        val value = getVariable.invoke(read(file), "rw_instance") as? String
        check(value == null || MamlCardScopeFiles.validInstance(value)) { "Invalid per-card instance token" }
        return value
    }

    private fun read(file: File): Any = constructor.newInstance().also {
        check(load.invoke(it, file.absolutePath) == true) { "Cannot load saved MAML card config" }
    }

    private fun rootMutex(root: Any): Any =
        requireNotNull(mutex.invoke(root)) { "Host root has no render mutex" }

    private fun number(root: Any, name: String, value: Double) {
        putNumber.invoke(getVariables.invoke(root), name, value)
    }

    private fun text(root: Any, name: String, value: String) {
        putObject.invoke(getVariables.invoke(root), name, value)
    }

    private fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            val owner = current
            runCatching { owner.getDeclaredField(name) }.getOrNull()
                ?.let { return it.apply { isAccessible = true } }
            current = owner.superclass
        }
        error("Required host field missing: ${type.name}.$name")
    }
}
