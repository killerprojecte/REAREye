package hk.uwu.reareye.hook.core

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 现代入口向生命周期转发的运行时接口。
 *
 * 应用目标必须在首次 attachBaseContext 前完成核心和业务 Hook 安装；热重载通过 API 102
 * 的 saved instance state 在新代际重新创建目标上下文和模块实例。
 */
interface HookRuntime {
    /** 接收模块加载信息并初始化日志。 */
    fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam)

    /** 在 system_server ClassLoader 中安装 system scope。 */
    fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam)

    /** 在目标包 ClassLoader 中立即安装核心生命周期与业务 Scope。 */
    fun onPackageReady(param: XposedModuleInterface.PackageReadyParam)

    /** 保存状态、冻结旧模块并允许 API 102 执行热重载。 */
    fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean

    /** 接收新代际状态并重装当前目标模块。 */
    fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam)
}

/** 当前第一阶段允许进入 Hook runtime 的目标包。 */
private object HookTargetAllowlist {
    private val packages = setOf(
        "com.xiaomi.subscreencenter",
        "com.android.thememanager",
        "com.android.systemui",
        "com.miui.weather2",
        "com.miui.gallery",
    )

    fun accepts(packageName: String): Boolean = packageName in packages
}

private const val KEY_APP_PACKAGE = "app.packageName"
private const val KEY_APP_PROCESS = "app.processName"
private const val KEY_APP_UID = "app.uid"
private const val KEY_APP_SOURCE_DIR = "app.sourceDir"
private const val KEY_APP_PUBLIC_SOURCE_DIR = "app.publicSourceDir"
private const val KEY_APP_SPLIT_SOURCE_DIRS = "app.splitSourceDirs"
private const val KEY_APP_DATA_DIR = "app.dataDir"
private const val KEY_APP_NATIVE_LIBRARY_DIR = "app.nativeLibraryDir"
private const val KEY_APP_CLASS_NAME = "app.className"
private const val MAX_RELOAD_TARGETS = 64

/** 将 ApplicationInfo 的热重载必需字段写为 classloader-neutral Bundle 数据。 */
internal fun Bundle.putReloadApplicationInfo(appInfo: ApplicationInfo) {
    putString(KEY_APP_PACKAGE, appInfo.packageName)
    putString(KEY_APP_PROCESS, appInfo.processName)
    putInt(KEY_APP_UID, appInfo.uid)
    putString(KEY_APP_SOURCE_DIR, appInfo.sourceDir)
    putString(KEY_APP_PUBLIC_SOURCE_DIR, appInfo.publicSourceDir)
    putStringArray(KEY_APP_SPLIT_SOURCE_DIRS, appInfo.splitSourceDirs?.copyOf())
    putString(KEY_APP_DATA_DIR, appInfo.dataDir)
    putString(KEY_APP_NATIVE_LIBRARY_DIR, appInfo.nativeLibraryDir)
    putString(KEY_APP_CLASS_NAME, appInfo.className)
}

/** 从 saved state 重建新的 ApplicationInfo，避免跨模块代传递旧实例。 */
internal fun Bundle.readReloadApplicationInfo(fallbackPackageName: String): ApplicationInfo =
    ApplicationInfo().apply {
        packageName = getString(KEY_APP_PACKAGE)?.takeIf { it.isNotBlank() } ?: fallbackPackageName
        processName = getString(KEY_APP_PROCESS)
        uid = getInt(KEY_APP_UID, 0)
        sourceDir = getString(KEY_APP_SOURCE_DIR)
        publicSourceDir = getString(KEY_APP_PUBLIC_SOURCE_DIR)
        splitSourceDirs = getStringArray(KEY_APP_SPLIT_SOURCE_DIRS)?.copyOf()
        dataDir = getString(KEY_APP_DATA_DIR)
        nativeLibraryDir = getString(KEY_APP_NATIVE_LIBRARY_DIR)
        className = getString(KEY_APP_CLASS_NAME)
    }

/**
 * HookRuntime 的 libxposed API 102 实现。
 *
 * 每个 package/process/ClassLoader 组合拥有独立目标状态、HookRegistry、生命周期注册表和
 * 模块实例。逻辑目标 ID 不包含 ClassLoader 地址，因此同一目标的热重载仍然使用稳定 Hook ID。
 */
@SuppressLint("XposedNewApi")
class HookRuntimeImpl(
    private val xposed: XposedInterface,
    private val scopeFactories: List<() -> HookScope>,
) : HookRuntime {
    private val logger = XposedHookLogger(xposed)
    private val frameworkInfo = xposed.captureFrameworkInfo(logger)
    private val states = ConcurrentHashMap<TargetKey, TargetState>()

    @Volatile
    private var currentProcessName: String = ""

    init {
        YLog.install(logger)
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        YLog.install(logger)
        currentProcessName = param.processName
        DexKitBootstrap.ensureLoaded(logger)
        logger.info(
            "libxposed module loaded: process=${param.processName} systemServer=${param.isSystemServer} " +
                    "framework={${frameworkInfo.diagnostics()}}"
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        runCatching {
            val classLoader = param.classLoader
            installTarget(
                TargetSpec(
                    packageName = "android",
                    processName = "system_server",
                    appInfo = systemServerApplicationInfo(),
                    classLoader = classLoader,
                    isSystemServer = true,
                ),
                restoredState = null,
            )
        }.onFailure {
            logger.error(
                "System server target initialization failed without killing target: " +
                        "framework={${frameworkInfo.diagnostics()}}",
                it,
            )
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        if (!HookTargetAllowlist.accepts(packageName)) {
            logger.debug("Skip package outside allowlist: package=$packageName")
            return
        }
        runCatching {
            val classLoader = param.classLoader
            val applicationInfo = param.applicationInfo
            val processName = currentProcessName
                .takeIf { it.isNotBlank() }
                ?: applicationInfo.processName.takeIf { it.isNotBlank() }
                ?: packageName
            installTarget(
                TargetSpec(
                    packageName = packageName,
                    processName = processName,
                    appInfo = applicationInfo,
                    classLoader = classLoader,
                    isSystemServer = false,
                ),
                restoredState = null,
            )
        }.onFailure {
            logger.error(
                "Application target initialization failed without aborting host callback: " +
                        "package=$packageName process=${currentProcessName.ifBlank { "<unknown>" }} " +
                        "framework={${frameworkInfo.diagnostics()}}",
                it,
            )
        }
    }

    /** TargetState 构造、注册和安装共享同一异常边界，避免半初始化目标泄漏。 */
    private fun installTarget(spec: TargetSpec, restoredState: Bundle?) {
        val key =
            TargetKey(spec.packageName, spec.processName, spec.classLoader, spec.isSystemServer)
        var state: TargetState? = null
        try {
            state = TargetState(spec)
            val existing = states.putIfAbsent(key, state)
            if (existing != null) {
                state.releaseFinal()
                logger.debug("Skip duplicate Hook target: target=${spec.targetId} hooks=${existing.registry.size()}")
                return
            }
            state.install(restoredState)
        } catch (throwable: Throwable) {
            val cleanupSucceeded = state?.let {
                states.remove(key, it)
                runCatching { it.releaseFinal() }
                    .onFailure { cleanupFailure ->
                        logger.error(
                            "Target cleanup threw: target=${spec.targetId}",
                            cleanupFailure
                        )
                    }
                    .getOrDefault(false)
            } ?: true
            logger.error(
                "Hook target installation failed: target=${spec.targetId} package=${spec.packageName} " +
                        "process=${spec.processName} systemServer=${spec.isSystemServer} " +
                        "hooks=${state?.registry?.size() ?: 0} cleanup=$cleanupSucceeded " +
                        "framework={${frameworkInfo.diagnostics()}}",
                throwable,
            )
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val targetSnapshot = states.values.toList()
        val saved = Bundle()
        val savedTargets = Bundle()
        var stateSaveSucceeded = true
        targetSnapshot.forEachIndexed { index, state ->
            runCatching {
                savedTargets.putBundle(reloadTargetKey(index), state.saveReloadState())
            }.onFailure {
                stateSaveSucceeded = false
                logger.error("Hot reload state save failed: target=${state.spec.targetId}", it)
            }
        }
        if (!stateSaveSucceeded) {
            logger.error("Hot reload rejected because target state could not be saved")
            return false
        }
        saved.putInt(KEY_TARGET_COUNT, targetSnapshot.size)
        saved.putBundle(KEY_TARGETS, savedTargets)
        runCatching { param.setSavedInstanceState(saved) }
            .onFailure {
                logger.error("Hot reload rejected because saved state could not be registered", it)
            }
            .getOrElse { return false }

        val transaction = runCatching {
            runReloadTransaction(
                items = targetSnapshot,
                preflight = TargetState::preflightFreezeForReload,
                prepare = TargetState::prepareFreezeForReload,
                rollback = TargetState::rollbackFreezeForReload,
                commit = TargetState::commitFreezeForReload,
                onFailure = { phase, state, throwable ->
                    logger.error(
                        "Hot reload transaction $phase failed: target=${state.spec.targetId}",
                        throwable,
                    )
                },
            )
        }.onFailure {
            logger.error("Hot reload transaction aborted unexpectedly before commit", it)
        }.getOrElse {
            ReloadTransactionResult(accepted = false, commitFailureCount = 0)
        }

        if (!transaction.accepted) {
            logger.warn(
                "Hot reload rejected before irreversible cleanup; old targets remain active: " +
                        "targets=${targetSnapshot.size}"
            )
            return false
        }

        // 只移除本次快照中的旧代状态；并发新增目标不属于该事务，不能被 states.clear() 误删。
        targetSnapshot.forEach { state ->
            val spec = state.spec
            states.remove(
                TargetKey(
                    spec.packageName,
                    spec.processName,
                    spec.classLoader,
                    spec.isSystemServer
                ),
                state,
            )
        }
        if (transaction.commitFailureCount == 0) {
            logger.info("Hot reload prepared: targets=${targetSnapshot.size} commitFailures=0")
        } else {
            logger.error(
                "Hot reload proceeding after irreversible cleanup failures; old generation will not be reused: " +
                        "targets=${targetSnapshot.size} commitFailures=${transaction.commitFailureCount}"
            )
        }
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        currentProcessName = param.processName
        val saved = param.savedInstanceState as? Bundle
        if (saved == null) {
            logger.error(
                "Hot reload cannot rebuild targets because savedInstanceState is not a Bundle; " +
                        "preserve unknown old handles=${param.oldHookHandles.size}"
            )
            return
        }

        val savedTargets = readSavedTargets(saved) ?: run {
            logger.error(
                "Hot reload saved state failed atomic validation; preserve old handles=${param.oldHookHandles.size}"
            )
            return
        }
        val processTargets = savedTargets.filter { (descriptor, _) ->
            descriptor.processName == param.processName
        }
        if (processTargets.isEmpty()) {
            logger.error(
                "Hot reload saved state contains no target for process=${param.processName}; " +
                        "preserve old handles=${param.oldHookHandles.size} savedTargets=${savedTargets.size}"
            )
            return
        }

        var succeeded = 0
        var failed = 0
        processTargets.forEach { (descriptor, targetState) ->
            val oldHandles = param.oldHookHandles.filter { it.belongsTo(descriptor.targetId) }
            val classLoader = resolveReloadClassLoader(descriptor, oldHandles)
            if (classLoader == null) {
                failed++
                logger.error(
                    "Hot reload target ClassLoader unavailable: target=${descriptor.targetId} " +
                            "oldHandles=${oldHandles.size}; clean only this target"
                )
                releaseOldHandles(oldHandles, "target ClassLoader unavailable")
                return@forEach
            }

            val spec = descriptor.toTargetSpec(classLoader)
            if (spec.targetId != descriptor.targetId) {
                failed++
                logger.error(
                    "Hot reload target descriptor mismatch: saved=${descriptor.targetId} rebuilt=${spec.targetId}"
                )
                releaseOldHandles(oldHandles, "target descriptor mismatch")
                return@forEach
            }

            val key =
                TargetKey(spec.packageName, spec.processName, classLoader, spec.isSystemServer)
            var state: TargetState? = null
            val rebuilt = runCatching {
                val rebuildingState = TargetState(spec, oldHandles)
                state = rebuildingState
                rebuildingState.install(targetState)
                if (!spec.isSystemServer && !rebuildingState.replayCurrentApplicationLifecycle()) {
                    error("Application lifecycle replay failed for target=${spec.targetId}")
                }
                check(rebuildingState.finishHotReloadReplacement()) {
                    "Old HookHandle cleanup incomplete for target=${spec.targetId}"
                }
                check(states.putIfAbsent(key, rebuildingState) == null) {
                    "Duplicate hot reload target=${spec.targetId}"
                }
                logger.info(
                    "Hot reload target rebuilt: target=${spec.targetId} oldHandles=${oldHandles.size} " +
                            "hooks=${rebuildingState.registry.size()}"
                )
            }.onFailure {
                logger.error(
                    "Hot reload target rebuild failed without cleaning other targets: " +
                            "target=${spec.targetId} oldHandles=${oldHandles.size}",
                    it,
                )
            }.isSuccess

            if (rebuilt) {
                succeeded++
            } else {
                failed++
                state?.let { failedState ->
                    states.remove(key, failedState)
                    val cleanupSucceeded = runCatching { failedState.releaseFinal() }
                        .onFailure {
                            logger.error(
                                "Hot reload failed-target cleanup threw: target=${spec.targetId}",
                                it,
                            )
                        }
                        .getOrDefault(false)
                    if (!cleanupSucceeded) {
                        logger.error("Hot reload failed-target cleanup incomplete: target=${spec.targetId}")
                    }
                } ?: releaseOldHandles(oldHandles, "target construction failed")
            }
        }

        val unknownHandleCount = param.oldHookHandles.count { handle ->
            processTargets.none { (descriptor, _) -> handle.belongsTo(descriptor.targetId) }
        }
        if (unknownHandleCount > 0) {
            logger.warn(
                "Preserve old HookHandles with no proven saved-state owner: " +
                        "process=${param.processName} count=$unknownHandleCount"
            )
        }
        logger.info(
            "Hot reload completed: process=${param.processName} succeeded=$succeeded failed=$failed " +
                    "unknownPreserved=$unknownHandleCount activeTargets=${states.size}"
        )
    }

    private fun readSavedTargets(saved: Bundle): List<Pair<ReloadTargetDescriptor, Bundle>>? {
        val targets = saved.getBundle(KEY_TARGETS) ?: run {
            logger.error("Hot reload saved target container is missing")
            return null
        }
        val count = saved.getInt(KEY_TARGET_COUNT, -1)
        if (count !in 0..MAX_RELOAD_TARGETS) {
            logger.error("Hot reload saved target count is invalid: count=$count max=$MAX_RELOAD_TARGETS")
            return null
        }
        val storedKeys = targets.keySet()
        if (storedKeys.size != count) {
            logger.error(
                "Hot reload saved target count mismatch: declared=$count stored=${storedKeys.size}"
            )
            return null
        }

        val result = ArrayList<Pair<ReloadTargetDescriptor, Bundle>>(count)
        val targetIds = HashSet<String>(count)
        val logicalTargets = HashSet<String>(count)
        repeat(count) { index ->
            val key = reloadTargetKey(index)
            if (key !in storedKeys) {
                logger.error("Hot reload saved target key missing: key=$key count=$count")
                return null
            }
            val state = targets.getBundle(key) ?: run {
                logger.error("Hot reload saved target entry is null: key=$key count=$count")
                return null
            }
            val descriptor = runCatching { ReloadTargetDescriptor.from(state) }
                .onFailure {
                    logger.error(
                        "Hot reload saved target descriptor invalid: index=$index",
                        it
                    )
                }
                .getOrNull()
                ?: return null
            if (!targetIds.add(descriptor.targetId)) {
                logger.error("Hot reload saved targetId duplicated: target=${descriptor.targetId}")
                return null
            }
            val logicalTarget = buildString {
                append(descriptor.packageName)
                append('|')
                append(descriptor.processName)
                append('|')
                append(descriptor.isSystemServer)
            }
            if (!logicalTargets.add(logicalTarget)) {
                logger.error(
                    "Hot reload logical target duplicated in process: package=${descriptor.packageName} " +
                            "process=${descriptor.processName} systemServer=${descriptor.isSystemServer}"
                )
                return null
            }
            result += descriptor to state
        }
        if (result.size != count) {
            logger.error("Hot reload valid descriptor count mismatch: declared=$count valid=${result.size}")
            return null
        }
        return result
    }

    private fun resolveReloadClassLoader(
        descriptor: ReloadTargetDescriptor,
        oldHandles: List<XposedInterface.HookHandle>,
    ): ClassLoader? {
        val declarationsByLoader = LinkedHashMap<ClassLoader, MutableSet<String>>()
        oldHandles.forEach { handle ->
            runCatching { handle.executable.declaringClass }
                .onFailure {
                    logger.error(
                        "Unable to inspect old HookHandle executable: target=${descriptor.targetId} id=${
                            safeHookId(
                                handle
                            )
                        }",
                        it,
                    )
                }
                .getOrNull()
                ?.let { declaringClass ->
                    val loader = declaringClass.classLoader ?: return@let
                    declarationsByLoader.getOrPut(loader, ::linkedSetOf) += declaringClass.name
                }
        }
        if (declarationsByLoader.isEmpty()) return null

        if (descriptor.isSystemServer) {
            if (declarationsByLoader.size != 1) {
                logger.error(
                    "Hot reload system_server ClassLoader ownership is ambiguous: " +
                            "target=${descriptor.targetId} candidates=${declarationsByLoader.size}"
                )
                return null
            }
            // 唯一候选直接来自该 targetId 的旧句柄声明类；禁止回退模块线程/系统 ClassLoader。
            return declarationsByLoader.keys.single()
        }

        val applicationClassName = normalizeApplicationClassName(
            descriptor.applicationPackageName,
            descriptor.applicationClassName,
        )
        val provenCandidates = if (applicationClassName != null) {
            declarationsByLoader.keys.filter { candidate ->
                runCatching { candidate.loadClass(applicationClassName) }
                    .map { applicationClass ->
                        applicationClass.name == applicationClassName &&
                                applicationClass.classLoader === candidate &&
                                Application::class.java.isAssignableFrom(applicationClass)
                    }
                    .getOrDefault(false)
            }
        } else {
            declarationsByLoader.filterValues { declaringClassNames ->
                declaringClassNames.any { className ->
                    className == descriptor.packageName || className.startsWith("${descriptor.packageName}.")
                }
            }.keys.toList()
        }
        if (provenCandidates.size != 1) {
            logger.error(
                "Hot reload application ClassLoader ownership not proven: target=${descriptor.targetId} " +
                        "applicationClass=${applicationClassName ?: "<none>"} candidates=${declarationsByLoader.size} " +
                        "proven=${provenCandidates.size}"
            )
            return null
        }
        return provenCandidates.single()
    }

    private fun normalizeApplicationClassName(packageName: String, className: String?): String? {
        val value = className?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith('.') -> packageName + value
            '.' !in value -> "$packageName.$value"
            else -> value
        }
    }

    private fun XposedInterface.HookHandle.belongsTo(targetId: String): Boolean =
        runCatching { id?.startsWith("reareye:$targetId:") == true }
            .onFailure {
                logger.error(
                    "Unable to inspect old HookHandle id for target=$targetId",
                    it
                )
            }
            .getOrDefault(false)

    private fun safeHookId(handle: XposedInterface.HookHandle): String =
        runCatching { handle.id.orEmpty() }.getOrDefault("<unavailable>")

    private fun releaseOldHandles(
        handles: List<XposedInterface.HookHandle>,
        reason: String,
    ) {
        handles.forEach { oldHandle ->
            runCatching { oldHandle.unhook() }
                .onFailure {
                    logger.error(
                        "Unable to release old HookHandle: id=${safeHookId(oldHandle)} reason=$reason",
                        it,
                    )
                }
        }
    }

    private data class ReloadTargetDescriptor(
        val targetId: String,
        val packageName: String,
        val processName: String,
        val isSystemServer: Boolean,
        val applicationPackageName: String,
        val applicationProcessName: String?,
        val applicationUid: Int,
        val applicationSourceDir: String?,
        val applicationPublicSourceDir: String?,
        val applicationSplitSourceDirs: Array<String>?,
        val applicationDataDir: String?,
        val applicationNativeLibraryDir: String?,
        val applicationClassName: String?,
    ) {
        fun toApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
            packageName = applicationPackageName
            processName = applicationProcessName
            uid = applicationUid
            sourceDir = applicationSourceDir
            publicSourceDir = applicationPublicSourceDir
            splitSourceDirs = applicationSplitSourceDirs?.copyOf()
            dataDir = applicationDataDir
            nativeLibraryDir = applicationNativeLibraryDir
            className = applicationClassName
        }

        fun toTargetSpec(classLoader: ClassLoader): TargetSpec {
            return TargetSpec(
                packageName = packageName,
                processName = processName,
                appInfo = toApplicationInfo(),
                classLoader = classLoader,
                isSystemServer = isSystemServer,
            )
        }

        companion object {
            fun from(state: Bundle): ReloadTargetDescriptor {
                val targetId = requireNotNull(state.getString(KEY_TARGET_ID)) {
                    "saved targetId is missing"
                }
                val packageName = requireNotNull(state.getString(KEY_PACKAGE)) {
                    "saved package is missing"
                }
                val processName = requireNotNull(state.getString(KEY_PROCESS)) {
                    "saved process is missing"
                }
                val applicationInfo = state.readReloadApplicationInfo(packageName)
                require(applicationInfo.packageName == packageName) {
                    "saved ApplicationInfo package mismatch: target=$packageName app=${applicationInfo.packageName}"
                }
                val descriptor = ReloadTargetDescriptor(
                    targetId = targetId,
                    packageName = packageName,
                    processName = processName,
                    isSystemServer = state.getBoolean(KEY_SYSTEM_SERVER, false),
                    applicationPackageName = applicationInfo.packageName,
                    applicationProcessName = applicationInfo.processName,
                    applicationUid = applicationInfo.uid,
                    applicationSourceDir = applicationInfo.sourceDir,
                    applicationPublicSourceDir = applicationInfo.publicSourceDir,
                    applicationSplitSourceDirs = applicationInfo.splitSourceDirs?.copyOf(),
                    applicationDataDir = applicationInfo.dataDir,
                    applicationNativeLibraryDir = applicationInfo.nativeLibraryDir,
                    applicationClassName = applicationInfo.className,
                )
                val expectedTargetId =
                    buildTargetId(packageName, processName, descriptor.toApplicationInfo())
                require(targetId == expectedTargetId) {
                    "saved targetId integrity mismatch: saved=$targetId expected=$expectedTargetId"
                }
                return descriptor
            }
        }
    }

    private data class IndexedScope(
        val index: Int,
        val scope: HookScope,
    )

    private inner class TargetState(
        val spec: TargetSpec,
        oldHookHandles: List<XposedInterface.HookHandle> = emptyList(),
    ) {
        var context: MutableHookContext = createContext(spec, oldHookHandles)
            private set
        var registry: HookRegistry = context.hooks
            private set
        private var scopes: List<HookScope> = emptyList()
        private var scopeEntries: List<IndexedScope> = emptyList()
        private var modules: List<HookModule> = emptyList()
        private var installed = false
        private var freezePrepared = false
        private var attachDispatched = false
        private var createDispatched = false
        private val attachDepth = ThreadLocal.withInitial { 0 }

        fun install(restoredState: Bundle?) {
            check(!installed) { "Target state already installed: ${spec.targetId}" }
            try {
                val validatedState = validateReloadState(restoredState)
                if (!spec.isSystemServer) installCoreHooks()
                val built = buildModules(validatedState)
                scopes = built.first
                modules = built.second
                installed = true
                logger.debug(
                    "Hook target installed: package=${spec.packageName} process=${spec.processName} " +
                            "systemServer=${spec.isSystemServer} hooks=${registry.size()}"
                )
            } catch (throwable: Throwable) {
                if (!releaseFinal()) {
                    logger.error(
                        "Target install cleanup failed: target=${spec.targetId}",
                        throwable
                    )
                }
                throw throwable
            }
        }

        fun saveReloadState(): Bundle {
            val result = Bundle()
            result.putString(KEY_TARGET_ID, spec.targetId)
            result.putString(KEY_PACKAGE, spec.packageName)
            result.putString(KEY_PROCESS, spec.processName)
            result.putBoolean(KEY_SYSTEM_SERVER, spec.isSystemServer)
            result.putReloadApplicationInfo(spec.appInfo)

            val scopeStates = Bundle()
            scopeEntries.forEach { entry ->
                val scope = entry.scope
                if (scope.reloadable) {
                    scopeStates.putBundle(
                        scopeStateKey(entry.index, scope),
                        sanitizeBundle(scope.saveReloadState(), "scope=${scope.javaClass.name}"),
                    )
                }
            }
            result.putBundle(KEY_SCOPE_STATES, scopeStates)

            val moduleStates = Bundle()
            modules.forEachIndexed { index, module ->
                if (module.isInstallCompleted && module.reloadable) {
                    moduleStates.putBundle(
                        moduleStateKey(index, module),
                        sanitizeBundle(module.saveReloadState(), "module=${module.javaClass.name}"),
                    )
                }
            }
            result.putBundle(KEY_MODULE_STATES, moduleStates)
            return result
        }

        /** 执行不触碰资源的目标级 reload 预检。 */
        fun preflightFreezeForReload(): Boolean {
            var success = true
            modules.forEach { module ->
                if (!module.preflightFreeze()) success = false
            }
            return success
        }

        /** 纯内存准备目标冻结；不得关闭 gate、释放资源或修改生命周期。 */
        fun prepareFreezeForReload(): Boolean {
            if (freezePrepared) return true
            modules.forEach { module ->
                if (!module.prepareFreeze()) {
                    modules.asReversed().forEach(HookModule::rollbackFreezePreparation)
                    return false
                }
            }
            freezePrepared = true
            return true
        }

        /** 撤销尚未提交的目标冻结准备，保证失败时旧代仍可被运行时持有。 */
        fun rollbackFreezeForReload() {
            if (!freezePrepared) {
                modules.asReversed().forEach(HookModule::rollbackFreezePreparation)
                return
            }
            modules.asReversed().forEach(HookModule::rollbackFreezePreparation)
            freezePrepared = false
        }

        /**
         * 提交不可逆目标冻结。
         *
         * cleanup 或生命周期冻结失败时返回 false，但保留 modules/scopes 引用并明确记录失败状态；
         * 上层事务仍必须进入新代，不能把半清理旧代重新投入运行。
         */
        fun commitFreezeForReload(): Boolean {
            if (!freezePrepared) {
                logger.error("Target freeze commit invariant violated: preparation missing target=${spec.targetId}")
                return false
            }
            var success = true
            runCatching { context.freezeLifecycle() }
                .onFailure {
                    success = false
                    logger.error(
                        "Target lifecycle freeze commit failed: target=${spec.targetId}",
                        it
                    )
                }
            modules.forEach { module ->
                val committed = runCatching { module.commitFreeze() }
                    .onFailure {
                        logger.error(
                            "Hook module freeze commit threw: target=${spec.targetId} module=${module.javaClass.name}",
                            it,
                        )
                    }
                    .getOrDefault(false)
                if (!committed) {
                    success = false
                    logger.error(
                        "Hook module freeze commit incomplete: " +
                                "target=${spec.targetId} module=${module.javaClass.name}"
                    )
                }
            }
            freezePrepared = false
            if (success) {
                logger.debug(
                    "Hot reload target frozen without unhooking registry: target=${spec.targetId} " +
                            "retainedHooks=${registry.size()}"
                )
                modules = emptyList()
                scopeEntries = emptyList()
                scopes = emptyList()
                installed = false
            } else {
                logger.error(
                    "Hot reload target frozen with incomplete cleanup; retain failed target state: " +
                            "target=${spec.targetId} modules=${modules.size} scopes=${scopes.size} hooks=${registry.size()}"
                )
            }
            return success
        }

        /** 兼容最终清理路径：准备并提交当前目标，再移除 Hook 和旧句柄。 */
        fun freezeForReload(): Boolean {
            if (!prepareFreezeForReload()) return false
            return commitFreezeForReload()
        }

        /** 最终释放失败重建目标的 Hook 和生命周期。 */
        fun releaseFinal(): Boolean {
            var success = freezeForReload()
            if (!registry.unhookAllChecked()) success = false
            if (!registry.finishHotReloadReplacement()) success = false
            return success
        }

        fun finishHotReloadReplacement(): Boolean = registry.finishHotReloadReplacement()

        fun replayCurrentApplicationLifecycle(): Boolean {
            if (spec.isSystemServer) return true
            val application = runCatching { resolveCurrentApplication(spec.classLoader) }
                .onFailure {
                    logger.error(
                        "Unable to resolve current Application for hot reload: " +
                                "package=${spec.packageName} process=${spec.processName}",
                        it,
                    )
                }
                .getOrNull()
                ?: run {
                    logger.error(
                        "Hot reload rejected: current Application unavailable for " +
                                "package=${spec.packageName} process=${spec.processName}"
                    )
                    return false
                }
            val observedPackage = runCatching { application.packageName }.getOrNull()
            if (observedPackage != spec.packageName) {
                logger.error(
                    "Hot reload rejected: current Application package mismatch expected=${spec.packageName} " +
                            "actual=$observedPackage"
                )
                return false
            }
            val replayed = context.lifecycle.replayCurrentApplication(application)
            if (replayed) {
                attachDispatched = true
                createDispatched = true
            }
            if (!replayed || context.appContext == null) {
                logger.error(
                    "Hot reload rejected: Application lifecycle replay failed: " +
                            "package=${spec.packageName} process=${spec.processName} replayed=$replayed"
                )
                return false
            }
            logger.info(
                "Hot reload Application lifecycle replayed: package=${spec.packageName} " +
                        "process=${spec.processName}"
            )
            return true
        }

        private fun validateReloadState(state: Bundle?): Bundle? {
            if (state == null) return null
            val valid = state.getString(KEY_TARGET_ID) == spec.targetId &&
                    state.getString(KEY_PACKAGE) == spec.packageName &&
                    state.getString(KEY_PROCESS) == spec.processName &&
                    state.getBoolean(KEY_SYSTEM_SERVER, !spec.isSystemServer) == spec.isSystemServer
            if (!valid) {
                logger.error(
                    "Drop reload state target mismatch: expected=${reloadStateKey(spec)} " +
                            "actual=${state.getString(KEY_TARGET_ID)}|${state.getString(KEY_PACKAGE)}|" +
                            state.getString(KEY_PROCESS)
                )
                return null
            }
            return state
        }

        private fun installCoreHooks() {
            val actual = resolveApplicationAttachBaseContext()
            if (actual != null) {
                val id = "reareye:${spec.targetId}:core:application.attachBaseContext.actual"
                installCoreOrigin(id, actual.toGenericString()) {
                    installAttachBaseContextHook(actual, id)
                }
            }

            val fallbackId = "reareye:${spec.targetId}:core:application.attachBaseContext.base"
            installCoreOrigin(fallbackId, "android.content.ContextWrapper#attachBaseContext") {
                val fallback = ContextWrapper::class.java.getDeclaredMethod(
                    "attachBaseContext",
                    Context::class.java,
                )
                if (actual != fallback) installAttachBaseContextHook(fallback, fallbackId)
            }

            val createId = "reareye:${spec.targetId}:core:instrumentation.callApplicationOnCreate"
            installCoreOrigin(createId, "android.app.Instrumentation#callApplicationOnCreate") {
                installCallApplicationOnCreateHook()
            }

            val terminateId = "reareye:${spec.targetId}:core:application.onTerminate"
            installCoreOrigin(terminateId, "android.app.Application#onTerminate") {
                installApplicationTerminateHook()
            }
        }

        private inline fun installCoreOrigin(id: String, origin: String, install: () -> Unit) {
            runCatching(install)
                .onSuccess {
                    logger.debug(
                        "Core lifecycle Hook installed: id=$id origin=$origin target=${spec.targetId} " +
                                "package=${spec.packageName} process=${spec.processName} hooks=${registry.size()}"
                    )
                }
                .onFailure {
                    logger.error(
                        "Core lifecycle Hook rejected; continue business Hook installation: " +
                                "id=$id origin=$origin target=${spec.targetId} package=${spec.packageName} " +
                                "process=${spec.processName} hooks=${registry.size()}",
                        it,
                    )
                }
        }

        private fun resolveApplicationAttachBaseContext(): Executable? {
            val className = spec.appInfo.className?.trim().orEmpty()
            if (className.isBlank()) return null
            val applicationClass = runCatching { spec.classLoader.loadClass(className) }
                .onFailure {
                    logger.error(
                        "Unable to resolve target Application class; keep ContextWrapper fallback: " +
                                "class=$className package=${spec.packageName}",
                        it,
                    )
                }
                .getOrNull()
                ?: return null
            if (!Application::class.java.isAssignableFrom(applicationClass)) {
                logger.error(
                    "Target Application class is not an Application; keep ContextWrapper fallback: " +
                            "class=${applicationClass.name} package=${spec.packageName}",
                )
                return null
            }
            var current: Class<*>? = applicationClass
            while (current != null && Application::class.java.isAssignableFrom(current)) {
                val declared = runCatching {
                    current.getDeclaredMethod("attachBaseContext", Context::class.java)
                }.getOrNull()
                if (declared != null) {
                    logger.debug(
                        "Application attachBaseContext resolved from nearest declaration: " +
                                "declaringClass=${current.name} targetClass=${applicationClass.name}"
                    )
                    return declared
                }
                current = current.superclass
            }
            logger.debug(
                "Target Application hierarchy does not declare attachBaseContext; " +
                        "use ContextWrapper fallback: class=${applicationClass.name}"
            )
            return null
        }

        private fun installAttachBaseContextHook(executable: Executable, id: String) {
            lateinit var installedHook: InstalledHook
            installedHook = registry.install(
                id = id,
                executable = executable,
                hooker = XposedInterface.Hooker { chain ->
                    val application = chain.thisObject as? Application
                        ?: return@Hooker chain.proceed()
                    val baseContext = chain.getArg(0) as? Context
                    if (!matchesApplication(application, baseContext)) {
                        return@Hooker chain.proceed()
                    }
                    val depth = attachDepth.get() ?: 0
                    attachDepth.set(depth + 1)
                    try {
                        val invocation = HookInvocation(chain, context) {
                            registry.remove(id, installedHook)
                        }
                        var failure: Throwable? = null
                        try {
                            invocation.proceed()
                        } catch (throwable: Throwable) {
                            failure = invocation.throwable ?: throwable
                        }
                        if (failure == null && depth == 0 && !attachDispatched) {
                            if (context.bindApplication(application, baseContext)) {
                                attachDispatched = true
                                context.lifecycle.dispatchAttach(invocation)
                            }
                        } else if (failure != null) {
                            logger.error(
                                "Application.attachBaseContext failed; skip normal lifecycle dispatch " +
                                        "package=${spec.packageName} process=${spec.processName} hook=$id",
                                failure,
                            )
                        }
                        if (invocation.hasResult) return@Hooker invocation.resultOrNull()
                        if (failure != null) throw failure
                        invocation.resultOrNull()
                    } finally {
                        if (depth == 0) attachDepth.remove() else attachDepth.set(depth)
                    }
                },
            )
        }

        private fun installCallApplicationOnCreateHook() {
            val executable = Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate",
                Application::class.java,
            )
            val id = "reareye:${spec.targetId}:core:instrumentation.callApplicationOnCreate"
            lateinit var installedHook: InstalledHook
            installedHook = registry.install(
                id = id,
                executable = executable,
                hooker = XposedInterface.Hooker { chain ->
                    val application = chain.getArg(0) as? Application
                        ?: return@Hooker chain.proceed()
                    if (!matchesApplication(application, null)) return@Hooker chain.proceed()
                    val invocation = HookInvocation(chain, context) {
                        registry.remove(id, installedHook)
                    }
                    var failure: Throwable? = null
                    try {
                        invocation.proceed()
                    } catch (throwable: Throwable) {
                        failure = invocation.throwable ?: throwable
                    }
                    if (failure == null && !createDispatched) {
                        if (context.bindApplication(application, null)) {
                            createDispatched = true
                            context.lifecycle.dispatchCreate(invocation)
                        } else {
                            logger.error(
                                "Application binding failed; skip create lifecycle dispatch: " +
                                        "package=${spec.packageName} process=${spec.processName}"
                            )
                        }
                    } else if (failure != null) {
                        logger.error(
                            "Instrumentation.callApplicationOnCreate failed; skip normal lifecycle dispatch " +
                                    "package=${spec.packageName} process=${spec.processName}",
                            failure,
                        )
                    }
                    if (invocation.hasResult) return@Hooker invocation.resultOrNull()
                    if (failure != null) throw failure
                    invocation.resultOrNull()
                },
            )
        }

        private fun installApplicationTerminateHook() {
            val executable = Application::class.java.getDeclaredMethod("onTerminate")
            val id = "reareye:${spec.targetId}:core:application.onTerminate"
            lateinit var installedHook: InstalledHook
            installedHook = registry.install(
                id = id,
                executable = executable,
                hooker = XposedInterface.Hooker { chain ->
                    if (!matchesApplication(chain.thisObject, null)) return@Hooker chain.proceed()
                    val invocation = HookInvocation(chain, context) {
                        registry.remove(id, installedHook)
                    }
                    var failure: Throwable? = null
                    try {
                        invocation.proceed()
                    } catch (throwable: Throwable) {
                        failure = invocation.throwable ?: throwable
                    }
                    if (failure == null) {
                        context.lifecycle.dispatchTerminate(invocation)
                    } else {
                        logger.error(
                            "Application.onTerminate failed; skip normal lifecycle dispatch " +
                                    "package=${spec.packageName} process=${spec.processName}",
                            failure,
                        )
                    }
                    if (invocation.hasResult) return@Hooker invocation.resultOrNull()
                    if (failure != null) throw failure
                    invocation.resultOrNull()
                },
            )
        }

        private fun matchesApplication(application: Any?, baseContext: Context?): Boolean {
            if (spec.isSystemServer || application !is Application) return false
            val observedPackage = runCatching {
                baseContext?.packageName ?: application.packageName
            }.getOrElse {
                logger.error(
                    "Unable to validate Application package; fail closed: package=${spec.packageName} " +
                            "process=${spec.processName}",
                    it,
                )
                return false
            }
            if (observedPackage != spec.packageName) {
                logger.warn(
                    "Skip Application lifecycle hook for package=$observedPackage; expected=${spec.packageName}",
                )
                return false
            }
            return true
        }

        private fun buildModules(restoredState: Bundle?): Pair<List<HookScope>, List<HookModule>> {
            val scopeStates = restoredState?.getBundle(KEY_SCOPE_STATES)
            val moduleStates = restoredState?.getBundle(KEY_MODULE_STATES)
            val builtScopes = HookEnvironment.withContext(context) {
                val entries = ArrayList<IndexedScope>(scopeFactories.size)
                scopeFactories.forEachIndexed { index, factory ->
                    try {
                        val scope = factory()
                        if (scope.reloadable) {
                            scopeStates?.getBundle(scopeStateKey(index, scope))
                                ?.let(scope::restoreReloadState)
                        }
                        entries += IndexedScope(index, scope)
                    } catch (throwable: Throwable) {
                        logger.error(
                            "Hook scope construction failed: index=$index package=${spec.packageName} " +
                                    "process=${spec.processName} systemServer=${spec.isSystemServer}",
                            throwable,
                        )
                        if (!spec.isSystemServer) throw throwable
                    }
                }
                entries
            }
            scopeEntries = builtScopes
            scopes = builtScopes.map { it.scope }
            val builtModules = HookEnvironment.withContext(context) {
                builtScopes.flatMap { it.scope.hooks }
            }
            modules = builtModules
            val attemptedModules = installHookModules(
                modules = builtModules,
                context = context,
                moduleStates = moduleStates,
                logger = logger,
                stateKey = ::moduleStateKey,
            )
            modules = attemptedModules
            return builtScopes.map { it.scope } to attemptedModules
        }
    }

    private fun createContext(
        spec: TargetSpec,
        oldHookHandles: List<XposedInterface.HookHandle> = emptyList(),
    ): MutableHookContext {
        val registry = HookRegistryImpl(xposed, logger, oldHookHandles)
        return MutableHookContext(
            packageName = spec.packageName,
            processName = spec.processName,
            appInfo = spec.appInfo,
            classLoader = spec.classLoader,
            isSystemServer = spec.isSystemServer,
            prefs = xposed.remoteHookPrefs(
                group = REMOTE_PREFS_GROUP,
                target = spec.targetId,
                framework = frameworkInfo,
                logger = logger,
            ),
            hooks = registry,
            logger = logger,
            systemContextProvider = { resolveSystemContext(spec.classLoader) },
        )
    }

    private data class TargetSpec(
        val packageName: String,
        val processName: String,
        val appInfo: ApplicationInfo,
        val classLoader: ClassLoader,
        val isSystemServer: Boolean,
    ) {
        val targetId: String = buildTargetId(packageName, processName, appInfo)
    }

    private data class TargetKey(
        val packageName: String,
        val processName: String,
        val classLoader: ClassLoader,
        val isSystemServer: Boolean,
    )

    companion object {
        private const val KEY_TARGETS = "targets"
        private const val KEY_TARGET_COUNT = "targetCount"
        private const val KEY_TARGET_ID = "targetId"
        private const val KEY_PACKAGE = "package"
        private const val KEY_PROCESS = "process"
        private const val KEY_SYSTEM_SERVER = "systemServer"
        private const val KEY_SCOPE_STATES = "scopeStates"
        private const val KEY_MODULE_STATES = "moduleStates"

        private fun reloadTargetKey(index: Int): String = "target:$index"

        private fun reloadStateKey(spec: TargetSpec): String =
            "target:${spec.targetId}|${spec.packageName}|${spec.processName}"

        private fun scopeStateKey(index: Int, scope: HookScope): String =
            "$index:${scope.javaClass.name}"

        private fun moduleStateKey(index: Int, module: HookModule): String =
            "$index:${module.javaClass.name}"

        @Suppress("DEPRECATION")
        private fun sanitizeBundle(source: Bundle, owner: String): Bundle {
            val target = Bundle()
            source.keySet().forEach { key ->
                when (val value = source.get(key)) {
                    null, is String, is CharSequence, is Boolean, is Byte, is Short,
                    is Int, is Long, is Float, is Double, is Char,
                    is ByteArray, is BooleanArray, is ShortArray, is IntArray,
                    is LongArray, is FloatArray, is DoubleArray, is CharArray,
                    is Array<*>, is Bundle -> target.putAny(key, value, owner)

                    else -> YLog.warn("Drop non classloader-neutral reload state: owner=$owner key=$key type=${value.javaClass.name}")
                }
            }
            return target
        }

        private fun Bundle.putAny(key: String, value: Any?, owner: String) {
            when (value) {
                null -> putString(key, null)
                is String -> putString(key, value)
                is CharSequence -> putCharSequence(key, value)
                is Boolean -> putBoolean(key, value)
                is Byte -> putByte(key, value)
                is Short -> putShort(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Double -> putDouble(key, value)
                is Char -> putChar(key, value)
                is ByteArray -> putByteArray(key, value)
                is BooleanArray -> putBooleanArray(key, value)
                is ShortArray -> putShortArray(key, value)
                is IntArray -> putIntArray(key, value)
                is LongArray -> putLongArray(key, value)
                is FloatArray -> putFloatArray(key, value)
                is DoubleArray -> putDoubleArray(key, value)
                is CharArray -> putCharArray(key, value)
                is Array<*> -> if (value.all { it == null || it is String }) {
                    @Suppress("UNCHECKED_CAST")
                    putStringArray(key, value as Array<String>?)
                } else {
                    YLog.warn("Drop unsupported reload array: owner=$owner key=$key")
                }

                is Bundle -> putBundle(key, sanitizeBundle(value, owner))
            }
        }
    }
}

/**
 * 安装业务模块；单个模块异常只记录并继续，核心生命周期 Hook 不经此路径。
 * 返回值保留所有已尝试模块，以便失败模块仍能在目标释放时执行清理。
 */
internal data class ReloadTransactionResult(
    val accepted: Boolean,
    val commitFailureCount: Int,
)

internal fun <T> runReloadTransaction(
    items: List<T>,
    preflight: (T) -> Boolean = { true },
    prepare: (T) -> Boolean,
    rollback: (T) -> Unit,
    commit: (T) -> Boolean,
    onFailure: (phase: String, item: T, throwable: Throwable) -> Unit = { _, _, _ -> },
): ReloadTransactionResult {
    var preflightSuccess = true
    items.forEach { item ->
        val passed = runCatching { preflight(item) }
            .onFailure { onFailure("preflight", item, it) }
            .getOrDefault(false)
        if (!passed) preflightSuccess = false
    }
    if (!preflightSuccess) {
        items.forEach { item ->
            runCatching { rollback(item) }
                .onFailure { onFailure("rollback", item, it) }
        }
        return ReloadTransactionResult(accepted = false, commitFailureCount = 0)
    }

    val attempted = ArrayList<T>(items.size)
    for (item in items) {
        attempted += item
        val prepared = runCatching { prepare(item) }
            .onFailure { onFailure("prepare", item, it) }
            .getOrDefault(false)
        if (!prepared) {
            attempted.asReversed().forEach { attemptedItem ->
                runCatching { rollback(attemptedItem) }
                    .onFailure { onFailure("rollback", attemptedItem, it) }
            }
            return ReloadTransactionResult(accepted = false, commitFailureCount = 0)
        }
    }
    // prepare 是纯内存阶段。commit 才执行不可逆清理；一旦进入 commit，即使某项目标失败，
    // 也必须继续提交其余目标并允许新代启动，绝不能返回 false 后复用半清理旧代。
    var commitFailureCount = 0
    items.forEach { item ->
        val result = runCatching { commit(item) }
        val committed = result.getOrDefault(false)
        if (!committed) {
            commitFailureCount++
            onFailure(
                "commit",
                item,
                result.exceptionOrNull()
                    ?: IllegalStateException("reload transaction commit incomplete"),
            )
        }
    }
    return ReloadTransactionResult(accepted = true, commitFailureCount = commitFailureCount)
}

internal fun installHookModules(
    modules: List<HookModule>,
    context: HookContext,
    moduleStates: Bundle?,
    logger: HookLogger,
    stateKey: (Int, HookModule) -> String,
): List<HookModule> {
    val attemptedModules = ArrayList<HookModule>(modules.size)
    modules.forEachIndexed { index, module ->
        attemptedModules += module
        try {
            module.install(
                context,
                if (module.reloadable) moduleStates?.getBundle(stateKey(index, module)) else null,
            )
        } catch (throwable: Throwable) {
            logger.error(
                "Business Hook module installation failed: module=${module.javaClass.name} " +
                        "package=${context.packageName} process=${context.processName}",
                throwable,
            )
        }
    }
    return attemptedModules
}

/** Hook scope 的接口；scope 只负责按目标上下文创建模块列表和可选重载状态。 */
interface HookScope {
    /** 当前目标要安装的模块实例；每次目标生命周期必须创建新实例。 */
    val hooks: List<HookModule>

    /** 是否参与统一热重载；默认所有 Scope 都参与。 */
    val reloadable: Boolean
        get() = true

    /** 保存 classloader-neutral Scope 状态。 */
    fun saveReloadState(): Bundle = Bundle()

    /** 恢复 Scope 状态。 */
    fun restoreReloadState(state: Bundle) = Unit
}

internal fun HookContext.methodInvoker(method: Method): XposedInterface.Invoker<*, Method> =
    (hooks as? InvokerProvider)?.methodInvoker(method)
        ?: error("Hook registry cannot provide method invoker")

internal fun HookContext.constructorInvoker(constructor: Constructor<*>): XposedInterface.CtorInvoker<*> =
    (hooks as? InvokerProvider)?.constructorInvoker(constructor)
        ?: error("Hook registry cannot provide constructor invoker")
