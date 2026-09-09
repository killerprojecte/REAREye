package hk.uwu.reareye.hook.core

import android.content.Context
import android.os.Bundle
import com.highcapable.kavaref.resolver.base.MemberResolver
import java.util.concurrent.atomic.AtomicBoolean

/** 保护旧代 Hook 回调的代际闸门；关闭后所有异步/Binder 回调都必须 fail-fast。 */
class HookGenerationGate internal constructor() {
    private val open = AtomicBoolean(true)

    fun isOpen(): Boolean = open.get()

    internal fun close() {
        open.set(false)
    }

    internal fun reopen() {
        open.set(true)
    }

    fun requireOpen() {
        check(isOpen()) { "Hook generation is closed" }
    }
}

/**
 * 自研 Hook 模块基类。
 *
 * 模块实例只属于一个目标上下文和一个运行代际。默认模块可热重载，状态使用
 * classloader-neutral Bundle 保存；模块可以覆盖状态方法和 onReloading 释放外部资源，但不得把
 * 旧代际模块对象传递给新代际。
 */
abstract class HookModule {
    private lateinit var target: HookContext
    private var installed = false
    private var installCompleted = false
    private var frozen = false
    private var freezePrepared = false
    private var freezeCommitSucceeded: Boolean? = null
    private val trackedResources = LinkedHashSet<AutoCloseable>()
    private val generationGate = HookGenerationGate()

    /** 仅已完成 onHook/onReloaded 的模块参与下一代状态保存。 */
    internal val isInstallCompleted: Boolean
        get() = installed && installCompleted

    /** 子类实现目标解析和 Hook 注册。 */
    abstract fun onHook()

    /** 是否参与统一热重载；默认所有现有 Hook 模块都参与。 */
    open val reloadable: Boolean
        get() = true

    /** 保存可跨 ClassLoader 传递的模块状态；只应写入 Bundle 支持的基础类型。 */
    open fun saveReloadState(): Bundle = Bundle()

    /** 在新代际 onHook 前恢复旧代际状态；默认无状态。 */
    open fun restoreReloadState(state: Bundle) = Unit

    /** 旧代际冻结前释放协程、Receiver、Binder 等外部资源；返回值表示清理是否完整成功。 */
    open fun onReloading(): Boolean = true

    /** 不执行清理的 reload 预检；失败时整个事务不会进入资源 cleanup。 */
    open fun onReloadingPreflight(): Boolean = true

    /**
     * 建立提交冻结前所需的纯内存状态；返回 false 表示当前状态暂时不允许重载。
     *
     * 实现不得关闭 generation gate 或释放外部资源。返回 false 或抛出异常时，运行时会立即调用
     * [onReloadingPreparationRolledBack] 撤销本次调用可能留下的部分准备。
     */
    open fun onReloadingPrepare(): Boolean = true

    /**
     * 撤销 [onReloadingPrepare] 建立的纯内存状态；实现必须幂等，并允许在部分准备后调用。
     */
    open fun onReloadingPreparationRolledBack() = Unit

    /** teardown 失败或多目标事务回滚时恢复旧代回调资源；必须幂等。 */
    open fun onReloadRollback(): Boolean = true

    /** 当前模块代际是否仍允许执行 Hook/异步/Binder 回调。 */
    val isGenerationActive: Boolean
        get() = generationGate.isOpen()

    /** 当前代异步/Binder 回调必须检查的 generation gate。 */
    protected val reloadGenerationGate: HookGenerationGate
        get() = generationGate

    /** 新代际安装完成后的可选恢复通知。 */
    open fun onReloaded() = Unit

    /** 跟踪由当前模块创建的可关闭资源，统一纳入热重载清理。 */
    protected fun <T : AutoCloseable> trackResource(resource: T): T {
        synchronized(trackedResources) { trackedResources += resource }
        return resource
    }

    /** 从自动清理集合移除已由模块显式关闭的资源。 */
    protected fun untrackResource(resource: AutoCloseable) {
        synchronized(trackedResources) { trackedResources.remove(resource) }
    }

    /** 当前目标包名。 */
    val packageName: String
        get() = requireTarget().packageName

    /** 当前目标进程名。 */
    val processName: String
        get() = requireTarget().processName

    /** 当前目标应用信息。 */
    val appInfo
        get() = requireTarget().appInfo

    /** 当前目标 ClassLoader。 */
    val appClassLoader: ClassLoader
        get() = requireTarget().classLoader

    /** 按当前目标 ClassLoader 解析类名；这是 DSL 的显式类加载边界。 */
    fun String.toClass(initialize: Boolean = false): Class<*> =
        toClass(appClassLoader, initialize)

    /** 使用指定 ClassLoader 解析类名，兼容现有 KavaRef 调用点。 */
    fun String.toClass(classLoader: ClassLoader, initialize: Boolean = false): Class<*> {
        return if (initialize) {
            Class.forName(this, true, classLoader)
        } else {
            classLoader.loadClass(this)
        }
    }

    /** 当前目标 Application 资源；attachBaseContext 前立即失败。 */
    val appResources
        get() = requireTarget().appContext?.resources
            ?: error("Application resources requested before attachBaseContext: $packageName")

    /** 当前 Application，attachBaseContext 前为空。 */
    val appContext: Context?
        get() = requireTarget().appContext

    /** 当前真实系统 Context；解析失败时由上下文记录并按目标回退。 */
    val systemContext: Context
        get() = requireTarget().systemContext

    /** 当前目标生命周期注册表。 */
    val lifecycle: HookLifecycle
        get() = requireTarget().lifecycle

    /** 当前目标偏好。 */
    val prefs: HookPrefs
        get() = requireTarget().prefs

    /** 当前目标是否为 system_server。 */
    val isSystemServer: Boolean
        get() = requireTarget().isSystemServer

    /** 当前目标是否为后屏设备。 */
    val isRearDevice: Boolean
        get() = requireTarget().isRearDevice

    /** 按包名装载 Hook DSL。 */
    fun loadApp(name: String, initiate: HookContext.() -> Unit) {
        val current = requireTarget()
        if (!current.isSystemServer && (name.isBlank() || name == current.packageName)) {
            HookEnvironment.withContext(current, this) { initiate(current) }
        }
    }

    /** 按多个包名装载 Hook DSL。 */
    fun loadApp(vararg names: String, initiate: HookContext.() -> Unit) {
        require(names.isNotEmpty()) { "loadApp requires at least one package name" }
        if (names.any { it == packageName }) loadApp(names.first { it == packageName }, initiate)
    }

    /** 只在 system_server 装载 Hook DSL。 */
    fun loadSystem(initiate: HookContext.() -> Unit) {
        val current = requireTarget()
        if (current.isSystemServer) HookEnvironment.withContext(current, this) { initiate(current) }
    }

    /** 只在指定进程装载 Hook DSL。 */
    fun withProcess(name: String, initiate: HookContext.() -> Unit) {
        if (processName == name) {
            val current = requireTarget()
            HookEnvironment.withContext(current, this) { initiate(current) }
        }
    }

    /** 为 KavaRef 方法/构造器解析结果安装单成员 Hook。 */
    fun MemberResolver<*, *>.hook(): HookBuilder = createHookBuilder(this, autoInstall = true)

    /** 在配置块结束后安装单成员 Hook。 */
    fun MemberResolver<*, *>.hook(initiate: HookBuilder.() -> Unit): HookBuilder =
        createHookBuilder(this, autoInstall = false).apply(initiate).also { it.install() }

    /** 为 KavaRef 构造器列表安装批量 Hook。 */
    fun Iterable<MemberResolver<*, *>>.hookAll(): HookBatchBuilder =
        createHookBatchBuilder(this, autoInstall = true)

    /** 在配置块结束后安装批量 Hook。 */
    fun Iterable<MemberResolver<*, *>>.hookAll(initiate: HookBatchBuilder.() -> Unit): HookBatchBuilder =
        createHookBatchBuilder(this, autoInstall = false).apply(initiate).also { it.install() }

    /** 注册 Application 生命周期回调；运行时统一从正确的 framework 入口分发。 */
    fun onAppLifecycle(initiate: ApplicationLifecycleBuilder.() -> Unit) {
        val builder = ApplicationLifecycleBuilder(this, requireTarget())
        builder.initiate()
        builder.install()
    }

    internal fun install(target: HookContext, restoredState: Bundle? = null) {
        check(!installed) { "HookModule instance cannot be reused across targets: ${this::class.java.name}" }
        check(!frozen) { "HookModule instance is frozen: ${this::class.java.name}" }
        this.target = target
        installed = true
        installCompleted = false
        HookEnvironment.withContext(target, this) {
            try {
                if (reloadable && restoredState != null) restoreReloadState(restoredState)
                onHook()
                onReloaded()
                installCompleted = true
            } catch (throwable: Throwable) {
                target.logger.error(
                    "Hook module failed: module=${this::class.java.name} package=${target.packageName} " +
                            "process=${target.processName}",
                    throwable,
                )
                throw throwable
            }
        }
    }

    /** 执行不触碰外部资源的 reload 预检。 */
    internal fun preflightFreeze(): Boolean {
        if (!installed || frozen) return true
        val current = requireTarget()
        return runCatching { onReloadingPreflight() }
            .onFailure {
                current.logger.error(
                    "Hook module reload preflight failed: module=${this::class.java.name} " +
                            "package=${current.packageName} process=${current.processName}",
                    it,
                )
            }
            .getOrElse { false }
            .also { passed ->
                if (!passed) {
                    current.logger.warn(
                        "Hook module reload preflight returned false: module=${this::class.java.name} " +
                                "package=${current.packageName} process=${current.processName}"
                    )
                }
            }
    }

    /**
     * 纯内存准备阶段。
     *
     * 此阶段不得关闭 generation gate、调用 [onReloading] 或释放 tracked resources；因此任一目标
     * 后续准备失败时，旧代仍保持完整可用状态。
     */
    internal fun prepareFreeze(): Boolean {
        if (frozen || freezePrepared) return true
        if (!installed) {
            freezePrepared = true
            return true
        }
        val current = requireTarget()
        val prepared = try {
            onReloadingPrepare()
        } catch (throwable: Throwable) {
            current.logger.error(
                "Hook module reload preparation failed: module=${this::class.java.name} " +
                        "package=${current.packageName} process=${current.processName}",
                throwable,
            )
            rollbackReloadingPreparation(current)
            return false
        }
        if (!prepared) {
            current.logger.warn(
                "Hook module reload preparation returned false: module=${this::class.java.name} " +
                        "package=${current.packageName} process=${current.processName}"
            )
            rollbackReloadingPreparation(current)
            return false
        }
        freezePrepared = true
        return true
    }

    /**
     * 提交不可逆冻结。
     *
     * 只有所有目标都完成纯准备后才会进入此阶段。cleanup 失败时模块仍标记 frozen、gate 保持关闭，
     * 调用方必须继续进入新代而不能返回 false 后复用半清理旧代；返回值仅用于记录最终清理状态。
     */
    internal fun commitFreeze(): Boolean {
        if (frozen) return freezeCommitSucceeded != false
        if (!freezePrepared) {
            if (::target.isInitialized) {
                target.logger.error(
                    "Hook module freeze commit rejected because preparation is missing: " +
                            "module=${this::class.java.name} package=${target.packageName} process=${target.processName}"
                )
            }
            return false
        }
        freezePrepared = false
        generationGate.close()
        if (!installed) {
            frozen = true
            freezeCommitSucceeded = true
            return true
        }

        val current = requireTarget()
        var success = true
        try {
            if (!onReloading()) {
                success = false
                current.logger.error(
                    "Hook module reload cleanup returned false during commit: module=${this::class.java.name} " +
                            "package=${current.packageName} process=${current.processName}"
                )
            }
        } catch (throwable: Throwable) {
            success = false
            current.logger.error(
                "Hook module reload cleanup failed during commit: module=${this::class.java.name} " +
                        "package=${current.packageName} process=${current.processName}",
                throwable,
            )
        }
        if (!closeTrackedResources(current)) success = false
        frozen = true
        freezeCommitSucceeded = success
        if (!success) {
            current.logger.error(
                "Hook module frozen with incomplete cleanup; old generation must not be reused: " +
                        "module=${this::class.java.name} package=${current.packageName} process=${current.processName}"
            )
        }
        return success
    }

    /** 撤销尚未提交的纯内存准备；旧代 gate 和资源从未被修改。 */
    internal fun rollbackFreezePreparation() {
        if (frozen || !freezePrepared) return
        if (!installed) {
            freezePrepared = false
            return
        }
        val current = requireTarget()
        try {
            rollbackReloadingPreparation(current)
        } finally {
            freezePrepared = false
        }
    }

    /** 单模块最终释放快捷方式；commit 失败后模块仍已冻结，不允许重试或复用。 */
    internal fun freeze(): Boolean {
        if (frozen) return freezeCommitSucceeded != false
        if (!preflightFreeze()) return false
        if (!prepareFreeze()) return false
        return commitFreeze()
    }

    /** 调用模块级准备回滚；异常只记录，调用方仍必须清除内部准备标记。 */
    private fun rollbackReloadingPreparation(context: HookContext) {
        try {
            onReloadingPreparationRolledBack()
        } catch (throwable: Throwable) {
            context.logger.error(
                "Hook module reload preparation rollback failed: module=${this::class.java.name} " +
                        "package=${context.packageName} process=${context.processName}",
                throwable,
            )
        }
    }

    private fun closeTrackedResources(context: HookContext): Boolean {
        val resources = synchronized(trackedResources) { trackedResources.toList() }
        var success = true
        resources.forEach { resource ->
            try {
                resource.close()
                synchronized(trackedResources) { trackedResources.remove(resource) }
            } catch (throwable: Throwable) {
                success = false
                context.logger.error(
                    "Tracked resource cleanup failed: module=${this::class.java.name} " +
                            "resource=${resource.javaClass.name} package=${context.packageName} " +
                            "process=${context.processName}",
                    throwable,
                )
            }
        }
        return success
    }

    internal fun targetContext(): HookContext = requireTarget()

    private fun requireTarget(): HookContext {
        check(::target.isInitialized) {
            "HookModule context is not initialized: ${this::class.java.name}"
        }
        return target
    }
}

/**
 * Application 生命周期兼容 DSL。
 *
 * 回调只登记到当前 HookContext；真实安装点由 HookRuntime 在 package-ready 阶段提前安装：
 * attachBaseContext、Instrumentation.callApplicationOnCreate 和 onTerminate。
 */
class ApplicationLifecycleBuilder internal constructor(
    private val module: HookModule,
    private val context: HookContext,
) {
    private var attachCallback: (HookInvocation.() -> Unit)? = null
    private var createCallback: (HookInvocation.() -> Unit)? = null
    private var terminateCallback: (HookInvocation.() -> Unit)? = null

    /** 在 Application.attachBaseContext 完成后执行回调。 */
    fun attachBaseContext(callback: HookInvocation.() -> Unit) {
        attachCallback = callback
    }

    /** 在 Instrumentation.callApplicationOnCreate 完成后执行回调。 */
    fun onCreate(callback: HookInvocation.() -> Unit) {
        createCallback = callback
    }

    /** 在 Application.onTerminate 完成后执行回调；仅用于模拟器/测试进程。 */
    fun onTerminate(callback: HookInvocation.() -> Unit) {
        terminateCallback = callback
    }

    internal fun install() {
        context.lifecycle.register(module, attachCallback, createCallback, terminateCallback)
    }
}
