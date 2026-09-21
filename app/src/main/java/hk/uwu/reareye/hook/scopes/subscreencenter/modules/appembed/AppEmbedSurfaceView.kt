package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.content.Context
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.MotionEvent
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.internal.appembed.IAppEmbedCallback
import hk.uwu.reareye.internal.appembed.IAppEmbedService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * MAML 中承载 SystemUI 远端 TaskView 的本地 SurfaceView。
 *
 * View 只管理同屏 SurfacePackage、绝对任务 bounds、输入区域和 Binder 会话；Activity 的
 * Task 生命周期完全由 SystemUI 中已有的 ShellTaskOrganizer/TaskViewFactory 管理。
 */
internal class AppEmbedSurfaceView(
    context: Context,
    /** 创建此 View 前已完成严格校验的 XML 配置。 */
    private val spec: AppEmbedSpec,
) : SurfaceView(context), SurfaceHolder.Callback,
    ViewTreeObserver.OnGlobalLayoutListener,
    ViewTreeObserver.OnScrollChangedListener,
    ViewTreeObserver.OnPreDrawListener,
    AutoCloseable {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 串行执行所有可能跨进程阻塞的 service 调用，保持 create/resize/release 顺序。 */
    private val remoteExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "REAREye-AppEmbed-${spec.packageName}").apply { isDaemon = true }
    }

    /** 每个元素独有的死亡 token，SystemUI 以它作为整组远端资源的 owner。 */
    private val ownerToken: IBinder = Binder()

    /** 处理 Binder 与 Surface 回调乱序的唯一状态源。 */
    private val sessionState = AppEmbedSessionState()

    /** 异步 SystemUI broker 连接。 */
    private val connector = AppEmbedServiceConnector(
        context = context,
        mainHandler = mainHandler,
        onConnected = ::onServiceConnected,
        onDisconnected = ::onServiceDisconnected,
    )

    /** 当前 session 对应的 broker，用于 broker 被替换后仍向正确实例发送 release。 */
    private var sessionService: IAppEmbedService? = null

    /** 当前 holder Surface 是否已向状态机报告。 */
    private var surfaceReported = false

    /** 当前绑定到 SurfaceView 的包；所有权交给 SurfaceView 后只通过 clear API 释放。 */
    private var hasLocalSurfacePackage = false

    /** createSession 返回前到达的 SurfacePackage，按 callback 代际与 session id 暂存。 */
    private val earlySurfacePackages =
        LinkedHashMap<CallbackKey, SurfaceControlViewHost.SurfacePackage>()

    /** 上一次发给远端的实际 View 布局快照，避免同一帧重复 resize。 */
    private var lastLayout: LayoutSnapshot? = null

    /** 永久 close 后拒绝所有 View 和 Binder 回调。 */
    private var closed = false

    /** 当前 holder 代际已经因不安全布局失败，等待下次 Surface 重建后再尝试。 */
    private var layoutFailed = false

    /** Tracks deliberate card re-entry, independently of Surface recreation and per-frame layout. */
    private var lastHostContentVisible = false

    /** MAML's renderer may pause while the underlying Android View remains visible and valid. */
    private var mamlActive = true

    /** Card entry animations must settle before launching an Activity into their moving bounds. */
    private val launchLayout = AppEmbedLaunchLayout<LayoutSnapshot>()
    private val checkLaunchLayout = Runnable { synchronizeSurface() }

    private fun resetLaunchLayout() {
        mainHandler.removeCallbacks(checkLaunchLayout)
        launchLayout.reset()
    }

    /** Serializes renderer lifecycle callbacks with Surface and Binder callbacks on the UI thread. */
    fun setMamlActive(active: Boolean) {
        if (Looper.myLooper() != mainHandler.looper) {
            if (!mainHandler.post { setMamlActive(active) }) {
                YLog.error("[AppEmbedSurface] main Handler rejected MAML activation=$active")
            }
            return
        }
        if (closed) return
        mamlActive = active
        if (!active) suspendHostSession()
        synchronizeSurface()
    }

    /** Ends one card activation immediately, so a retained Surface can start a fresh session later. */
    private fun suspendHostSession() {
        resetLaunchLayout()
        lastHostContentVisible = false
        lastLayout = null
        layoutFailed = false
        if (!surfaceReported) return
        surfaceReported = false
        val service = sessionService
        executeActions(sessionState.onHostHidden(), releaseService = service)
        sessionService = null
        clearEarlySurfacePackages()
        YLog.debug("[AppEmbedSurface] card deactivated; next activation will create a new session")
    }

    init {
        holder.addCallback(this)
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        contentDescription = "Embedded ${spec.packageName}"
    }

    /** holder 首次拥有 Surface 时开始 broker 连接和 session 创建。 */
    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronizeSurface()
    }

    /** 尺寸变化时同步远端 ViewHost 与 task input bounds。 */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronizeSurface()
    }

    /** Surface 销毁时立即取消当前代 session，晚到回调由代际检查回收。 */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (closed) return
        resetLaunchLayout()
        layoutFailed = false
        lastLayout = null
        if (!surfaceReported) {
            clearEarlySurfacePackages()
            return
        }
        surfaceReported = false
        val service = sessionService
        executeActions(sessionState.onSurfaceDestroyed(), releaseService = service)
        sessionService = null
        clearEarlySurfacePackages()
    }

    /** View 移动、父布局变化或旋转后更新任务绝对屏幕位置。 */
    override fun onGlobalLayout() {
        synchronizeSurface()
    }

    /** MAML 容器滚动后更新任务绝对屏幕位置。 */
    override fun onScrollChanged() {
        synchronizeSurface()
    }

    /** MAML translation 动画只触发绘制时，逐帧同步有符号屏幕坐标。 */
    override fun onPreDraw(): Boolean {
        synchronizeSurface()
        return true
    }

    /** 挂到窗口后注册位置监听与宿主输入区域打洞。 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(this)
        viewTreeObserver.addOnScrollChangedListener(this)
        viewTreeObserver.addOnPreDrawListener(this)
        synchronizeSurface()
    }

    /** 离开窗口时移除输入洞和远端 session，避免不可见 task 留在 organizer 中。 */
    override fun onDetachedFromWindow() {
        resetLaunchLayout()
        removePositionListeners()
        if (surfaceReported) {
            surfaceReported = false
            layoutFailed = false
            lastLayout = null
            val service = sessionService
            executeActions(sessionState.onSurfaceDestroyed(), releaseService = service)
            sessionService = null
            clearEarlySurfacePackages()
        } else {
            layoutFailed = false
            lastLayout = null
            clearEarlySurfacePackages()
        }
        super.onDetachedFromWindow()
    }

    /** 将窗口可见性传给远端 TaskView，阻止不可见元素继续显示 task leash。 */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        synchronizeSurface()
    }

    /** MAML can hide a card without changing its containing window's visibility. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        synchronizeSurface()
    }

    /** Never transfer a gesture to the remote SurfacePackage; MAML retains the whole stream. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    /** A fully transparent ancestor also suspends this card's background task. */
    private fun hasVisibleAlpha(): Boolean {
        if (!isShown) return false
        var current: View? = this
        while (current != null) {
            if (current.alpha <= 0f) return false
            current = current.parent as? View
        }
        return true
    }

    /** 构造并校验当前同屏布局；首次创建 session，随后只发送实际变化。 */
    private fun synchronizeSurface() {
        if (closed) return
        val hostVisible =
            mamlActive && isAttachedToWindow && windowVisibility == View.VISIBLE && hasVisibleAlpha() &&
                    getGlobalVisibleRect(Rect())
        // Consume hiding before checking Surface validity: hiding often invalidates the Surface first.
        if (!hostVisible) {
            suspendHostSession()
            return
        }
        if (layoutFailed || !holder.surface.isValid || width <= 0 || height <= 0) return
        val becameVisible = !lastHostContentVisible
        if (hostVisible != lastHostContentVisible) {
            lastHostContentVisible = hostVisible
            val id = sessionState.sessionId
            val service = sessionService
            if (id != null && service != null) {
                submitRemote("setVisible") { service.setVisible(id, hostVisible) }
            }
        }
        if (becameVisible) {
            executeActions(sessionState.onHostBecameVisible(), releaseService = sessionService)
        }
        val snapshot = try {
            captureLayoutSnapshot()
        } catch (error: Throwable) {
            layoutFailed = true
            YLog.error("[AppEmbedSurface] unsafe or invalid SurfaceView layout", error)
            val id = sessionState.sessionId
            val service = sessionService
            if (id != null) {
                executeActions(
                    sessionState.onRemoteError(sessionState.surfaceGeneration, id),
                    releaseService = service,
                )
            } else if (surfaceReported) {
                executeActions(sessionState.onCreateFailed(sessionState.surfaceGeneration))
            }
            return
        }
        if (!surfaceReported) {
            val remaining = launchLayout.remainingMillis(snapshot, SystemClock.uptimeMillis())
            mainHandler.removeCallbacks(checkLaunchLayout)
            if (remaining > 0L) {
                if (!mainHandler.postDelayed(checkLaunchLayout, remaining)) {
                    YLog.error("[AppEmbedSurface] main Handler rejected stable-layout check")
                }
                return
            }
            surfaceReported = true
            lastLayout = snapshot
            executeActions(sessionState.onSurfaceAvailable())
            return
        }
        if (snapshot == lastLayout) return
        lastLayout = snapshot
        val id = sessionState.sessionId ?: return
        val service = sessionService ?: return
        submitRemote("resize") {
            service.resize(id, snapshot.widthPx, snapshot.heightPx, snapshot.taskBoundsOnScreen)
        }
    }

    /** 读取 hostToken、display、物理尺寸与绝对 bounds，并拒绝无法保持输入对齐的覆盖值。 */
    private fun captureLayoutSnapshot(): LayoutSnapshot {
        val hostToken = hostToken ?: error("AppEmbed SurfaceView has no host token")
        val currentDisplay =
            display ?: error("AppEmbed SurfaceView is not associated with a display")
        requireNoScaledOrRotatedAncestors()
        val screenLocation = IntArray(2).also(::getLocationOnScreen)
        val actualBounds = Rect(
            screenLocation[0],
            screenLocation[1],
            screenLocation[0] + width,
            screenLocation[1] + height,
        )
        require(!actualBounds.isEmpty) { "AppEmbed SurfaceView has empty screen bounds" }
        val taskBounds = spec.resolveTaskBounds(actualBounds)
        // TaskView content follows the resolved task bounds, not the host View rectangle. This keeps
        // remote surface size, task configuration and input bounds coherent for ratio/inset/override layouts.
        val contentSize = spec.resolveContentSize(taskBounds.width(), taskBounds.height())
        return LayoutSnapshot(
            hostToken = hostToken,
            displayId = currentDisplay.displayId,
            widthPx = contentSize.widthPx,
            heightPx = contentSize.heightPx,
            densityDpi = spec.densityDpi ?: 0,
            taskBoundsOnScreen = taskBounds,
        )
    }

    /** 将纯状态机动作映射为 Android/Binder 副作用。 */
    private fun executeActions(
        actions: List<AppEmbedSessionState.Action>,
        callbackPackage: SurfaceControlViewHost.SurfacePackage? = null,
        releaseService: IAppEmbedService? = sessionService,
    ) {
        var packageConsumed = false
        try {
            for (action in actions) {
                when (action) {
                    AppEmbedSessionState.Action.Connect -> connector.connect()
                    is AppEmbedSessionState.Action.Create -> createRemoteSession(action.surfaceGeneration)
                    is AppEmbedSessionState.Action.AttachSurfacePackage -> {
                        val surfacePackage = callbackPackage
                            ?: error("AttachSurfacePackage requires callback package")
                        clearLocalSurfacePackage()
                        setChildSurfacePackage(surfacePackage)
                        hasLocalSurfacePackage = true
                        packageConsumed = true
                    }

                    AppEmbedSessionState.Action.DiscardSurfacePackage -> {
                        packageConsumed = true
                        callbackPackage?.let {
                            releaseSurfacePackage(it, "discard callback SurfacePackage")
                        }
                    }

                    is AppEmbedSessionState.Action.ReleaseRemote -> {
                        val service = releaseService
                        if (service == null) {
                            YLog.warn("[AppEmbedSurface] cannot release session=${action.sessionId}: broker unavailable")
                        } else {
                            submitRemote("release") { service.release(action.sessionId) }
                        }
                    }

                    is AppEmbedSessionState.Action.TaskBecameActive -> {
                        YLog.debug("[AppEmbedSurface] display-only task active session=${action.sessionId}")
                    }

                    AppEmbedSessionState.Action.ClearLocalSurface -> clearLocalSurfacePackage()
                }
            }
        } finally {
            if (callbackPackage != null && !packageConsumed) {
                releaseSurfacePackage(callbackPackage, "unconsumed callback SurfacePackage")
            }
        }
    }

    /** 读取当前布局并在远端串行线程创建一个带专属 callback 代际的 session。 */
    private fun createRemoteSession(requestGeneration: Long) {
        val service = connector.serviceOrNull()
        if (service == null) {
            YLog.error("[AppEmbedSurface] create requested without a connected broker")
            executeActions(sessionState.onCreateFailed(requestGeneration))
            return
        }
        val snapshot = lastLayout
            ?: run {
                YLog.error("[AppEmbedSurface] create requested without a layout snapshot")
                executeActions(sessionState.onCreateFailed(requestGeneration))
                return
            }
        val callback = createRemoteCallback(requestGeneration, service)
        submitRemote("createSession", onFailure = {
            mainHandler.post {
                executeActions(
                    sessionState.onCreateFailed(requestGeneration),
                    releaseService = service
                )
            }
        }) {
            val id = service.createSession(
                ownerToken,
                snapshot.hostToken,
                snapshot.displayId,
                snapshot.widthPx,
                snapshot.heightPx,
                snapshot.densityDpi,
                snapshot.taskBoundsOnScreen,
                spec.buildLaunchIntent(),
                false,
                callback,
            )
            mainHandler.post {
                if (closed) return@post
                val actions = sessionState.onCreateReturned(requestGeneration, id)
                if (sessionState.sessionId == id) sessionService = service
                val key = CallbackKey(requestGeneration, id)
                val earlyPackage = earlySurfacePackages.remove(key)
                releaseUnexpectedEarlyPackages(requestGeneration, id, service)
                executeActions(actions, callbackPackage = earlyPackage, releaseService = service)
            }
        }
    }

    /** 创建绑定 service 与 Surface 代际的 callback，防止 broker 重启后 id 复用误绑定。 */
    private fun createRemoteCallback(
        callbackGeneration: Long,
        service: IAppEmbedService,
    ): IAppEmbedCallback = object : IAppEmbedCallback.Stub() {
        override fun onSurfaceReady(sessionId: Long, surfacePackageBundle: Bundle?) {
            val surfacePackage = try {
                extractSurfacePackage(surfacePackageBundle)
            } catch (error: Throwable) {
                mainHandler.post {
                    handleCallbackFailure(
                        operation = "decode SurfacePackage",
                        callbackGeneration = callbackGeneration,
                        callbackSessionId = sessionId,
                        service = service,
                        error = error,
                    )
                }
                return
            }
            if (surfacePackage == null) {
                mainHandler.post {
                    handleCallbackFailure(
                        operation = "receive SurfacePackage",
                        callbackGeneration = callbackGeneration,
                        callbackSessionId = sessionId,
                        service = service,
                        error = IllegalStateException(
                            "Session $sessionId returned no SurfacePackage"
                        ),
                    )
                }
                return
            }
            mainHandler.post {
                if (closed) {
                    releaseSurfacePackage(surfacePackage, "callback after close")
                    return@post
                }
                var packageHandedOff = false
                try {
                    val actions = sessionState.onSurfaceReady(callbackGeneration, sessionId)
                    if (actions.isEmpty() &&
                        callbackGeneration == sessionState.surfaceGeneration &&
                        sessionState.phase == AppEmbedSessionState.Phase.CREATING &&
                        sessionState.sessionId == null
                    ) {
                        val old = earlySurfacePackages.put(
                            CallbackKey(callbackGeneration, sessionId),
                            surfacePackage,
                        )
                        packageHandedOff = true
                        old?.let { releaseSurfacePackage(it, "replaced early SurfacePackage") }
                    } else {
                        packageHandedOff = true
                        executeActions(
                            actions,
                            callbackPackage = surfacePackage,
                            releaseService = service,
                        )
                    }
                } catch (error: Throwable) {
                    if (!packageHandedOff) {
                        releaseSurfacePackage(surfacePackage, "failed SurfacePackage callback")
                    }
                    handleCallbackFailure(
                        operation = "handle SurfacePackage",
                        callbackGeneration = callbackGeneration,
                        callbackSessionId = sessionId,
                        service = service,
                        error = error,
                    )
                }
            }
        }

        override fun onTaskCreated(sessionId: Long, taskId: Int) {
            mainHandler.post {
                runCallbackBoundary("handle task created", callbackGeneration, sessionId, service) {
                    val actions = sessionState.onTaskCreated(callbackGeneration, sessionId)
                    if (actions.any { it is AppEmbedSessionState.Action.TaskBecameActive }) {
                        YLog.info("[AppEmbedSurface] task created session=$sessionId task=$taskId")
                    }
                    executeActions(actions, releaseService = service)
                }
            }
        }

        override fun onTaskRemovalStarted(sessionId: Long) {
            mainHandler.post {
                runCallbackBoundary("handle task removal", callbackGeneration, sessionId, service) {
                    val actions = sessionState.onTaskRemovalStarted(callbackGeneration, sessionId)
                    if (actions.any { it === AppEmbedSessionState.Action.ClearLocalSurface }) {
                        YLog.info("[AppEmbedSurface] task removal started session=$sessionId")
                    }
                    executeActions(actions, releaseService = service)
                }
            }
        }

        override fun onTaskVisibilityChanged(sessionId: Long, visible: Boolean) {
            mainHandler.post {
                runCallbackBoundary(
                    "handle task visibility",
                    callbackGeneration,
                    sessionId,
                    service
                ) {
                    sessionState.onTaskVisibilityChanged(callbackGeneration, sessionId, visible)
                }
            }
        }

        override fun onError(sessionId: Long, errorCode: Int, message: String?) {
            YLog.error(
                "[AppEmbedSurface] remote error session=$sessionId code=$errorCode message=${message.orEmpty()}"
            )
            mainHandler.post {
                runCallbackBoundary("handle remote error", callbackGeneration, sessionId, service) {
                    earlySurfacePackages.remove(CallbackKey(callbackGeneration, sessionId))
                        ?.let { releaseSurfacePackage(it, "remote error") }
                    executeActions(
                        sessionState.onRemoteError(callbackGeneration, sessionId),
                        releaseService = service,
                    )
                }
            }
        }
    }

    /** Runs one Binder callback on the owning thread and terminates its session on any fault. */
    private fun runCallbackBoundary(
        operation: String,
        callbackGeneration: Long,
        callbackSessionId: Long,
        service: IAppEmbedService,
        block: () -> Unit,
    ) {
        if (closed) return
        try {
            block()
        } catch (error: Throwable) {
            handleCallbackFailure(
                operation = operation,
                callbackGeneration = callbackGeneration,
                callbackSessionId = callbackSessionId,
                service = service,
                error = error,
            )
        }
    }

    /** Logs one callback fault, releases cached packages and terminates the matching generation. */
    private fun handleCallbackFailure(
        operation: String,
        callbackGeneration: Long,
        callbackSessionId: Long,
        service: IAppEmbedService,
        error: Throwable,
    ) {
        YLog.error(
            "[AppEmbedSurface] $operation failed generation=$callbackGeneration " +
                    "session=$callbackSessionId",
            error,
        )
        earlySurfacePackages.remove(CallbackKey(callbackGeneration, callbackSessionId))
            ?.let { releaseSurfacePackage(it, "$operation failure") }
        if (closed) return
        val actions = try {
            sessionState.onRemoteError(callbackGeneration, callbackSessionId)
        } catch (stateError: Throwable) {
            YLog.error("[AppEmbedSurface] callback failure state transition failed", stateError)
            clearLocalSurfacePackage()
            submitRemote("release failed callback session") {
                service.release(callbackSessionId)
            }
            return
        }
        try {
            executeActions(actions, releaseService = service)
        } catch (cleanupError: Throwable) {
            YLog.error("[AppEmbedSurface] callback failure cleanup failed", cleanupError)
            clearLocalSurfacePackage()
            submitRemote("release failed callback session") {
                service.release(callbackSessionId)
            }
        }
    }

    /** Fails local setup without allowing an input or Surface exception to crash the host. */
    private fun handleLocalFailure(operation: String, error: Throwable) {
        YLog.error("[AppEmbedSurface] $operation failed", error)
        val actions = try {
            val id = sessionState.sessionId
            if (id == null) {
                sessionState.onCreateFailed(sessionState.surfaceGeneration)
            } else {
                sessionState.onRemoteError(sessionState.surfaceGeneration, id)
            }
        } catch (stateError: Throwable) {
            YLog.error("[AppEmbedSurface] local failure state transition failed", stateError)
            emptyList()
        }
        try {
            executeActions(actions)
        } catch (cleanupError: Throwable) {
            YLog.error("[AppEmbedSurface] local failure cleanup failed", cleanupError)
            clearLocalSurfacePackage()
        }
    }

    /** 解析 Binder Bundle 中转移所有权的 SurfacePackage。 */
    private fun extractSurfacePackage(bundle: Bundle?): SurfaceControlViewHost.SurfacePackage? {
        if (bundle == null) return null
        bundle.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader
        return bundle.getParcelable(
            AppEmbedContract.SURFACE_PACKAGE_KEY,
            SurfaceControlViewHost.SurfacePackage::class.java,
        )
    }

    /** service 接通后驱动当前 Surface 代际继续创建。 */
    private fun onServiceConnected(service: IAppEmbedService) {
        if (closed) return
        YLog.info("[AppEmbedSurface] SystemUI broker connected pkg=${spec.packageName}")
        executeActions(sessionState.onServiceConnected(), releaseService = service)
    }

    /** service 死亡、换代或连接失败时清理本地包；前两者复用可重连状态迁移。 */
    private fun onServiceDisconnected(reason: String) {
        if (closed) return
        if (reason == "broker_replaced") {
            YLog.info("[AppEmbedSurface] SystemUI broker replaced pkg=${spec.packageName}")
        } else {
            YLog.error("[AppEmbedSurface] SystemUI broker disconnected reason=$reason")
        }
        sessionService = null
        clearEarlySurfacePackages()
        val actions = if (reason == "remote_died" || reason == "broker_replaced") {
            sessionState.onRemoteDied()
        } else {
            sessionState.onConnectionFailed()
        }
        executeActions(actions, releaseService = null)
    }

    /** 将远端调用放入单线程队列并完整记录异常。 */
    private fun submitRemote(
        operation: String,
        onFailure: ((Throwable) -> Unit)? = null,
        block: () -> Unit,
    ) {
        try {
            remoteExecutor.execute {
                try {
                    block()
                } catch (error: Throwable) {
                    if (error !is DeadObjectException) {
                        YLog.error("[AppEmbedSurface] remote $operation failed", error)
                    }
                    onFailure?.invoke(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            YLog.error("[AppEmbedSurface] remote $operation rejected after close", error)
            onFailure?.invoke(error)
        }
    }

    /** 只用公开 clear API 解除并释放 SurfaceView 当前持有的 SurfacePackage。 */
    private fun clearLocalSurfacePackage() {
        if (!hasLocalSurfacePackage) return
        hasLocalSurfacePackage = false
        try {
            clearChildSurfacePackage()
        } catch (error: Throwable) {
            YLog.error("[AppEmbedSurface] child SurfacePackage clear failed", error)
        }
    }

    /**
     * TaskView input bounds 不支持宿主层级的缩放或旋转；平移由 getLocationOnScreen 覆盖。
     */
    private fun requireNoScaledOrRotatedAncestors() {
        var current: View? = this
        while (current != null) {
            require(
                current.scaleX == 1f && current.scaleY == 1f &&
                        current.rotation == 0f && current.rotationX == 0f && current.rotationY == 0f
            ) {
                "AppEmbed does not support scaled/rotated ancestor ${current.javaClass.name}"
            }
            val parent: ViewParent? = current.parent
            current = parent as? View
        }
    }

    /** 释放所有尚未交给 SurfaceView 的早到 SurfacePackage。 */
    private fun clearEarlySurfacePackages() {
        earlySurfacePackages.values.forEach {
            releaseSurfacePackage(it, "clear early SurfacePackage")
        }
        earlySurfacePackages.clear()
    }

    /** create 返回后清理同代际中不属于实际返回 id 的异常 callback。 */
    private fun releaseUnexpectedEarlyPackages(
        generation: Long,
        acceptedSessionId: Long,
        service: IAppEmbedService,
    ) {
        val staleKeys = earlySurfacePackages.keys.filter { key ->
            key.generation == generation && key.sessionId != acceptedSessionId
        }
        for (key in staleKeys) {
            earlySurfacePackages.remove(key)?.let {
                releaseSurfacePackage(it, "unexpected early SurfacePackage")
            }
            submitRemote("release unexpected early session") { service.release(key.sessionId) }
        }
    }

    /** Releases a transferred SurfacePackage while preserving cleanup progress after failure. */
    private fun releaseSurfacePackage(
        surfacePackage: SurfaceControlViewHost.SurfacePackage,
        reason: String,
    ) {
        try {
            surfacePackage.release()
        } catch (error: Throwable) {
            YLog.error("[AppEmbedSurface] $reason release failed", error)
        }
    }

    /** 从当前仍存活的 ViewTreeObserver 移除位置监听。 */
    private fun removePositionListeners() {
        val observer = viewTreeObserver
        if (!observer.isAlive) return
        observer.removeOnGlobalLayoutListener(this)
        observer.removeOnScrollChangedListener(this)
        observer.removeOnPreDrawListener(this)
    }

    /** 永久释放本地、Binder、线程和输入资源；允许重复调用。 */
    override fun close() {
        if (Looper.myLooper() != mainHandler.looper) {
            mainHandler.post(::close)
            return
        }
        if (closed) return
        closed = true
        resetLaunchLayout()
        runCleanupStep("remove position listeners") { removePositionListeners() }
        val capability = try {
            connector.serviceOrNull() ?: sessionService
        } catch (error: Throwable) {
            YLog.error("[AppEmbedSurface] read capability during close failed", error)
            sessionService
        }
        runCleanupStep("close session state") {
            executeActions(sessionState.close(), releaseService = sessionService)
        }
        runCleanupStep("clear early SurfacePackages") { clearEarlySurfacePackages() }
        sessionService = null
        runCleanupStep("close service connector") { connector.close() }
        runCleanupStep("remove Surface callback") { holder.removeCallback(this) }
        if (capability != null) {
            submitRemote("dispose capability") { capability.dispose() }
        }
        runCleanupStep("shutdown remote executor") { remoteExecutor.shutdown() }
    }

    /** Runs one close step without preventing the remaining resources from being disposed. */
    private fun runCleanupStep(name: String, step: () -> Unit) {
        try {
            step()
        } catch (error: Throwable) {
            YLog.error("[AppEmbedSurface] close step failed: $name", error)
        }
    }

    /** create/resize 所需的同一时刻布局数据。 */
    private data class LayoutSnapshot(
        /** SurfaceControlViewHost 输入转移需要的宿主 token。 */
        val hostToken: IBinder,
        /** SurfaceView 所在物理 display id。 */
        val displayId: Int,
        /** 远端 ViewHost 宽度。 */
        val widthPx: Int,
        /** 远端 ViewHost 高度。 */
        val heightPx: Int,
        /** 任务配置密度；0 表示继承当前物理显示，正数表示显式覆盖。 */
        val densityDpi: Int,
        /** 与 SurfaceView 完全相同的绝对屏幕 task bounds。 */
        val taskBoundsOnScreen: Rect,
    )

    /** 唯一标识一个 callback 所属的本地 Surface 代际与远端 session。 */
    private data class CallbackKey(
        /** callback 创建时的 Surface 代际。 */
        val generation: Long,
        /** SystemUI 返回的 session id。 */
        val sessionId: Long,
    )
}
