package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

/**
 * AppEmbed 客户端会话的纯状态机。
 *
 * Android View、Binder 和 TaskView 回调可能乱序到达；调用方只执行这里返回的动作，从而
 * 确定处理旧 Surface 代际、晚到 SurfacePackage 和早到 task 事件。
 */
class AppEmbedSessionState {
    /** 客户端可观察阶段，用于日志、测试和阻止关闭后的重入。 */
    enum class Phase { DETACHED, WAITING_FOR_SERVICE, CREATING, ATTACHED, FAILED, CLOSED }

    /** 状态转换要求 Android 外层执行的副作用。 */
    sealed interface Action {
        /** 发起一次异步 SystemUI binder 获取。 */
        data object Connect : Action

        /** 使用指定 Surface 代际创建远端会话。 */
        data class Create(val surfaceGeneration: Long) : Action

        /** 把回调中的 SurfacePackage 绑定到当前本地 SurfaceView。 */
        data class AttachSurfacePackage(val sessionId: Long) : Action

        /** 释放晚到或重复回调携带的 SurfacePackage 副本。 */
        data object DiscardSurfacePackage : Action

        /** 释放指定远端 session。 */
        data class ReleaseRemote(val sessionId: Long) : Action

        /** 目标 task 已由 ShellTaskOrganizer 确认，可以开启输入洞和焦点。 */
        data class TaskBecameActive(val sessionId: Long) : Action

        /** 清理本地 SurfacePackage 以及宿主输入区域注册。 */
        data object ClearLocalSurface : Action
    }

    /** 当前状态阶段。 */
    var phase: Phase = Phase.DETACHED
        private set

    /** 每次 Surface 建立、销毁或 broker 死亡时递增，用来拒绝旧 callback。 */
    var surfaceGeneration: Long = 0L
        private set

    /** 当前远端 session id。 */
    var sessionId: Long? = null
        private set

    /** TaskView.Listener 是否已经确认当前 session 的目标 task 存在。 */
    var taskActive: Boolean = false
        private set(value) {
            field = value
            if (!value) nativeTaskVisible = null
        }

    /** Native visibility is distinct from task existence; a hidden task must not own an input hole. */
    private var nativeTaskVisible: Boolean? = null

    /** Whether the current task can receive input, including the native hidden-surface state. */
    val taskReceivesInput: Boolean
        get() = taskActive && nativeTaskVisible != false

    /** Visibility may arrive before the synchronous createSession result. */
    private val earlyTaskVisibility = LinkedHashMap<Long, Boolean>()

    /** SystemUI service binder 是否仍可调用。 */
    private var serviceConnected = false

    /** 本地 SurfaceView 是否具备 hostToken 和有效尺寸。 */
    private var surfaceAvailable = false

    /** createSession 返回前到达的 SurfaceReady session id。 */
    private val earlyReadyIds = LinkedHashSet<Long>()

    /** createSession 返回前到达的 terminal error session id。 */
    private val earlyFailedIds = LinkedHashSet<Long>()

    /** createSession 返回前到达的 TaskCreated session id。 */
    private val earlyTaskCreatedIds = LinkedHashSet<Long>()

    /** createSession 返回前到达的 TaskRemovalStarted session id。 */
    private val earlyTaskRemovedIds = LinkedHashSet<Long>()

    /** Surface 创建或重建后进入连接/创建阶段，并释放上一代 session。 */
    fun onSurfaceAvailable(): List<Action> {
        check(phase != Phase.CLOSED) { "AppEmbed session is closed" }
        val oldId = sessionId
        sessionId = null
        taskActive = false
        surfaceAvailable = true
        surfaceGeneration++
        clearEarlyCallbacks()
        return buildList {
            if (oldId != null) {
                add(Action.ClearLocalSurface)
                add(Action.ReleaseRemote(oldId))
            }
            if (serviceConnected) {
                phase = Phase.CREATING
                add(Action.Create(surfaceGeneration))
            } else {
                phase = Phase.WAITING_FOR_SERVICE
                add(Action.Connect)
            }
        }
    }

    /** service 接通后，如 Surface 已就绪且无 create 在途则创建当前代 session。 */
    fun onServiceConnected(): List<Action> {
        check(phase != Phase.CLOSED) { "AppEmbed session is closed" }
        serviceConnected = true
        if (!surfaceAvailable || sessionId != null || phase == Phase.CREATING) return emptyList()
        phase = Phase.CREATING
        return listOf(Action.Create(surfaceGeneration))
    }

    /** bootstrap 失败时终止本代连接，避免同步失败触发无界重试。 */
    fun onConnectionFailed(): List<Action> {
        if (phase == Phase.CLOSED) return emptyList()
        serviceConnected = false
        sessionId = null
        taskActive = false
        clearEarlyCallbacks()
        phase = Phase.FAILED
        return listOf(Action.ClearLocalSurface)
    }

    /** 记录 createSession 返回值；Surface、task 和 error callback 均可能更早到达。 */
    fun onCreateReturned(requestGeneration: Long, createdId: Long): List<Action> {
        require(createdId > 0L) { "AppEmbed session id must be positive" }
        if (phase == Phase.CLOSED || !surfaceAvailable || requestGeneration != surfaceGeneration) {
            return listOf(Action.ReleaseRemote(createdId))
        }
        check(sessionId == null) { "AppEmbed already owns session $sessionId" }
        sessionId = createdId
        if (createdId in earlyTaskRemovedIds || createdId in earlyFailedIds) {
            sessionId = null
            taskActive = false
            phase = Phase.FAILED
            clearEarlyCallbacks()
            return listOf(Action.ClearLocalSurface, Action.ReleaseRemote(createdId))
        }

        val surfaceReady = createdId in earlyReadyIds
        val taskCreated = createdId in earlyTaskCreatedIds
        taskActive = taskCreated
        nativeTaskVisible = earlyTaskVisibility[createdId]
        phase = if (surfaceReady) Phase.ATTACHED else Phase.CREATING
        clearEarlyCallbacks()
        return buildList {
            if (surfaceReady) add(Action.AttachSurfacePackage(createdId))
            if (taskCreated) add(Action.TaskBecameActive(createdId))
        }
    }

    /** createSession Binder 调用失败时，只终止仍属于同一 Surface 代际的请求。 */
    fun onCreateFailed(requestGeneration: Long): List<Action> {
        if (phase == Phase.CLOSED || requestGeneration != surfaceGeneration) return emptyList()
        sessionId = null
        taskActive = false
        clearEarlyCallbacks()
        phase = Phase.FAILED
        return listOf(Action.ClearLocalSurface)
    }

    /** SurfacePackage 只允许绑定当前 session；其余回调要求释放包及旧 session。 */
    fun onSurfaceReady(callbackGeneration: Long, readyId: Long): List<Action> {
        if (callbackGeneration != surfaceGeneration || phase == Phase.CLOSED || !surfaceAvailable) {
            return listOf(Action.DiscardSurfacePackage, Action.ReleaseRemote(readyId))
        }
        if (phase == Phase.CREATING && sessionId == null) {
            earlyReadyIds += readyId
            return emptyList()
        }
        if (phase == Phase.ATTACHED && sessionId == readyId) {
            return listOf(Action.DiscardSurfacePackage)
        }
        if (phase != Phase.CREATING || sessionId != readyId) {
            return listOf(Action.DiscardSurfacePackage, Action.ReleaseRemote(readyId))
        }
        phase = Phase.ATTACHED
        return listOf(Action.AttachSurfacePackage(readyId))
    }

    /** TaskCreated 与 createSession 返回可并发；早到事件缓存到同一 callback 代际。 */
    fun onTaskCreated(callbackGeneration: Long, createdId: Long): List<Action> {
        if (callbackGeneration != surfaceGeneration || phase == Phase.CLOSED) {
            return listOf(Action.ReleaseRemote(createdId))
        }
        if (phase == Phase.CREATING && sessionId == null) {
            if (createdId !in earlyTaskRemovedIds) earlyTaskCreatedIds += createdId
            return emptyList()
        }
        if (sessionId != createdId || (phase != Phase.CREATING && phase != Phase.ATTACHED)) {
            return listOf(Action.ReleaseRemote(createdId))
        }
        if (taskActive) return emptyList()
        taskActive = true
        return listOf(Action.TaskBecameActive(createdId))
    }

    /** Accepts visibility only from this generation and its issued session. */
    fun onTaskVisibilityChanged(callbackGeneration: Long, id: Long, visible: Boolean) {
        if (callbackGeneration != surfaceGeneration || phase == Phase.CLOSED) return
        if (phase == Phase.CREATING && sessionId == null) {
            earlyTaskVisibility[id] = visible
        } else if (sessionId == id) {
            nativeTaskVisible = visible
        }
    }

    /** A deliberate return to a failed card starts a new generation, without frame-driven retries. */
    fun onHostBecameVisible(): List<Action> =
        if (phase == Phase.FAILED && surfaceAvailable) onSurfaceAvailable() else emptyList()

    /** A hidden/paused card ends its activation even when Android retains the same Surface. */
    fun onHostHidden(): List<Action> =
        if (surfaceAvailable && phase != Phase.CLOSED) onSurfaceDestroyed() else emptyList()

    /** TaskRemovalStarted 终止当前 session；早到事件会压过同 id 的 TaskCreated。 */
    fun onTaskRemovalStarted(callbackGeneration: Long, removedId: Long): List<Action> {
        if (callbackGeneration != surfaceGeneration || phase == Phase.CLOSED) {
            return listOf(Action.ReleaseRemote(removedId))
        }
        if (phase == Phase.CREATING && sessionId == null) {
            earlyTaskCreatedIds.remove(removedId)
            earlyTaskRemovedIds += removedId
            return emptyList()
        }
        if (sessionId != removedId) return listOf(Action.ReleaseRemote(removedId))
        sessionId = null
        taskActive = false
        phase = Phase.FAILED
        clearEarlyCallbacks()
        return listOf(Action.ClearLocalSurface, Action.ReleaseRemote(removedId))
    }

    /** Surface 销毁时清理本地包和当前远端 session。 */
    fun onSurfaceDestroyed(): List<Action> {
        if (phase == Phase.CLOSED) return emptyList()
        surfaceAvailable = false
        surfaceGeneration++
        clearEarlyCallbacks()
        val oldId = sessionId
        sessionId = null
        taskActive = false
        phase = Phase.DETACHED
        return buildList {
            add(Action.ClearLocalSurface)
            oldId?.let { add(Action.ReleaseRemote(it)) }
        }
    }

    /** Binder 死亡使远端 session 隐式失效；保留 Surface 时只发起一次新连接。 */
    fun onRemoteDied(): List<Action> {
        if (phase == Phase.CLOSED) return emptyList()
        serviceConnected = false
        sessionId = null
        taskActive = false
        surfaceGeneration++
        clearEarlyCallbacks()
        phase = if (surfaceAvailable) Phase.WAITING_FOR_SERVICE else Phase.DETACHED
        return buildList {
            add(Action.ClearLocalSurface)
            if (surfaceAvailable) add(Action.Connect)
        }
    }

    /** 当前远端 session 报错后进入 FAILED，并释放双方资源。 */
    fun onRemoteError(callbackGeneration: Long, failedId: Long): List<Action> {
        if (callbackGeneration != surfaceGeneration || phase == Phase.CLOSED) {
            return listOf(Action.ReleaseRemote(failedId))
        }
        if (phase == Phase.CREATING && sessionId == null) {
            earlyFailedIds += failedId
            earlyReadyIds.remove(failedId)
            earlyTaskCreatedIds.remove(failedId)
            return emptyList()
        }
        if (sessionId != failedId) return listOf(Action.ReleaseRemote(failedId))
        sessionId = null
        taskActive = false
        clearEarlyCallbacks()
        phase = Phase.FAILED
        return listOf(Action.ClearLocalSurface, Action.ReleaseRemote(failedId))
    }

    /** 永久关闭元素；关闭后任何新 Surface 事件均 fail-fast。 */
    fun close(): List<Action> {
        if (phase == Phase.CLOSED) return emptyList()
        surfaceAvailable = false
        serviceConnected = false
        surfaceGeneration++
        clearEarlyCallbacks()
        val oldId = sessionId
        sessionId = null
        taskActive = false
        phase = Phase.CLOSED
        return buildList {
            add(Action.ClearLocalSurface)
            oldId?.let { add(Action.ReleaseRemote(it)) }
        }
    }

    /** 清空所有只在 createSession 同步返回前有效的 callback 缓存。 */
    private fun clearEarlyCallbacks() {
        earlyReadyIds.clear()
        earlyFailedIds.clear()
        earlyTaskCreatedIds.clear()
        earlyTaskRemovedIds.clear()
        earlyTaskVisibility.clear()
    }
}
