package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.view.SurfaceControl
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.hook.core.YukiBaseHooker
import java.util.IdentityHashMap

/**
 * Starts a generation-owned broker from SystemUI's existing WM Shell component graph.
 *
 * Android 17 exposes its singleton TaskView factory through `HasWMComponent`. Each cold start and
 * hot-reload lifecycle replay resolves that current ROM object again, while TaskView behavior hooks
 * remain scoped to this module generation.
 */
internal class SystemUiAppEmbedHook : YukiBaseHooker() {
    /** Broker owned exclusively by this hook generation. */
    @Volatile
    private var broker: SystemUiAppEmbedBroker? = null

    private val launchTransitions = AppEmbedLaunchTransitionTracker(OPEN_TRANSITION_TYPE)

    override fun onHook() {
        loadApp(SYSTEM_UI_PACKAGE) {
            withProcess(SYSTEM_UI_PROCESS) {
                installTaskViewHooks()
                onAppLifecycle {
                    onCreate {
                        val context = appContext
                            ?: error("SystemUI Application context is unavailable at onCreate")
                        startBroker(context.applicationContext ?: context)
                    }
                    onTerminate {
                        closeBroker(AppEmbedReleaseReason.MODULE_RELOAD)
                    }
                }
            }
        }
    }

    /** Performs a read-only main-thread snapshot before the global reload transaction prepares. */
    override fun onReloadingPreflight(): Boolean {
        return broker?.canPrepareReload() ?: true
    }

    /** Atomically fences new host creation and task launches after rechecking cleanup safety. */
    override fun onReloadingPrepare(): Boolean {
        return broker?.prepareReload() ?: true
    }

    /** Reopens the old broker and resumes deferred work when any target aborts reload preparation. */
    override fun onReloadingPreparationRolledBack() {
        broker?.rollbackReloadPreparation()
    }

    /** Closes the prepared broker before this generation's behavior hooks are uninstalled. */
    override fun onReloading(): Boolean {
        val closed = closeBroker(AppEmbedReleaseReason.MODULE_RELOAD)
        launchTransitions.clear()
        return closed
    }

    /** Installs the exact Android 17 TaskView behavior and launch-transition hooks. */
    private fun installTaskViewHooks() {
        val taskViewTransitionsClass = TASK_VIEW_TRANSITIONS.toClass()
        val taskViewClass = TASK_VIEW.toClass()
        val taskViewTaskControllerClass = TASK_VIEW_TASK_CONTROLLER.toClass()
        val pendingTransitionClass = TASK_VIEW_PENDING_TRANSITION.toClass()
        val windowContainerTransactionClass = WINDOW_CONTAINER_TRANSACTION.toClass()
        val transitionInfoClass = TRANSITION_INFO.toClass()
        val transitionDispatchStateClass = TRANSITION_DISPATCH_STATE.toClass()
        val surfaceTransactionClass = SURFACE_TRANSACTION.toClass()
        val transitionFinishCallbackClass = TRANSITION_FINISH_CALLBACK.toClass()

        installRequiredCaptureHook("TaskView.getCurrentBoundsOnScreen") {
            taskViewClass.resolve().firstMethod {
                name = "getCurrentBoundsOnScreen"
                parameterCount = 0
                returnType = Rect::class.java
            }.hook().after {
                if (!isGenerationActive) return@after
                val authoritative = broker
                    ?.authoritativeBoundsForTaskView(instance)
                    ?: return@after
                result = Rect(authoritative)
            }
        }

        installRequiredCaptureHook("TaskViewTransitions.setTaskBounds") {
            taskViewTransitionsClass.resolve().firstMethod {
                name = "setTaskBounds"
                parameterCount = 2
                parameters(taskViewTaskControllerClass, Rect::class.java)
                returnType = Void.TYPE
            }.hook().before {
                if (!isGenerationActive) return@before
                val controller = args(0).any() ?: return@before
                val authoritative = broker
                    ?.authoritativeBoundsForTaskController(controller)
                    ?: return@before
                args(1).set(Rect(authoritative))
            }
        }

        installRequiredCaptureHook("TaskViewTransitions.prepareOpenAnimation") {
            taskViewTransitionsClass.resolve().firstMethod {
                name = "prepareOpenAnimation"
                parameterCount = 7
                parameters(
                    taskViewTaskControllerClass,
                    Boolean::class.javaPrimitiveType!!,
                    surfaceTransactionClass,
                    surfaceTransactionClass,
                    ActivityManager.RunningTaskInfo::class.java,
                    SurfaceControl::class.java,
                    windowContainerTransactionClass,
                )
                returnType = Boolean::class.javaPrimitiveType!!
            }.hook {
                before {
                    if (!isGenerationActive) return@before
                    val controller = args(0).any() ?: return@before
                    val taskInfo =
                        args(4).any() as? ActivityManager.RunningTaskInfo ?: return@before
                    launchTransitions.beginNativePrepare(controller)
                    broker?.prepareTaskDensity(controller, taskInfo)
                }
                after {
                    if (!isGenerationActive) return@after
                    val controller = args(0).any() ?: return@after
                    val taskInfo = args(4).any() as? ActivityManager.RunningTaskInfo ?: return@after
                    val prepareFailure = throwable
                    if (prepareFailure != null) {
                        launchTransitions.abortNativePrepare(controller)
                        broker?.onNativeTaskPrepareFailed(controller, prepareFailure)
                        return@after
                    }
                    broker?.onNativeTaskPrepared(controller, taskInfo)
                    handleLaunchResolution(launchTransitions.completeNativePrepare(controller))
                }
            }
        }

        installRequiredCaptureHook("TaskViewTaskController.setTaskNotFound") {
            taskViewTaskControllerClass.resolve().firstMethod {
                name = "setTaskNotFound"
                parameterCount = 0
                returnType = Void.TYPE
            }.hook().before {
                if (!isGenerationActive ||
                    !launchTransitions.shouldSuppressTaskNotFound(instance) ||
                    broker?.shouldSuppressTaskNotFound(instance) != true
                ) {
                    return@before
                }
                broker?.onTaskNotFoundSuppressed(instance)
                result = null
            }
        }

        installRequiredCaptureHook("TaskViewTaskController.onTaskAppeared") {
            taskViewTaskControllerClass.resolve().firstMethod {
                name = "onTaskAppeared"
                parameterCount = 2
                parameters(ActivityManager.RunningTaskInfo::class.java, SurfaceControl::class.java)
                returnType = Void.TYPE
            }.hook().after {
                if (!isGenerationActive) return@after
                val taskInfo = args(0).any() as? ActivityManager.RunningTaskInfo ?: return@after
                val leash = args(1).any() as? SurfaceControl ?: return@after
                val activeBroker = broker ?: return@after
                if (!activeBroker.ownsTaskController(instance)) return@after
                if (!activeBroker.recordAppearedTaskCandidate(instance, taskInfo)) return@after
                val retainedLeash = activeBroker.retainTaskLeash(instance, leash) ?: return@after
                handleLaunchResolution(
                    launchTransitions.observeTaskAppeared(
                        controller = instance,
                        taskInfo = taskInfo,
                        leash = retainedLeash,
                        releaseLeash = { (it as SurfaceControl).release() },
                    ),
                )
            }
        }

        installRequiredCaptureHook("TaskViewTaskController.onTaskVanished") {
            taskViewTaskControllerClass.resolve().firstMethod {
                name = "onTaskVanished"
                parameterCount = 1
                parameters(ActivityManager.RunningTaskInfo::class.java)
                returnType = Void.TYPE
            }.hook().after {
                if (!isGenerationActive) return@after
                val taskInfo = args(0).any() as? ActivityManager.RunningTaskInfo ?: return@after
                if (broker?.onNativeTaskVanished(instance, taskInfo) == true) {
                    launchTransitions.forgetController(instance)
                }
            }
        }

        installRequiredCaptureHook("TaskViewTransitions.updateTaskViewTaskBounds") {
            taskViewTransitionsClass.resolve().firstMethod {
                name = "updateTaskViewTaskBounds"
                parameterCount = 3
                parameters(
                    windowContainerTransactionClass,
                    ActivityManager.RunningTaskInfo::class.java,
                    Rect::class.java,
                )
                returnType = Void.TYPE
            }.hook().after {
                if (!isGenerationActive) return@after
                val transaction = args(0).any() ?: return@after
                val taskInfo = args(1).any() as? ActivityManager.RunningTaskInfo ?: return@after
                val bounds = args(2).any() as? Rect ?: return@after
                broker?.appendTaskDensity(transaction, taskInfo, Rect(bounds))
            }
        }


        installRequiredCaptureHook("TaskView opening PendingTransition constructor") {
            pendingTransitionClass.resolve().firstConstructor {
                parameterCount = 4
                parameters(
                    Int::class.javaPrimitiveType!!,
                    windowContainerTransactionClass,
                    taskViewTaskControllerClass,
                    IBinder::class.java,
                )
            }.hook().after {
                if (!isGenerationActive || args(0).int() != OPEN_TRANSITION_TYPE) return@after
                val pending = instance
                val controller = args(2).any()
                    ?: error("TaskView opening transition has no controller")
                if (broker?.ownsTaskController(controller) != true) return@after
                launchTransitions.observePending(pending, args(0).int(), controller)
            }
        }

        installRequiredCaptureHook("TaskViewTransitions.findPending(IBinder)") {
            taskViewTransitionsClass.resolve().firstMethod {
                name = "findPending"
                parameterCount = 1
                parameters(IBinder::class.java)
            }.hook().after {
                if (!isGenerationActive) return@after
                val token = args(0).any() as? IBinder ?: return@after
                val pending = result<Any>() ?: return@after
                launchTransitions.observeClaim(token, pending)
            }
        }

        installRequiredCaptureHook("TaskViewTransitions.startAnimation(legacy)") {
            taskViewTransitionsClass.resolve().firstMethod {
                name = "startAnimation"
                parameterCount = 5
                parameters(
                    IBinder::class.java,
                    transitionInfoClass,
                    surfaceTransactionClass,
                    surfaceTransactionClass,
                    transitionFinishCallbackClass,
                )
            }.hook().after {
                finishClaimedLaunch(args(0).any() as? IBinder)
            }
        }

        installRequiredCaptureHook("TaskViewTransitions.startAnimation(dispatch state)") {
            taskViewTransitionsClass.resolve().firstMethod {
                name = "startAnimation"
                parameterCount = 6
                parameters(
                    IBinder::class.java,
                    transitionInfoClass,
                    transitionDispatchStateClass,
                    surfaceTransactionClass,
                    surfaceTransactionClass,
                    transitionFinishCallbackClass,
                )
            }.hook().after {
                finishClaimedLaunch(args(0).any() as? IBinder)
            }
        }

        installRequiredCaptureHook("TaskViewTransitions.onTransitionConsumed") {
            taskViewTransitionsClass.resolve().firstMethod {
                name = "onTransitionConsumed"
                parameterCount = 3
                parameters(
                    IBinder::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    surfaceTransactionClass,
                )
            }.hook().after {
                finishClaimedLaunch(args(0).any() as? IBinder)
            }
        }
    }

    /** Resolves nested startAnimation overloads once and drops both strong identity-map entries. */
    private fun finishClaimedLaunch(token: IBinder?) {
        if (!isGenerationActive || token == null) return
        handleLaunchResolution(launchTransitions.finish(token))
    }

    /** Routes one exact controller outcome without treating a merged transition as a launch error. */
    private fun handleLaunchResolution(resolution: AppEmbedLaunchResolution?) {
        when (resolution) {
            null -> Unit
            is AppEmbedLaunchResolution.NativePrepared ->
                broker?.onLaunchTransitionFinished(resolution.controller)

            is AppEmbedLaunchResolution.AwaitingTask ->
                broker?.onLaunchAwaitingLateTask(resolution.controller)

            is AppEmbedLaunchResolution.AdoptLateTask -> {
                val taskInfo = resolution.taskInfo as? ActivityManager.RunningTaskInfo
                    ?: error("Late TaskView adoption has no RunningTaskInfo")
                val leash = resolution.leash as? SurfaceControl
                    ?: error("Late TaskView adoption has no task leash")
                val activeBroker = broker
                if (activeBroker == null) {
                    launchTransitions.finishLateAdoptionCall(
                        resolution.controller,
                        succeeded = false,
                    )
                    return
                }
                activeBroker.adoptLateTask(
                    controller = resolution.controller,
                    taskInfo = taskInfo,
                    leash = leash,
                    onFinished = { succeeded ->
                        launchTransitions.finishLateAdoptionCall(
                            resolution.controller,
                            succeeded,
                        )
                    },
                )
            }
        }
    }

    /** Resolves the ROM singleton, starts the broker, then announces the usable generation. */
    private fun startBroker(context: Context) {
        if (!isGenerationActive) {
            YLog.warn("[$TAG] broker start ignored for a closed generation")
            return
        }
        if (broker != null) {
            YLog.warn("[$TAG] duplicate SystemUI Application onCreate ignored")
            return
        }

        var created: SystemUiAppEmbedBroker? = null
        try {
            val newBroker = SystemUiAppEmbedBroker(
                context = context,
                hostClassLoader = appClassLoader,
                generationActive = { isGenerationActive },
                logger = AppEmbedLogSink(::logBrokerMessage),
            )
            created = newBroker
            broker = newBroker
            newBroker.installFactoryFromApplication(context)
            check(newBroker.hasFactory()) { "SystemUI WMComponent returned no TaskViewFactory" }
            check(newBroker.start()) { "SystemUI AppEmbed broker refused to start" }
            check(newBroker.announceReady()) { "SystemUI AppEmbed readiness announcement failed" }
            YLog.info("[$TAG] broker started factoryAvailable=${newBroker.hasFactory()}")
        } catch (error: Throwable) {
            broker = null
            val createdBroker = created
            if (createdBroker != null) {
                val closed = runCatching {
                    createdBroker.close(AppEmbedReleaseReason.FAILURE)
                }
                    .onFailure { closeError ->
                        YLog.error("[$TAG] broker cleanup failed after startup error", closeError)
                    }
                    .getOrDefault(false)
                if (!closed) {
                    YLog.error("[$TAG] broker reported incomplete cleanup after startup error")
                }
            }
            YLog.error("[$TAG] broker startup failed", error)
        }
    }

    /** Closes the broker once and reports whether every capability and session was disposed. */
    private fun closeBroker(reason: AppEmbedReleaseReason): Boolean {
        val current = broker ?: return true
        broker = null
        return runCatching { current.close(reason) }
            .onFailure { error -> YLog.error("[$TAG] broker close failed reason=$reason", error) }
            .getOrDefault(false)
            .also { closed ->
                if (!closed) YLog.error("[$TAG] broker close incomplete reason=$reason")
            }
    }

    /** Installs one ROM hook and records its exact failure. */
    private fun installCaptureHook(name: String, install: () -> Unit): Boolean =
        runCatching {
            install()
            true
        }.onFailure { error ->
            YLog.error("[$TAG] failed to install $name capture", error)
        }.getOrDefault(false)

    /** Installs a required TaskView behavior hook and fails module installation if it is absent. */
    private fun installRequiredCaptureHook(name: String, install: () -> Unit) {
        check(
            installCaptureHook(
                name,
                install
            )
        ) { "Required AppEmbed capture is unavailable: $name" }
    }

    /** Routes the broker's explicit severity without inferring state from message text or causes. */
    private fun logBrokerMessage(
        level: AppEmbedLogLevel,
        message: String,
        error: Throwable?,
    ) {
        val taggedMessage = "[$TAG] $message"
        when (level) {
            AppEmbedLogLevel.DEBUG -> YLog.debug(taggedMessage, error)
            AppEmbedLogLevel.INFO -> YLog.info(taggedMessage, error)
            AppEmbedLogLevel.WARN -> YLog.warn(taggedMessage, error)
            AppEmbedLogLevel.ERROR -> YLog.error(taggedMessage, error)
        }
    }

    private companion object {
        const val OPEN_TRANSITION_TYPE = 1
        const val TAG = "SystemUiAppEmbed"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val SYSTEM_UI_PROCESS = "com.android.systemui"
        const val TASK_VIEW = "com.android.wm.shell.taskview.TaskView"
        const val TASK_VIEW_TASK_CONTROLLER =
            "com.android.wm.shell.taskview.TaskViewTaskController"
        const val TASK_VIEW_TRANSITIONS = "com.android.wm.shell.taskview.TaskViewTransitions"
        const val TASK_VIEW_PENDING_TRANSITION =
            "com.android.wm.shell.taskview.TaskViewTransitions\$PendingTransition"
        const val WINDOW_CONTAINER_TRANSACTION = "android.window.WindowContainerTransaction"
        const val TRANSITION_INFO = "android.window.TransitionInfo"
        const val TRANSITION_DISPATCH_STATE =
            "com.android.wm.shell.transition.TransitionDispatchState"
        const val SURFACE_TRANSACTION = "android.view.SurfaceControl\$Transaction"
        const val TRANSITION_FINISH_CALLBACK =
            "com.android.wm.shell.transition.Transitions\$TransitionFinishCallback"
    }

}

/**
 * Correlates WM Shell's package-private PendingTransition objects without reading their fields.
 * Constructor and public findPending hooks supply every identity used here.
 */
internal class AppEmbedLaunchTransitionTracker(
    private val openingType: Int,
) {
    init {
        require(openingType >= 0) { "openingType must be non-negative" }
    }

    private val lock = Any()
    private val pendingControllers = IdentityHashMap<Any, Any>()
    private val claimedControllers = IdentityHashMap<Any, PendingOwner>()
    private val controllerStates = IdentityHashMap<Any, ControllerState>()

    fun observePending(pending: Any, type: Int, controller: Any) {
        if (type != openingType) return
        synchronized(lock) {
            pendingControllers[pending] = controller
            controllerStates.putIfAbsent(controller, ControllerState())
        }
    }

    fun observeClaim(token: Any, pending: Any): Boolean = synchronized(lock) {
        val controller = pendingControllers[pending] ?: return@synchronized false
        claimedControllers[token] = PendingOwner(pending, controller)
        true
    }

    /** Marks entry into native prepare without treating a throwing call as successful. */
    fun beginNativePrepare(controller: Any): Boolean = synchronized(lock) {
        val state = controllerStates[controller] ?: return@synchronized false
        state.nativePrepareInProgress = true
        true
    }

    /** Clears a tentative prepare marker after the ROM method throws without resolving ownership. */
    fun abortNativePrepare(controller: Any): Boolean = synchronized(lock) {
        val state = controllerStates[controller] ?: return@synchronized false
        state.nativePrepareInProgress = false
        true
    }

    /** Confirms native preparation only after the ROM method returned successfully. */
    fun completeNativePrepare(controller: Any): AppEmbedLaunchResolution? = synchronized(lock) {
        val state = controllerStates[controller] ?: return@synchronized null
        state.nativePrepareInProgress = false
        state.nativePrepared = true
        state.releaseAppearedTask()
        if (state.fallbackClaimed) {
            controllerStates.remove(controller)
            return@synchronized null
        }
        if (!state.transitionEnded) return@synchronized null
        controllerStates.remove(controller)
        AppEmbedLaunchResolution.NativePrepared(controller)
    }

    /**
     * Caches the exact cookie-owned task until its original transition either prepares it or ends.
     */
    fun observeTaskAppeared(
        controller: Any,
        taskInfo: Any,
        leash: Any,
        releaseLeash: (Any) -> Unit = {},
    ): AppEmbedLaunchResolution? = synchronized(lock) {
        val state = controllerStates[controller]
        if (state == null || state.nativePrepared || state.appeared != null) {
            releaseLeash(leash)
            return@synchronized null
        }
        state.appeared = AppearedTask(taskInfo, leash, releaseLeash)
        if (state.fallbackClaimed || state.nativePrepareInProgress || !state.transitionEnded) {
            return@synchronized null
        }
        claimLateAdoption(controller, state)
    }

    /** True while native TaskView must not discard this owned task before late adoption. */
    fun shouldSuppressTaskNotFound(controller: Any): Boolean = synchronized(lock) {
        controllerStates[controller]?.let { !it.nativePrepared } == true
    }

    /** Resolves nested startAnimation overloads and chooses native completion or late adoption. */
    fun finish(token: Any): AppEmbedLaunchResolution? = synchronized(lock) {
        val owner = claimedControllers.remove(token) ?: return@synchronized null
        pendingControllers.remove(owner.pending)
        val state = controllerStates[owner.controller] ?: return@synchronized null
        state.transitionEnded = true
        if (state.nativePrepared) {
            state.releaseAppearedTask()
            controllerStates.remove(owner.controller)
            AppEmbedLaunchResolution.NativePrepared(owner.controller)
        } else {
            val appeared = state.appeared
            if (appeared == null || state.nativePrepareInProgress) {
                AppEmbedLaunchResolution.AwaitingTask(owner.controller)
            } else {
                claimLateAdoption(owner.controller, state)
            }
        }
    }

    /** Removes every retained identity after the owned task is definitively gone. */
    fun forgetController(controller: Any) {
        synchronized(lock) {
            controllerStates.remove(controller)?.releaseAppearedTask()
            pendingControllers.entries.removeAll { it.value === controller }
            claimedControllers.entries.removeAll { it.value.controller === controller }
        }
    }

    /** Releases a retained leash when public native adoption returned or failed before prepare. */
    fun finishLateAdoptionCall(controller: Any, succeeded: Boolean) {
        synchronized(lock) {
            val state = controllerStates[controller] ?: return
            state.releaseAppearedTask()
            if (succeeded && state.nativePrepared) {
                controllerStates.remove(controller)
            } else if (!succeeded) {
                state.nativePrepareInProgress = false
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            pendingControllers.clear()
            claimedControllers.clear()
            controllerStates.values.forEach(ControllerState::releaseAppearedTask)
            controllerStates.clear()
        }
    }

    private fun claimLateAdoption(
        controller: Any,
        state: ControllerState,
    ): AppEmbedLaunchResolution.AdoptLateTask {
        val appeared = checkNotNull(state.appeared) { "Late adoption requires an appeared task" }
        state.fallbackClaimed = true
        return AppEmbedLaunchResolution.AdoptLateTask(
            controller = controller,
            taskInfo = appeared.taskInfo,
            leash = appeared.leash,
        )
    }

    private data class PendingOwner(val pending: Any, val controller: Any)

    private class AppearedTask(
        val taskInfo: Any,
        val leash: Any,
        private val releaseLeash: (Any) -> Unit,
    ) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            releaseLeash(leash)
        }
    }

    private class ControllerState {
        var nativePrepared: Boolean = false
        var nativePrepareInProgress: Boolean = false
        var transitionEnded: Boolean = false
        var fallbackClaimed: Boolean = false
        var appeared: AppearedTask? = null

        fun releaseAppearedTask() {
            appeared?.release()
            appeared = null
        }
    }
}

/** Exact action selected after correlating one owned controller, transition, and appeared task. */
internal sealed interface AppEmbedLaunchResolution {
    /** The original TaskView OPEN path already prepared the controller. */
    data class NativePrepared(val controller: Any) : AppEmbedLaunchResolution

    /** The original launch ended before the cookie-owned task reached TaskView. */
    data class AwaitingTask(val controller: Any) : AppEmbedLaunchResolution

    /** A late cookie-owned task must be handed to TaskView's public root-task adoption path. */
    data class AdoptLateTask(
        val controller: Any,
        val taskInfo: Any,
        val leash: Any,
    ) : AppEmbedLaunchResolution
}
