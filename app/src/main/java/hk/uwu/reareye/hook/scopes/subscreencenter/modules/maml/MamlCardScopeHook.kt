package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.NotificationRouteBridgeContract
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.roxyhook.HookHandle
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Opt-in for independent picker cards. Ordinary MAML themes and fixed widgets are untouched. */
@OptIn(DexKitExperimentalApi::class)
class MamlCardScopeHook : RoxyHooker() {
    private class Scope(val localId: String, val storageIdentity: String, val file: File) {
        @Volatile
        var deleted = false

        @Volatile
        var key: String? = null
        var generation = 0
        var completed = -1
        var queued = false
    }

    @Volatile
    private var hasRoots: () -> Boolean = { false }
    override fun onHotReloadPreflight(): Boolean = !hasRoots()

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val handles = mutableListOf<HookHandle>()
            val scopes = Collections.synchronizedMap(WeakHashMap<Any, Scope>())
            val main = Handler(Looper.getMainLooper())
            hasRoots = { synchronized(scopes) { scopes.isNotEmpty() } }
            try {
                val bridge = MamlCardHostBridge(appClassLoader)
                val points = createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = resolveHookPackageVersionCode(
                        hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir,
                    ),
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                ).use { resolveCardPoints(it, appClassLoader, bridge.viewClass, bridge.rootClass) }
                val remove = points.remove
                val createView = points.create
                val localId = points.localId
                val cardView = points.view
                val moduleBundleFields = mutableMapOf<Class<*>, List<java.lang.reflect.Field>>()
                fun moduleIdentity(card: Any): String? {
                    val fields = synchronized(moduleBundleFields) {
                        moduleBundleFields.getOrPut(card.javaClass) {
                            generateSequence(card.javaClass as Class<*>?) { it.superclass }
                                .flatMap { it.declaredFields.asSequence() }
                                .filter { !Modifier.isStatic(it.modifiers) && it.type == android.os.Bundle::class.java }
                                .onEach { it.isAccessible = true }.toList()
                        }
                    }
                    val identities = fields.mapNotNull { field ->
                        val extras = field.get(card) as? android.os.Bundle ?: return@mapNotNull null
                        if (extras.getString("__xposed_origin__") != "hk.uwu.reareye") return@mapNotNull null
                        requireNotNull(
                            MamlCardScopeFiles.moduleIdentity(
                                extras.getString("__xposed_origin__"),
                                extras.getString("package_name"),
                                extras.getString("business"),
                                extras.getString("__rear_card_id__"),
                            )
                        ) { "Module card is missing a stable definition identity" }
                    }.distinct()
                    check(identities.size <= 1) { "Ambiguous module card definition identity" }
                    return identities.singleOrNull()
                }

                fun storageIdentity(card: Any): String? {
                    val nativeId = localId.getInt(card).toString()
                    val type = points.type?.getInt(card)
                    val appCard = points.appCreate?.declaringClass?.isInstance(card) == true
                    val resId = if (type == 7 && appCard) {
                        runCatching { (points.extra?.get(card) as? android.os.Bundle)?.getString("resId") }
                            .onFailure {
                                YLog.warn(
                                    "Native application-card Bundle identity unavailable",
                                    it
                                )
                            }.getOrNull()
                    } else null
                    // Notification IDs are allocated anew after restart. Use the verified
                    // module definition UUID, not the current notification/localId counter.
                    if (!appCard && type != 7) moduleIdentity(card)?.let { return it }
                    // Unresolved subclasses must not silently share native id 0 with AppCardWidget.
                    return MamlCardScopeFiles.storageIdentity(
                        nativeId, type, appCard, resId,
                        nativeClass = card.javaClass == createView.declaringClass
                    )
                }

                val owners = runtime.manage(
                    MamlCardOwnerTracker(
                        { card -> localId.getInt(card).toString() },
                        { card -> cardView.get(card) },
                        ::storageIdentity,
                    )
                )

                fun files(context: Context) =
                    MamlCardScopeFiles(File(context.filesDir, "reareye-maml-cards-v1"))

                runtime.manage(MamlActivityCardInvalidation.observe { identity ->
                    val targets = synchronized(scopes) {
                        scopes.entries.filter {
                            it.value.storageIdentity == identity && bridge.activityPicker(it.key)
                        }.map { it.key to it.value }
                    }
                    targets.forEach { (_, scope) -> scope.deleted = true }
                    targets.forEach { (root, _) -> bridge.detachDeleted(root) }
                })

                fun verifyActivityDefinition(root: Any, identity: String) {
                    if (!bridge.activityPicker(root) || !identity.startsWith("module:")) return
                    val parts = identity.split(':')
                    check(parts.size == 4) { "Invalid module card identity" }
                    val business = parts[2]
                    val cardId = parts[3]
                    val pending =
                        hookPrefs.getStringSet(NotificationRouteBridgeContract.PENDING_DELETIONS_PREF)
                            .any { NotificationRouteBridgeContract.decodeDeletionRecord(it) == (business to cardId) }
                    val exists = MamlActivityCardInvalidation.definitionExists(
                        hookPrefs.getString(ConfigKeys.REAR_WIDGET_CARD_DATA, "[]"),
                        business,
                        cardId,
                    )
                    if (pending || exists == false) {
                        check(
                            MamlActivityCardInvalidation.deleteDefinition(
                                bridge.context(root),
                                business,
                                cardId
                            )
                        ) {
                            "Cannot erase deleted Activity card"
                        }
                        error("Activity card definition was deleted")
                    }
                    check(exists == true) { "Card definition could not be verified" }
                }

                fun bind(view: Any, root: Any, declaredId: String? = null): Scope {
                    val id =
                        requireNotNull(owners.nativeLocalId(view)) { "No live independent card instance" }
                    check(declaredId == null || declaredId == id) { "Native card identity changed" }
                    val identity =
                        requireNotNull(owners.storageIdentity(view)) { "Unresolved AppCardWidget identity" }
                    verifyActivityDefinition(root, identity)
                    return synchronized(scopes) {
                        scopes[root]?.also {
                            check(it.localId == id && it.storageIdentity == identity && !it.deleted) { "Card identity changed" }
                        } ?: Scope(
                            id, identity, files(bridge.context(root)).prepare(
                                identity,
                                legacyIdentity = id.takeIf { identity.startsWith("module:") },
                            )
                        ).also { scopes[root] = it }
                    }
                }

                fun initialize(root: Any, scope: Scope, immediate: Boolean = false) {
                    val generation = synchronized(scope) {
                        if (scope.deleted || scope.completed == scope.generation || (scope.queued && !immediate)) return
                        scope.queued = true
                        scope.generation
                    }
                    val weakRoot = WeakReference(root)
                    val restore = restore@{
                        val owner = weakRoot.get() ?: return@restore
                        try {
                            bridge.withRootLock(owner) {
                                if (scope.deleted || scopes[owner] !== scope || synchronized(scope) {
                                        scope.generation != generation || scope.completed == generation
                                    }) return@withRootLock
                                verifyActivityDefinition(owner, scope.storageIdentity)
                                val firstReady = scope.key == null
                                scope.key = bridge.initialize(owner, scope.file)
                                synchronized(scope) { scope.completed = generation }
                                if (firstReady) {
                                    val domain =
                                        if (scope.storageIdentity.startsWith("app:")) "application" else "persistent"
                                    YLog.info("Independent MAML card config ready ($domain)")
                                }
                            }
                        } catch (error: Exception) {
                            bridge.fail(owner, error.message ?: "Card config unavailable")
                            YLog.warn(
                                "Could not load independent MAML card ${scope.storageIdentity}",
                                error
                            )
                        } finally {
                            synchronized(scope) {
                                if (scope.generation == generation) scope.queued = false
                            }
                        }
                    }
                    if (immediate) restore() else try {
                        bridge.enqueue(root, restore)
                    } catch (error: Exception) {
                        synchronized(scope) {
                            if (scope.generation == generation) scope.queued = false
                        }
                        throw error
                    }
                }

                val touchGuard = runtime.manage(MamlPickerTouchPolicy { view, x, y ->
                    val root = bridge.root(view)
                    root != null && scopes[root]?.let {
                        !it.deleted && it.key != null && owners.owns(view, it.localId) &&
                                owners.storageIdentity(view) == it.storageIdentity
                    } == true &&
                            bridge.pickerHit(root, x, y)
                })
                val gestureHandles = mutableListOf<HookHandle>()
                points.gestures?.let { gestures ->
                    try {
                        gestures.dispatchers.forEach { dispatch ->
                            gestureHandles += dispatch.hook {
                                before {
                                    val event = args[0] as? MotionEvent ?: return@before
                                    val decor =
                                        (instance as? Activity)?.window?.decorView ?: return@before
                                    touchGuard.beginDispatch(decor, event)
                                }
                                after {
                                    (args[0] as? MotionEvent)?.let(touchGuard::endDispatch)
                                }
                            }
                        }
                        gestureHandles += gestures.manager.hook {
                            before {
                                val manager = instance ?: return@before
                                val event = args[0] as? MotionEvent ?: return@before
                                // Returning false preserves the event; true would CANCEL children.
                                if (touchGuard.beforeManager(manager, event)) result = false
                            }
                        }
                        handles += gestureHandles
                        YLog.info("MAML picker gesture bridge installed for ${gestures.dispatchers.size} Activity callers")
                    } catch (error: Throwable) {
                        gestureHandles.asReversed().forEach { runCatching { it.close() } }
                        touchGuard.close()
                        YLog.warn(
                            "MAML picker gesture hooks unavailable; independent configuration remains enabled",
                            error
                        )
                    }
                }
                // Independently install the verified root frame clock; failure must never
                // disable per-card configuration or real-widget restoration.
                runCatching {
                    MamlHostBridge.method(
                        bridge.rootClass,
                        "doTick",
                        Long::class.javaPrimitiveType!!
                    ).hook {
                        before {
                            val root = instance ?: return@before
                            if (scopes[root]?.deleted == false && bridge.pickerVisible(root)) {
                                bridge.publishFrameTime(root, android.os.SystemClock.uptimeMillis())
                            }
                        }
                    }
                }.onSuccess(handles::add).onFailure {
                    YLog.warn("MAML picker frame clock unavailable", it)
                }
                handles += MamlHostBridge.method(
                    bridge.viewClass,
                    "onTouchEvent",
                    MotionEvent::class.java
                ).hook {
                    before {
                        val event = args[0] as? MotionEvent ?: return@before
                        val root = instance?.let(bridge::root) ?: return@before
                        if (scopes[root]?.deleted == false && bridge.pickerVisible(root)) {
                            bridge.publishTouchTime(root, event.eventTime)
                        }
                    }
                    after {
                        val view = instance as? View ?: return@after
                        if (hasThrowable) {
                            touchGuard.release(view); return@after
                        }
                        val event = args[0] as? MotionEvent ?: return@after
                        if (touchGuard.afterTouch(view, event, result == true)) result = true
                    }
                }
                handles += MamlHostBridge.method(bridge.viewClass, "onDetachedFromWindow").hook {
                    before { (instance as? View)?.let(touchGuard::release) }
                }
                // Track actual card view creation, never a localized-title cache or preview localId.
                listOfNotNull(createView, points.appCreate).distinct().forEach { creation ->
                    handles += creation.hook {
                        after {
                            if (hasThrowable) return@after
                            val card = instance ?: return@after
                            val view = result ?: return@after
                            if (bridge.viewClass.isInstance(view)) {
                                runCatching { owners.remember(card, view) }
                                    .onFailure {
                                        YLog.warn(
                                            "Could not record native MAML card ownership",
                                            it
                                        )
                                    }
                                (view as? View)?.let(touchGuard::remember)
                            }
                        }
                    }
                }
                handles += points.nativeInit.hook {
                    before {
                        val view = instance ?: return@before
                        val root = args[1] ?: return@before
                        if (!bridge.enabled(root)) return@before
                        try {
                            bridge.prepare(root, bind(view, root).file)
                        } catch (error: Exception) {
                            bridge.fail(root, error.message ?: "No independent card identity")
                            YLog.warn(
                                "Cannot prepare independent MAML card before native init",
                                error
                            )
                        }
                    }
                }
                handles += bridge.setLocalId.hook {
                    after {
                        if (hasThrowable) return@after
                        val view = args[0] ?: return@after
                        val root = bridge.root(view) ?: return@after
                        if (!bridge.enabled(root)) return@after
                        try {
                            val id = args[1] as? String ?: error("Missing host card localId")
                            check(
                                id.toIntOrNull()?.toString() == id
                            ) { "Invalid host card localId" }
                            initialize(root, bind(view, root, id))
                        } catch (error: Exception) {
                            bridge.fail(root, error.message ?: "No independent card identity")
                            YLog.warn("Independent MAML card identity unavailable", error)
                        }
                    }
                }
                handles += bridge.init.hook {
                    before {
                        val root = instance ?: return@before
                        val scope = scopes[root] ?: return@before
                        if (scope.deleted) return@before
                        synchronized(scope) { scope.generation++; scope.queued = false }
                        try {
                            bridge.nativeInitializing(root)
                            bridge.prepare(root, scope.file)
                        } catch (error: Exception) {
                            bridge.fail(root, error.message ?: "Cannot prepare independent config")
                            YLog.warn("Independent MAML native re-init preparation failed", error)
                        }
                    }
                    after {
                        // Restore before native init returns, not after the first renderer frame.
                        if (!hasThrowable) instance?.let { root ->
                            scopes[root]?.let {
                                initialize(
                                    root,
                                    it,
                                    immediate = true
                                )
                            }
                        }
                    }
                }
                listOf(bridge.saveString, bridge.saveNumber).forEach { method ->
                    handles += method.hook {
                        before {
                            val root = instance ?: return@before
                            if (scopes[root]?.deleted == true ||
                                (args[0] == "ra_action" && bridge.activityPicker(root))
                            ) result = null
                        }
                        after {
                            if (hasThrowable) return@after
                            val root = instance ?: return@after
                            val activity = bridge.activityPicker(root)
                            val action = args[0] == "ra_action" && activity
                            if (!action && (activity || args[0] != "rw_selected")) return@after
                            val scope = scopes[root] ?: return@after
                            if (scope.deleted || scope.key == null) return@after
                            // Reserve before validation/locking/persistence can block across hide or pause.
                            val acceptedOrder =
                                if (action) ActivityHostLaunchPermit.newBoundary() else 0L
                            try {
                                bridge.withRootLock(root) {
                                    if (!scope.deleted) {
                                        if (action) {
                                            verifyActivityDefinition(root, scope.storageIdentity)
                                            bridge.activityAction(root, scope.file, acceptedOrder)
                                        } else bridge.commit(root, scope.file)
                                    }
                                }
                            } catch (error: Exception) {
                                bridge.fail(root, error.message ?: "Cannot save card selection")
                                YLog.warn("Independent MAML selection save failed", error)
                            }
                        }
                    }
                }
                handles += remove.hook {
                    before {
                        val card = instance ?: return@before
                        val identity = storageIdentity(card) ?: return@before
                        val transientOwner = MamlCardScopeFiles.preservesBinding(identity)
                        val ownedRoots = if (transientOwner) owners.views(card)
                            .mapNotNull(bridge::root) else emptyList()
                        owners.remove(card)
                        val targets = synchronized(scopes) {
                            scopes.entries.filter {
                                it.value.storageIdentity == identity &&
                                        (!transientOwner || ownedRoots.any { root -> root === it.key })
                            }.map { it.key to it.value }
                        }
                        MamlCardScopeFiles.onNativeRemoval(identity, retireRoots = {
                            // Freeze this transient generation, not another reopened view of the same resource.
                            targets.forEach { (_, scope) -> scope.deleted = true }
                            targets.forEach { (root, _) -> bridge.detachDeleted(root) }
                        }, deleteBinding = delete@{
                            // Application dismiss/registry cleanup preserves both file and widget binding.
                            // Ordinary persisted cards keep the established registry-removal boundary.
                            val context = (cardView.get(card) as? View)?.context?.applicationContext
                                ?: hookAppContext?.applicationContext ?: return@delete
                            val file = files(context).config(identity)
                            val keys = targets.mapNotNull { it.second.key }.toMutableSet()
                            runCatching { bridge.savedKey(file) }.onSuccess { it?.let(keys::add) }
                                .onFailure { YLog.warn("Could not read removed card binding", it) }
                            val clean = {
                                runCatching { bridge.deleteActivity(file) }
                                    .onFailure {
                                        YLog.warn(
                                            "Could not erase removed Activity card configuration",
                                            it
                                        )
                                    }
                                keys.filterNot { key ->
                                    targets.any { (root, scope) ->
                                        scope.key == key && bridge.activityPicker(root)
                                    }
                                }.forEach { key ->
                                    runCatching {
                                        check(
                                            AppWidgetPlatform.shared(context).sessions.deleteCardBinding(
                                                key
                                            )
                                        )
                                    }
                                        .onFailure {
                                            YLog.warn(
                                                "Could not release removed card widget",
                                                it
                                            )
                                        }
                                }
                                runCatching { check(!file.exists() || file.delete()) { "Cannot delete per-card MAML config" } }
                                    .onFailure {
                                        YLog.warn(
                                            "Could not delete removed MAML card config",
                                            it
                                        )
                                    }
                            }
                            if (Looper.myLooper() == Looper.getMainLooper()) clean() else main.post { clean() }
                        })
                    }
                }
                runtime.onClose {
                    val roots = synchronized(scopes) { scopes.keys.toList() }
                    roots.filter(bridge::activityPicker)
                        .forEach { runCatching { bridge.detachDeleted(it) } }
                    scopes.clear()
                }
                YLog.info("Independent MAML card config bridge installed")
            } catch (error: Throwable) {
                handles.asReversed().forEach { runCatching { it.close() } }
                YLog.warn("Independent MAML picker cards disabled: incompatible host ABI", error)
            }
        }
    }
}

/** Resolve obfuscated host members by behaviour; never fall back to a particular APK's names. */
@OptIn(DexKitExperimentalApi::class)
private fun resolveCardPoints(
    cache: DexKitCacheBridge.RecyclableBridge,
    loader: ClassLoader,
    viewClass: Class<*>,
    rootClass: Class<*>,
): CardPoints {
    val create = requireNotNull(cache.getMethodDirectOrNull("SSC_MAML_CARD_CREATE_V1") {
        findMethod {
            matcher {
                paramTypes("android.content.Context")
                returnType = "android.view.View"
                usingStrings("MamlWidget", "onCreate path = ")
            }
        }.singleOrNull()
    }) { "DexKit: ambiguous or missing MAML card creation" }.getMethodInstance(loader)
    val cardClass = create.declaringClass
    val remove = requireNotNull(cache.getMethodDirectOrNull("SSC_MAML_CARD_REMOVE_V1") {
        findMethod {
            matcher {
                declaredClass = cardClass.name
                paramCount(0)
                returnType = "void"
                usingStrings("MamlWidget", "Clear cache for ", "sp_")
            }
        }.singleOrNull()
    }) { "DexKit: ambiguous or missing MAML card removal" }.getMethodInstance(loader)
    val localId = requireNotNull(cache.getFieldDirectOrNull("SSC_MAML_CARD_LOCAL_ID_V1") {
        findField {
            matcher {
                type = "int"
                readMethods {
                    add {
                        declaredClass = cardClass.name
                        name = remove.name
                        paramCount(0)
                        returnType = "void"
                    }
                }
            }
        }.singleOrNull()
    }) { "DexKit: ambiguous or missing MAML card localId" }.getFieldInstance(loader)
    val view = requireNotNull(cache.getFieldDirectOrNull("SSC_MAML_CARD_VIEW_V1") {
        findField {
            matcher {
                declaredClass = cardClass.name
                readMethods {
                    add {
                        declaredClass = cardClass.name
                        name = create.name
                        paramTypes("android.content.Context")
                        returnType = "android.view.View"
                    }
                }
                writeMethods {
                    add {
                        declaredClass = cardClass.name
                        name = create.name
                        paramTypes("android.content.Context")
                        returnType = "android.view.View"
                    }
                }
            }
        }.singleOrNull()
    }) { "DexKit: ambiguous or missing MAML card view" }.getFieldInstance(loader)
    check(!Modifier.isStatic(create.modifiers) && !Modifier.isStatic(remove.modifiers))
    check(create.parameterTypes.contentEquals(arrayOf(Context::class.java)))
    check(create.returnType == View::class.java)
    check(remove.declaringClass == cardClass && remove.parameterCount == 0 && remove.returnType == Void.TYPE)
    check(localId.declaringClass.isAssignableFrom(cardClass) && localId.type == Int::class.javaPrimitiveType)
    check(!Modifier.isStatic(localId.modifiers) && !Modifier.isStatic(view.modifiers))
    check(view.declaringClass == cardClass && viewClass.isAssignableFrom(view.type))
    val gestures = resolveMamlGesturePoints(
        findManager = {
            cache.getMethodDirectOrNull("SSC_MAML_PICKER_GESTURE_V1") {
                findMethod {
                    matcher {
                        paramTypes("android.view.MotionEvent")
                        returnType = "boolean"
                        usingStrings("GestureManager", "onTouchEvent: matchedGesture = ")
                    }
                }.singleOrNull()
            }?.getMethodInstance(loader)
        },
        findDispatchers = { gesture ->
            // Main and guide Activities both invoke this manager. Cache the whole candidate list,
            // then validate Activity ownership; a valid multi-caller result is not ambiguous.
            cache.getMethodsDirectOrEmpty("SSC_MAML_PICKER_DISPATCHERS_V2") {
                findMethod {
                    matcher {
                        name = "dispatchTouchEvent"
                        paramTypes("android.view.MotionEvent")
                        returnType = "boolean"
                        invokeMethods {
                            add {
                                declaredClass = gesture.declaringClass.name
                                name = gesture.name
                                paramTypes("android.view.MotionEvent")
                                returnType = "boolean"
                            }
                        }
                    }
                }
            }.map { it.getMethodInstance(loader) }
        },
        onFailure = { error ->
            YLog.warn(
                "MAML picker gesture ABI unavailable; independent configuration remains enabled",
                error
            )
        },
    )
    val nativeInit = requireNotNull(cache.getMethodDirectOrNull("SSC_MAML_NATIVE_INIT_V1") {
        findMethod {
            matcher {
                declaredClass = viewClass.name
                paramTypes("android.content.Context", rootClass.name)
                returnType = "void"
                usingStrings("MamlView#initMamlview")
            }
        }.singleOrNull()
    }) { "DexKit: ambiguous or missing native MamlView initialization" }.getMethodInstance(loader)
    check(
        !Modifier.isStatic(nativeInit.modifiers) && nativeInit.declaringClass == viewClass &&
                nativeInit.parameterTypes.contentEquals(
                    arrayOf(
                        Context::class.java,
                        rootClass
                    )
                ) && nativeInit.returnType == Void.TYPE
    )
    // q2.v's only public final int is mType, also read by its native toString.
    // Resolve optional application-domain points independently: failure must not disable ordinary cards.
    val type = runCatching {
        cache.getFieldDirectOrNull("SSC_MAML_CARD_TYPE_V1") {
            findField {
                matcher {
                    declaredClass = localId.declaringClass.name
                    type = "int"
                    modifiers = Modifier.PUBLIC or Modifier.FINAL
                    readMethods {
                        add {
                            name = "toString"; usingStrings("SubScreenWidget{mType=")
                        }
                    }
                }
            }.singleOrNull()
        }?.getFieldInstance(loader)?.also {
            check(
                it.declaringClass == localId.declaringClass && !Modifier.isStatic(it.modifiers) &&
                        Modifier.isFinal(it.modifiers) && it.type == Int::class.javaPrimitiveType
            )
        }
    }.onFailure {
        YLog.warn(
            "DexKit: MAML type identity unresolved; application cards disabled",
            it
        )
    }.getOrNull()
    val appCreate = runCatching {
        cache.getMethodDirectOrNull("SSC_MAML_APP_CARD_CREATE_V1") {
            findMethod {
                matcher {
                    paramTypes("android.content.Context")
                    returnType = "android.view.View"
                    usingStrings("AppCardWidget", "onCreate: ")
                }
            }.singleOrNull()
        }?.getMethodInstance(loader)?.also {
            check(
                it.declaringClass != cardClass && cardClass.isAssignableFrom(it.declaringClass) &&
                        !Modifier.isStatic(it.modifiers)
            )
        }
    }.onFailure { YLog.warn("DexKit: AppCardWidget identity unresolved", it) }.getOrNull()
    val extra = runCatching {
        cache.getFieldDirectOrNull("SSC_MAML_CARD_EXTRA_V1") {
            findField {
                matcher {
                    declaredClass = localId.declaringClass.name
                    this.type = "android.os.Bundle"
                    readMethods {
                        add {
                            declaredClass = localId.declaringClass.name
                            paramCount(0)
                            returnType = "java.lang.String"
                            usingStrings("resName", "unknown")
                        }
                    }
                }
            }.singleOrNull()
        }?.getFieldInstance(loader)?.also {
            check(
                !Modifier.isStatic(it.modifiers) && it.type == android.os.Bundle::class.java &&
                        it.declaringClass == localId.declaringClass
            )
        }
    }.onFailure { YLog.warn("DexKit: AppCardWidget Bundle unresolved", it) }.getOrNull()
    if (type == null || appCreate == null || extra == null) {
        YLog.warn("DexKit: application-card identity incomplete/ambiguous; fail closed for application cards")
    }
    listOfNotNull(create, remove, localId, view, nativeInit, type, appCreate, extra)
        .forEach { it.isAccessible = true }
    return CardPoints(create, remove, localId, view, gestures, nativeInit, type, appCreate, extra)
}

private data class CardPoints(
    val create: java.lang.reflect.Method,
    val remove: java.lang.reflect.Method,
    val localId: java.lang.reflect.Field,
    val view: java.lang.reflect.Field,
    val gestures: MamlGesturePoints?,
    val nativeInit: java.lang.reflect.Method,
    val type: java.lang.reflect.Field?,
    val appCreate: java.lang.reflect.Method?,
    val extra: java.lang.reflect.Field?,
)

/** Optional gesture ABI: failure must never disable per-card configuration restoration. */
internal data class MamlGesturePoints(
    val manager: java.lang.reflect.Method,
    val dispatchers: List<java.lang.reflect.Method>,
)

internal fun resolveMamlGesturePoints(
    findManager: () -> java.lang.reflect.Method?,
    findDispatchers: (java.lang.reflect.Method) -> List<java.lang.reflect.Method>,
    onFailure: (Throwable) -> Unit,
): MamlGesturePoints? = runCatching {
    val manager =
        requireNotNull(findManager()) { "DexKit: ambiguous or missing host GestureManager" }
    require(isMamlTouchMethod(manager)) { "Invalid GestureManager method signature" }
    val dispatchers = findDispatchers(manager).distinct().filter {
        it.name == "dispatchTouchEvent" && Modifier.isPublic(it.modifiers) &&
                isMamlTouchMethod(it) && Activity::class.java.isAssignableFrom(it.declaringClass)
    }
    require(dispatchers.isNotEmpty()) { "DexKit: missing valid GestureManager Activity callers" }
    (dispatchers + manager).forEach { it.isAccessible = true }
    MamlGesturePoints(manager, dispatchers)
}.onFailure(onFailure).getOrNull()

private fun isMamlTouchMethod(method: java.lang.reflect.Method): Boolean =
    !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java)) &&
            method.returnType == Boolean::class.javaPrimitiveType

/** Main-thread gesture ownership; no roots/Activities retained and no global pager hooks. */
internal class MamlPickerTouchGuard : AutoCloseable {
    private val active = WeakHashMap<View, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false

    @Synchronized
    fun afterTouch(view: View, action: Int, pickerVisible: Boolean, handled: Boolean): Boolean {
        if (closed) return false
        if (action == MotionEvent.ACTION_DOWN) {
            release(view)
            if (pickerVisible && handled) active[view] = true
        }
        if (active[view] != true) return false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            release(view)
        } else {
            // Reassert after native MamlView's preceding-flag check, including pointer changes.
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }

    @Synchronized
    fun release(view: View) {
        if (active.remove(view) != true) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.parent?.requestDisallowInterceptTouchEvent(false)
        } else {
            val weakView = WeakReference(view)
            main.post { weakView.get()?.parent?.requestDisallowInterceptTouchEvent(false) }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        active.keys.toList().forEach(::release)
        active.clear()
    }
}
