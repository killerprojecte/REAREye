package hk.uwu.reareye.hook.support

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** service API 102 可观察的模块运行状态。 */
enum class ModuleActivationState {
    /** service 尚未绑定或当前环境不是支持 libxposed service 的宿主。 */
    SERVICE_UNAVAILABLE,

    /** service 已绑定但没有获批的作用域。 */
    SCOPE_NOT_AUTHORIZED,

    /** service 已绑定且有作用域，但当前没有正在运行的目标进程。 */
    NO_RUNNING_TARGET,

    /** 当前至少有一个有效运行目标。 */
    ACTIVE,
}

/**
 * 可重复刷新的状态容器。
 *
 * service 绑定只保存实时查询函数；每次 [refresh] 都重新读取 scope/runningTargets。状态未变化时
 * 不重复通知，观察者移除后不会再收到页面外回调。
 */
internal class ModuleActivationTracker(
    private val onRefreshFailure: (Throwable) -> Unit = {},
) {
    private val observers = CopyOnWriteArraySet<(ModuleActivationState) -> Unit>()

    @Volatile
    private var state: ModuleActivationState = ModuleActivationState.SERVICE_UNAVAILABLE

    @Volatile
    private var stateProvider: (() -> ModuleActivationState)? = null

    fun current(): ModuleActivationState = state

    fun observe(observer: (ModuleActivationState) -> Unit) {
        observers += observer
        observer(state)
    }

    fun removeObserver(observer: (ModuleActivationState) -> Unit) {
        observers -= observer
    }

    @Synchronized
    fun bind(provider: () -> ModuleActivationState): ModuleActivationState {
        stateProvider = provider
        return refresh()
    }

    @Synchronized
    fun unbind(): ModuleActivationState {
        stateProvider = null
        publish(ModuleActivationState.SERVICE_UNAVAILABLE)
        return state
    }

    @Synchronized
    fun refresh(): ModuleActivationState {
        val provider = stateProvider ?: return state
        val next = runCatching(provider)
            .onFailure(onRefreshFailure)
            .getOrDefault(ModuleActivationState.SERVICE_UNAVAILABLE)
        publish(next)
        return state
    }

    private fun publish(next: ModuleActivationState) {
        if (state == next) return
        state = next
        observers.forEach { it(next) }
    }
}

/**
 * UI 侧 service 状态适配点。
 *
 * 该对象只依赖 libxposed service API 102；Hook 核心不读取 UI 状态，也不把 service
 * 生命周期混入目标进程。若 service 不可用，状态明确为 SERVICE_UNAVAILABLE 而非静默伪造激活。
 */
internal data class RemotePreferencesSnapshot(
    /** 在同一 service 代际内取得的远程偏好对象。 */
    val preferences: SharedPreferences,
    /** 取得远程偏好时对应的 service 代际。 */
    val generation: Long,
)

@SuppressLint("XposedNewApi")
object XposedModuleStatus {
    private val tracker = ModuleActivationTracker { throwable ->
        YLog.error("Unable to refresh libxposed module status", throwable)
    }
    private val remotePreferenceObservers = CopyOnWriteArraySet<(Long) -> Unit>()
    private val remoteStateLock = Any()

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var listenerRegistered = false

    /** service 代际标识；每次绑定/死亡都会递增，供远程偏好适配重新同步本地缓存。 */
    private val remotePreferencesGeneration = AtomicLong(0L)

    /** 当前缓存状态。 */
    fun current(): ModuleActivationState = tracker.current()

    /** 当前 service 代际；service 重新绑定后远程偏好适配必须重新同步本地缓存。 */
    fun remotePreferencesGeneration(): Long = synchronized(remoteStateLock) {
        remotePreferencesGeneration.get()
    }

    /**
     * 观察远程偏好 service 代际。
     *
     * 每次 service 绑定和死亡都会通知，即使模块激活状态仍然是同一个值；新增观察者会立即
     * 收到当前代际，页面无需通过固定延时猜测 remote 是否已经重新可读。
     */
    @Synchronized
    fun observeRemotePreferences(observer: (Long) -> Unit) {
        remotePreferenceObservers += observer
        observer(remotePreferencesGeneration())
        ensureListenerLocked()
    }

    /** 移除单个远程偏好 service 代际观察者。 */
    fun removeRemotePreferencesObserver(observer: (Long) -> Unit) {
        remotePreferenceObservers -= observer
    }

    /** 绑定 service listener 并观察状态变化。重复注册安全。 */
    @Synchronized
    fun observe(observer: (ModuleActivationState) -> Unit) {
        tracker.observe(observer)
        ensureListenerLocked()
    }

    /** 页面恢复或重新进入时重新读取 scope 和 runningTargets。 */
    @Synchronized
    fun refresh(): ModuleActivationState {
        ensureListenerLocked()
        return tracker.refresh()
    }

    /** 确保 service 监听已注册；偏好适配不需要额外的 UI 状态观察者。 */
    @Synchronized
    fun ensureServiceListener() {
        ensureListenerLocked()
    }

    /**
     * 在同一 service 代际内取得远程偏好快照。
     *
     * service 和 generation 先在同一把锁下捕获；远程偏好取得后再次校验两者。若期间发生绑定
     * 或死亡，丢弃这次结果并最多重试一次，避免旧代 SharedPreferences 覆盖新代状态。
     */
    internal fun remotePreferencesSnapshot(name: String): RemotePreferencesSnapshot? {
        require(name.isNotBlank()) { "Remote preference name must not be blank" }
        ensureServiceListener()
        repeat(2) { attempt ->
            val captured = synchronized(remoteStateLock) {
                service?.let { it to remotePreferencesGeneration.get() }
            } ?: return null
            val boundService = captured.first
            val capturedGeneration = captured.second
            if (boundService.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
                YLog.warn("libxposed service lacks PROP_CAP_REMOTE: preference=$name")
                return null
            }
            val preferences = runCatching { boundService.getRemotePreferences(name) }
                .onFailure {
                    YLog.warn(
                        "Unable to obtain remote preferences: preference=$name generation=$capturedGeneration " +
                                "attempt=${attempt + 1}",
                        it,
                    )
                }
                .getOrNull()
            if (preferences == null) {
                if (attempt == 0) {
                    YLog.warn(
                        "Retrying remote preference snapshot after obtain failure: " +
                                "preference=$name generation=$capturedGeneration"
                    )
                    return@repeat
                }
                YLog.warn(
                    "Remote preference obtain failed after one retry: " +
                            "preference=$name generation=$capturedGeneration"
                )
                return null
            }
            val stable = synchronized(remoteStateLock) {
                service === boundService && remotePreferencesGeneration.get() == capturedGeneration
            }
            if (stable) {
                return RemotePreferencesSnapshot(preferences, capturedGeneration)
            }
            YLog.warn(
                "Discarded remote preference snapshot after generation change: " +
                        "preference=$name attempt=${attempt + 1} generation=$capturedGeneration " +
                        "currentGeneration=${remotePreferencesGeneration()}"
            )
        }
        YLog.warn("Remote preference snapshot unavailable after one retry: preference=$name")
        return null
    }

    /** 返回当前 service 的远程偏好；service 未绑定或快照不稳定时返回 null。 */
    fun remotePreferences(name: String): SharedPreferences? =
        remotePreferencesSnapshot(name)?.preferences

    /**
     * 通过当前 API 102 service 覆盖写入 RemoteFile。
     *
     * 远程文件名先在本地校验；service 代际发生变化或任一 I/O 步骤失败时返回 false，并记录
     * 文件大小和阶段诊断，但绝不记录 blob 内容。
     */
    fun writeRemoteFile(name: String, bytes: ByteArray): Boolean {
        return ByteArrayInputStream(bytes).use { input ->
            writeRemoteFile(name, input, bytes.size.toLong())
        }
    }

    /**
     * 通过 API 102 service 流式覆盖 RemoteFile。
     *
     * RPP 可能达到几十 MB，UI 侧应使用此入口，避免再创建一个完整的 ByteArray。
     */
    fun writeRemoteFile(name: String, source: InputStream, expectedSize: Long = -1L): Boolean {
        val safeName = runCatching { RemoteFileName.requireValid(name) }
            .onFailure { YLog.error("Rejected invalid remote file write name: $name", it) }
            .getOrNull() ?: return false
        ensureServiceListener()
        val captured = synchronized(remoteStateLock) {
            service?.let { it to remotePreferencesGeneration.get() }
        }
        if (captured == null) {
            YLog.error("Unable to write remote file because service is unavailable: name=$safeName size=$expectedSize")
            return false
        }
        val boundService = captured.first
        val generation = captured.second
        if (boundService.apiVersion < XposedService.API_102) {
            YLog.error(
                "Unable to write remote file because API 102 is required: " +
                        "name=$safeName size=$expectedSize api=${boundService.apiVersion}",
            )
            return false
        }
        if (boundService.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
            YLog.error("Unable to write remote file because capability is unavailable: name=$safeName size=$expectedSize")
            return false
        }
        return runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(boundService.openRemoteFile(safeName))
                .use { output ->
                    output.channel.truncate(0L)
                    output.channel.position(0L)
                    val copied = source.copyTo(output, DEFAULT_REMOTE_COPY_BUFFER_SIZE)
                    check(expectedSize < 0L || copied == expectedSize) {
                        "Remote file size mismatch: expected=$expectedSize actual=$copied"
                    }
                    output.flush()
                    output.fd.sync()
                }
            val stable = synchronized(remoteStateLock) {
                service === boundService && remotePreferencesGeneration.get() == generation
            }
            check(stable) {
                "Remote service generation changed while writing file: name=$safeName generation=$generation"
            }
            true
        }.onFailure {
            YLog.error(
                "Unable to write remote file: name=$safeName size=$expectedSize generation=$generation",
                it,
            )
        }.getOrDefault(false)
    }

    private const val DEFAULT_REMOTE_COPY_BUFFER_SIZE = 1024 * 1024

    /** 删除 RemoteFile；API 返回“文件不存在”时按幂等删除成功处理。 */
    fun deleteRemoteFile(name: String): Boolean {
        val safeName = runCatching { RemoteFileName.requireValid(name) }
            .onFailure { YLog.error("Rejected invalid remote file delete name: $name", it) }
            .getOrNull() ?: return false
        ensureServiceListener()
        val captured = synchronized(remoteStateLock) {
            service?.let { it to remotePreferencesGeneration.get() }
        }
        if (captured == null) {
            YLog.error("Unable to delete remote file because service is unavailable: name=$safeName")
            return false
        }
        val boundService = captured.first
        val generation = captured.second
        if (boundService.apiVersion < XposedService.API_102) {
            YLog.error(
                "Unable to delete remote file because API 102 is required: " +
                        "name=$safeName api=${boundService.apiVersion}",
            )
            return false
        }
        if (boundService.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
            YLog.error("Unable to delete remote file because capability is unavailable: name=$safeName")
            return false
        }
        return runCatching {
            val deleted = boundService.deleteRemoteFile(safeName)
            val stable = synchronized(remoteStateLock) {
                service === boundService && remotePreferencesGeneration.get() == generation
            }
            check(stable) {
                "Remote service generation changed while deleting file: name=$safeName generation=$generation"
            }
            if (!deleted) {
                YLog.info("Remote file already absent during delete: name=$safeName")
            }
            true
        }.onFailure {
            YLog.error("Unable to delete remote file: name=$safeName generation=$generation", it)
        }.getOrDefault(false)
    }

    /**
     * 不读取旧 map，直接删除整个 RemotePreferences 组。
     *
     * 迁移旧版本留下的超大 blob 时，先取 SharedPreferences 快照本身可能触发 Binder 超限；该
     * 操作只发送组名，供迁移在 RemoteFile 写完后清理旧组并重新建立小 map。
     */
    fun deleteRemotePreferences(name: String): Boolean {
        require(name.isNotBlank()) { "Remote preference name must not be blank" }
        ensureServiceListener()
        val captured = synchronized(remoteStateLock) {
            service?.let { it to remotePreferencesGeneration.get() }
        }
        if (captured == null) {
            YLog.error("Unable to delete remote preferences because service is unavailable: name=$name")
            return false
        }
        val boundService = captured.first
        val generation = captured.second
        if (boundService.apiVersion < XposedService.API_101) {
            YLog.error(
                "Unable to delete remote preferences because API 101 is required: " +
                        "name=$name api=${boundService.apiVersion}",
            )
            return false
        }
        if (boundService.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) {
            YLog.error("Unable to delete remote preferences because capability is unavailable: name=$name")
            return false
        }
        return runCatching {
            boundService.deleteRemotePreferences(name)
            val stable = synchronized(remoteStateLock) {
                service === boundService && remotePreferencesGeneration.get() == generation
            }
            check(stable) {
                "Remote service generation changed while deleting preferences: name=$name generation=$generation"
            }
            true
        }.onFailure {
            YLog.error("Unable to delete remote preferences: name=$name generation=$generation", it)
        }.getOrDefault(false)
    }

    /** 移除单个 UI 观察者。 */
    fun removeObserver(observer: (ModuleActivationState) -> Unit) {
        tracker.removeObserver(observer)
    }

    private fun ensureListenerLocked() {
        if (listenerRegistered) return
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(boundService: XposedService) {
                    val generation = synchronized(remoteStateLock) {
                        service = boundService
                        remotePreferencesGeneration.incrementAndGet()
                    }
                    YLog.info(
                        "libxposed service bound: generation=$generation " +
                                "remoteCapability=${boundService.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L}",
                    )
                    publishRemotePreferencesGeneration(generation)
                    tracker.bind { resolveState(boundService) }
                }

                override fun onServiceDied(deadService: XposedService) {
                    val generation = synchronized(remoteStateLock) {
                        if (service !== deadService) return@synchronized null
                        service = null
                        remotePreferencesGeneration.incrementAndGet()
                    } ?: return
                    YLog.warn("libxposed service died: generation=$generation")
                    publishRemotePreferencesGeneration(generation)
                    tracker.unbind()
                }
            })
            listenerRegistered = true
        } catch (throwable: Throwable) {
            listenerRegistered = false
            YLog.error("Unable to register libxposed service listener", throwable)
            throw throwable
        }
    }

    private fun publishRemotePreferencesGeneration(generation: Long) {
        remotePreferenceObservers.forEach { observer ->
            observer(generation)
        }
    }

    private fun resolveState(boundService: XposedService): ModuleActivationState {
        val scope = boundService.scope
        if (scope.isEmpty()) return ModuleActivationState.SCOPE_NOT_AUTHORIZED
        val runningTargets = boundService.runningTargets
        return if (runningTargets.any(::isValidRunningTarget)) {
            ModuleActivationState.ACTIVE
        } else {
            ModuleActivationState.NO_RUNNING_TARGET
        }
    }

    private fun isValidRunningTarget(target: HookedTarget): Boolean =
        target.state != HookedTarget.State.FAILED
}




