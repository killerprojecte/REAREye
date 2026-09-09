package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.app.ActivityManager
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import androidx.core.content.ContextCompat
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeContract
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed.AppEmbedContract
import hk.uwu.reareye.internal.appembed.IAppEmbedCallback
import hk.uwu.reareye.internal.appembed.IAppEmbedService
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * SystemUI-side owner of AppEmbed client capabilities and real TaskView sessions.
 *
 * The broker reuses SystemUI's existing TaskViewFactory and therefore never registers another task
 * organizer. A verified bootstrap request receives a private [IAppEmbedService] Binder. Every call
 * checks the UID captured during bootstrap, and every session additionally follows owner/callback
 * Binder death until all TaskView and SurfaceControlViewHost resources are released.
 */
internal class SystemUiAppEmbedBroker(
    context: Context,
    hostClassLoader: ClassLoader,
    private val generationActive: () -> Boolean,
    /** Receives diagnostics whose severity was selected at the originating operation. */
    private val logger: AppEmbedLogSink,
) {
    private val applicationContext = context.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> runOnMain(runnable) }
    private val adapter = SystemUiTaskViewHostAdapter.create(
        hostClassLoader = hostClassLoader,
        dexDirectory = File(applicationContext.codeCacheDir, "reareye-appembed"),
        generationName = Integer.toUnsignedString(System.identityHashCode(this), 36),
    )
    private val taskViewFactory = AtomicReference<Any?>()
    private val nextCapabilityNamespace = AtomicLong(1L)
    private val capabilities = LinkedHashSet<ClientCapability>()
    private val sessions = LinkedHashMap<Long, SessionRecord>()
    private val reloadGate = AppEmbedReloadGate()
    private val deferredReloadWork = ArrayDeque<DeferredReloadWork>()
    private val receiverRegistered = AtomicBoolean(false)
    private val identityLock = Any()
    private val taskViewBounds = IdentityHashMap<Any, Rect>()
    private val taskControllerBounds = IdentityHashMap<Any, Rect>()
    private val taskControllerResources = IdentityHashMap<Any, OwnedTaskResource>()
    private val taskDensityRegistry = AppEmbedTaskDensityRegistry()

    private val callerPolicy = AppEmbedCallerPolicy(
        expectedPackage = AppEmbedContract.SUBSCREEN_PACKAGE,
        expectedUid = {
            applicationContext.packageManager.getPackageUid(
                AppEmbedContract.SUBSCREEN_PACKAGE,
                0,
            )
        },
        packagesForUid = applicationContext.packageManager::getPackagesForUid,
    )

    private val bootstrapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AppEmbedContract.REQUEST_ACTION) return
            if (!generationActive()) {
                logDebug("Reject AppEmbed bootstrap for inactive hook generation")
                return
            }

            val caller = try {
                callerPolicy.authorizeBootstrap(sentFromUid, sentFromPackage)
            } catch (error: SecurityException) {
                logWarn("Reject unauthenticated AppEmbed bootstrap", error)
                return
            }
            val callbackBinder = intent
                .getBundleExtra(HookHostBridgeContract.Extras.BUNDLE)
                ?.getBinder(HookHostBridgeContract.Extras.BINDER)
            if (callbackBinder == null) {
                logWarn("Reject AppEmbed bootstrap without callback Binder")
                return
            }
            val callback = IHookHostBridgeBootstrap.Stub.asInterface(callbackBinder)
            if (callback == null) {
                logWarn("Reject AppEmbed bootstrap with invalid callback Binder")
                return
            }

            val capability = try {
                ClientCapability(caller, callbackBinder, nextCapabilityNamespace())
            } catch (error: Throwable) {
                logError("Unable to create AppEmbed capability", error)
                return
            }

            capabilities += capability
            try {
                callback.onBinderReady(capability.asBinder())
            } catch (error: RemoteException) {
                logDebug("Unable to return AppEmbed capability Binder", error)
                capability.disposeInternal(AppEmbedReleaseReason.CLIENT_CAPABILITY_DIED)
            }
        }
    }

    /** Registers the authenticated Binder bootstrap receiver. */
    fun start(): Boolean {
        return runOnMainBlocking("register bootstrap receiver") {
            if (!generationActive()) {
                logError("Cannot start AppEmbed broker for inactive generation")
                return@runOnMainBlocking false
            }
            if (!receiverRegistered.compareAndSet(false, true)) {
                return@runOnMainBlocking true
            }
            try {
                ContextCompat.registerReceiver(
                    applicationContext,
                    bootstrapReceiver,
                    IntentFilter(AppEmbedContract.REQUEST_ACTION),
                    null,
                    null,
                    ContextCompat.RECEIVER_EXPORTED,
                )
                true
            } catch (error: Throwable) {
                receiverRegistered.set(false)
                logError("Unable to register AppEmbed bootstrap receiver", error)
                false
            }
        }
    }

    /** Announces a fully started generation so a live client replaces its old capability Binder. */
    fun announceReady(): Boolean {
        return runOnMainBlocking("announce broker readiness") {
            check(generationActive()) { "Cannot announce an inactive AppEmbed generation" }
            check(receiverRegistered.get()) { "Cannot announce AppEmbed before receiver registration" }
            check(hasFactory()) { "Cannot announce AppEmbed without a TaskViewFactory" }
            val intent = Intent(AppEmbedContract.BROKER_READY_ACTION)
                .setPackage(AppEmbedContract.SUBSCREEN_PACKAGE)
            val options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            applicationContext.sendBroadcast(intent, null, options)
            true
        }
    }

    /** Resolves and installs the current singleton factory from SystemUI's public component graph. */
    fun installFactoryFromApplication(applicationContext: Context) {
        installFactory(adapter.getFactoryFromApplication(applicationContext))
    }

    /** Retains the current ROM singleton only for this broker generation's session creation. */
    private fun installFactory(factory: Any) {
        taskViewFactory.set(factory)
        logInfo("SystemUI TaskViewFactory captured: ${factory.javaClass.name}")
    }

    /** Exposes readiness for hook diagnostics and fail-fast request handling. */
    fun hasFactory(): Boolean = taskViewFactory.get() != null

    /** Read-only main-thread snapshot used by reload preflight to avoid known deferred cleanup. */
    fun canPrepareReload(): Boolean {
        return runOnMainBlocking("inspect AppEmbed reload readiness") {
            val busyReason = reloadBusyReasonOnMain()
            if (busyReason != null) logInfo("AppEmbed hot reload is temporarily busy: $busyReason")
            busyReason == null
        }
    }

    /** Rechecks cleanup safety and atomically fences later creation and task-launch work. */
    fun prepareReload(): Boolean {
        return runOnMainBlocking("prepare AppEmbed reload") {
            if (reloadGate.route() == AppEmbedReloadGate.Route.DEFER) {
                return@runOnMainBlocking true
            }
            val busyReason = reloadBusyReasonOnMain()
            if (busyReason != null) {
                logInfo("AppEmbed hot reload preparation became busy: $busyReason")
                return@runOnMainBlocking false
            }
            reloadGate.prepare()
        }
    }

    /** Reopens this generation and resumes deferred work after a global prepare rollback. */
    fun rollbackReloadPreparation() {
        val completed = runOnMainBlocking("rollback AppEmbed reload preparation") {
            if (!reloadGate.rollback()) return@runOnMainBlocking true
            drainDeferredReloadWork(resume = true)
        }
        if (!completed) logError("AppEmbed reload preparation rollback did not complete synchronously")
    }

    private fun reloadBusyReasonOnMain(): String? {
        sessions.values.firstOrNull {
            it.machine.state == AppEmbedSessionState.CREATING_HOST
        }?.let { return "session=${it.request.sessionId} is awaiting TaskView factory completion" }
        val resources = synchronized(identityLock) { taskControllerResources.values.toList() }
        return resources.firstNotNullOfOrNull(OwnedTaskResource::reloadBusyReason)
    }

    /** Returns this broker's authoritative screen-space bounds for an owned TaskView instance. */
    fun authoritativeBoundsForTaskView(taskView: Any): Rect? = synchronized(identityLock) {
        taskViewBounds[taskView]?.let(::Rect)
    }

    /** Returns this broker's authoritative screen-space bounds for an owned TaskView controller. */
    fun authoritativeBoundsForTaskController(controller: Any): Rect? = synchronized(identityLock) {
        taskControllerBounds[controller]?.let(::Rect)
    }

    /** True only for a live TaskView controller created by this broker generation. */
    fun ownsTaskController(controller: Any): Boolean = synchronized(identityLock) {
        taskControllerResources.containsKey(controller)
    }

    /** Records the exact cookie-owned task before any asynchronous late-adoption dispatch. */
    fun recordAppearedTaskCandidate(
        controller: Any,
        taskInfo: ActivityManager.RunningTaskInfo,
    ): Boolean {
        val owned = synchronized(identityLock) { taskControllerResources[controller] }
            ?: return false
        return try {
            owned.recordAppearedTaskCandidate(taskInfo)
            true
        } catch (error: Throwable) {
            logError("Unable to record an appeared AppEmbed task candidate", error)
            runOnMain { reportLateAdoptionFailure(owned, error) }
            false
        }
    }

    /** Retains an appeared task leash across the Shell-to-main late-adoption handoff. */
    fun retainTaskLeash(controller: Any, leash: SurfaceControl): SurfaceControl? {
        val owned = synchronized(identityLock) { taskControllerResources[controller] }
            ?: return null
        return try {
            adapter.retainTaskLeash(leash)
        } catch (error: Throwable) {
            logError("Unable to retain an appeared AppEmbed task leash", error)
            runOnMain { reportLateAdoptionFailure(owned, error) }
            null
        }
    }

    /** Associates an owned controller with its actual child token before native open preparation. */
    fun onNativeTaskPrepared(controller: Any, taskInfo: ActivityManager.RunningTaskInfo) {
        val owned = synchronized(identityLock) { taskControllerResources[controller] } ?: return
        try {
            owned.onNativeTaskPrepared(taskInfo)
        } catch (error: Throwable) {
            logError("Unable to record an AppEmbed native task before open preparation", error)
            runOnMain { reportLateAdoptionFailure(owned, error) }
        }
    }

    /** Records a throwing native prepare without falsely resolving the owned task. */
    fun onNativeTaskPrepareFailed(controller: Any, error: Throwable) {
        val owned = synchronized(identityLock) { taskControllerResources[controller] } ?: return
        runOnMain { reportLateAdoptionFailure(owned, error) }
    }

    /** True while this exact controller still needs its cookie-owned task to be adopted. */
    fun shouldSuppressTaskNotFound(controller: Any): Boolean = synchronized(identityLock) {
        taskControllerResources[controller]?.shouldSuppressTaskNotFound() == true
    }

    /** Records one suppressed native false-negative without treating transition merging as failure. */
    fun onTaskNotFoundSuppressed(controller: Any) {
        val owned = synchronized(identityLock) { taskControllerResources[controller] } ?: return
        owned.onTaskNotFoundSuppressed()
    }

    /** Marks that an owned launch transition ended before its cookie-owned task appeared. */
    fun onLaunchAwaitingLateTask(controller: Any) {
        runOnMain {
            val owned = synchronized(identityLock) { taskControllerResources[controller] }
                ?: return@runOnMain
            owned.onLaunchAwaitingLateTask()
        }
    }

    /** Schedules public native TaskView adoption on this controller's own Shell executor. */
    fun adoptLateTask(
        controller: Any,
        taskInfo: ActivityManager.RunningTaskInfo,
        leash: SurfaceControl,
        onFinished: (Boolean) -> Unit,
    ) {
        val finishDelivered = AtomicBoolean(false)
        val finishOnce: (Boolean) -> Unit = { succeeded ->
            if (finishDelivered.compareAndSet(false, true)) onFinished(succeeded)
        }
        runOnMain {
            val owned = synchronized(identityLock) { taskControllerResources[controller] }
            if (owned == null) {
                finishOnce(false)
                return@runOnMain
            }
            try {
                owned.adoptLateTask(taskInfo, leash, finishOnce)
            } catch (error: Throwable) {
                finishOnce(false)
                logError("Unable to schedule AppEmbed late-task adoption", error)
                reportLateAdoptionFailure(owned, error)
            }
        }
    }

    /** Completes a tombstone only after WM reports that its exact adopted child token vanished. */
    fun onNativeTaskVanished(controller: Any, taskInfo: ActivityManager.RunningTaskInfo): Boolean {
        val owned = synchronized(identityLock) { taskControllerResources[controller] }
            ?: return false
        val vanished = try {
            owned.recordNativeTaskVanished(taskInfo)
        } catch (error: Throwable) {
            logError("Unable to validate an AppEmbed vanished-task callback", error)
            false
        }
        if (!vanished) return false
        runOnMain {
            if (synchronized(identityLock) { taskControllerResources[controller] } !== owned) {
                return@runOnMain
            }
            try {
                owned.onNativeTaskVanished()
            } catch (error: Throwable) {
                logError("Unable to finish vanished AppEmbed native task", error)
                reportLateAdoptionFailure(owned, error)
            }
        }
        return true
    }

    /** Keeps session-specific containment failures from escaping onto SystemUI's main thread. */
    private fun reportLateAdoptionFailure(owned: OwnedTaskResource, error: Throwable) {
        try {
            owned.failLateAdoption(error)
        } catch (cleanupError: Throwable) {
            logError("Unable to terminate a failed AppEmbed late adoption", cleanupError)
        }
    }

    /** Associates an owned controller with its actual child token before native open preparation. */
    fun prepareTaskDensity(controller: Any, taskInfo: ActivityManager.RunningTaskInfo) {
        val owned = synchronized(identityLock) { taskControllerResources[controller] } ?: return
        if (owned.densityDpi == INHERIT_DISPLAY_DENSITY) return
        try {
            val token = adapter.getTaskTokenBinder(taskInfo)
            synchronized(identityLock) {
                if (taskControllerResources[controller] !== owned) return
                taskDensityRegistry.register(
                    controller = controller,
                    taskId = taskInfo.taskId,
                    token = token,
                    densityDpi = owned.densityDpi,
                )
            }
        } catch (error: Throwable) {
            failTaskDensity(owned, "Unable to bind embedded child task density", error)
        }
    }

    /** Appends density and dependent dp fields to this child task's native bounds transaction. */
    fun appendTaskDensity(
        transaction: Any,
        taskInfo: ActivityManager.RunningTaskInfo,
        bounds: Rect,
    ) {
        val token = try {
            adapter.getTaskTokenBinder(taskInfo)
        } catch (error: Throwable) {
            logError("Unable to read TaskView child token while applying density", error)
            return
        }
        val binding = taskDensityRegistry.find(token) ?: return
        val owned = synchronized(identityLock) { taskControllerResources[binding.controller] }
            ?: return
        if (binding.taskId != taskInfo.taskId) {
            failTaskDensity(
                owned,
                "Embedded child task identity changed from ${binding.taskId} to ${taskInfo.taskId}",
                IllegalStateException("TaskInfo taskId changed for an owned child token"),
            )
            return
        }
        try {
            val density = AppEmbedTaskDensityCalculator.calculate(bounds, binding.densityDpi)
            adapter.applyTaskDensity(transaction, taskInfo, density)
        } catch (error: Throwable) {
            failTaskDensity(owned, "Unable to append embedded task density transaction", error)
        }
    }

    /** Logs and terminates only the session owning a failed task-scoped configuration. */
    private fun failTaskDensity(owned: OwnedTaskResource, message: String, error: Throwable) {
        logError(message, error)
        runOnMain {
            try {
                owned.failTaskDensity(message, error)
            } catch (containmentError: Throwable) {
                logError("Unable to contain AppEmbed task density failure", containmentError)
            }
        }
    }

    /** Completes deferred cleanup when WM Shell resolves this exact controller's launch transition. */
    fun onLaunchTransitionFinished(controller: Any) {
        runOnMain {
            val owned = synchronized(identityLock) { taskControllerResources[controller] }
                ?: return@runOnMain
            try {
                owned.onLaunchTransitionFinished()
            } catch (error: Throwable) {
                logError("Launch-transition cleanup failed for an AppEmbed TaskView", error)
                try {
                    owned.failAfterTransition(error)
                } catch (containmentError: Throwable) {
                    logError(
                        "Unable to contain AppEmbed launch-transition cleanup failure",
                        containmentError,
                    )
                }
            }
        }
    }

    private fun updateAuthoritativeBounds(taskView: Any, controller: Any, bounds: Rect) {
        synchronized(identityLock) {
            taskViewBounds[taskView] = Rect(bounds)
            taskControllerBounds[controller] = Rect(bounds)
        }
    }

    private fun registerTaskResource(
        taskView: Any,
        controller: Any,
        bounds: Rect,
        resource: OwnedTaskResource,
    ) {
        synchronized(identityLock) {
            check(!taskControllerResources.containsKey(controller)) {
                "TaskView controller is already owned by another AppEmbed resource"
            }
            taskViewBounds[taskView] = Rect(bounds)
            taskControllerBounds[controller] = Rect(bounds)
            taskControllerResources[controller] = resource
        }
    }

    private fun removeAuthoritativeBounds(taskView: Any, controller: Any) {
        synchronized(identityLock) {
            taskViewBounds.remove(taskView)
            taskControllerBounds.remove(controller)
            taskControllerResources.remove(controller)
            taskDensityRegistry.remove(controller)
        }
    }

    /** Unregisters bootstrap and closes only this hook generation's capabilities and sessions. */
    fun close(reason: AppEmbedReleaseReason = AppEmbedReleaseReason.MODULE_RELOAD): Boolean {
        return runOnMainBlocking("close broker") {
            var success = true
            reloadGate.close()
            if (!drainDeferredReloadWork(resume = false)) success = false
            if (receiverRegistered.compareAndSet(true, false)) {
                try {
                    applicationContext.unregisterReceiver(bootstrapReceiver)
                } catch (error: Throwable) {
                    success = false
                    logError("Unable to unregister AppEmbed bootstrap receiver", error)
                }
            }
            sessions.values.toList().forEach { session ->
                if (!session.releaseForBroker(reason)) success = false
            }
            capabilities.toList().forEach { capability ->
                if (!capability.disposeInternal(reason)) success = false
            }
            if (synchronized(identityLock) { taskControllerResources.isNotEmpty() }) {
                success = false
                logError("AppEmbed broker close left TaskView resources awaiting native completion")
            }
            taskViewFactory.set(null)
            success
        }
    }

    /** Private Binder capability minted for one authenticated bootstrap request. */
    private inner class ClientCapability(
        val caller: AuthorizedAppEmbedCaller,
        private val bootstrapBinder: IBinder,
        capabilityNamespace: Long,
    ) : IAppEmbedService.Stub() {
        private val sessionLedger = AppEmbedCapabilitySessionLedger(capabilityNamespace)
        private val bootstrapDeathRecipient = IBinder.DeathRecipient {
            disposeInternal(AppEmbedReleaseReason.CLIENT_CAPABILITY_DIED)
        }

        init {
            bootstrapBinder.linkToDeath(bootstrapDeathRecipient, 0)
        }

        override fun createSession(
            ownerToken: IBinder,
            hostToken: IBinder,
            displayId: Int,
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            taskBoundsOnScreen: Rect,
            launchIntent: Intent,
            touchable: Boolean,
            callback: IAppEmbedCallback,
        ): Long {
            enforceCallerAndOpen()
            AppEmbedTaskBoundsValidator.requireValid(widthPx, heightPx, taskBoundsOnScreen)
            require(densityDpi >= INHERIT_DISPLAY_DENSITY) {
                "AppEmbed density must be zero (inherit) or positive"
            }

            val sessionId = sessionLedger.issueSessionId()
            val request = SessionRequest(
                sessionId = sessionId,
                capability = this,
                ownerToken = ownerToken,
                hostToken = hostToken,
                displayId = displayId,
                widthPx = widthPx,
                heightPx = heightPx,
                densityDpi = densityDpi,
                taskBoundsOnScreen = Rect(taskBoundsOnScreen),
                launchIntent = Intent(launchIntent),
                touchable = touchable,
                callback = callback,
            )
            runOnMain { createSessionOnMain(request) }
            return sessionId
        }

        override fun resize(
            sessionId: Long,
            widthPx: Int,
            heightPx: Int,
            taskBoundsOnScreen: Rect,
        ) {
            if (!authorizeSessionCommand(sessionId, "resize")) return
            AppEmbedTaskBoundsValidator.requireValid(widthPx, heightPx, taskBoundsOnScreen)
            val boundsCopy = Rect(taskBoundsOnScreen)
            runOnMain {
                routeSessionCommandOnMain(this, sessionId, "resize") { session ->
                    session.resize(widthPx, heightPx, boundsCopy)
                }
            }
        }

        override fun setVisible(sessionId: Long, visible: Boolean) {
            if (!authorizeSessionCommand(sessionId, "visibility")) return
            runOnMain {
                routeSessionCommandOnMain(this, sessionId, "visibility") { session ->
                    session.setVisibleFromClient(visible)
                }
            }
        }

        override fun requestFocus(sessionId: Long) {
            if (!authorizeSessionCommand(sessionId, "focus")) return
            runOnMain {
                routeSessionCommandOnMain(this, sessionId, "focus") { session ->
                    session.requestFocusFromClient()
                }
            }
        }

        override fun release(sessionId: Long) {
            enforceCaller()
            sessionLedger.requireIssuedSession(sessionId)
            runOnMain {
                val session = sessions[sessionId]
                if (session == null) {
                    sessionLedger.markTerminal(sessionId)
                } else {
                    session.machine.release(AppEmbedReleaseReason.CLIENT_RELEASE)
                }
            }
        }

        override fun dispose() {
            enforceCaller()
            disposeInternal(AppEmbedReleaseReason.CLIENT_DISPOSE)
        }

        fun disposeInternal(reason: AppEmbedReleaseReason): Boolean {
            val activeSessionIds = sessionLedger.closeAndTakeActiveSessions() ?: return true
            var unlinkSuccess = true
            try {
                if (!bootstrapBinder.unlinkToDeath(bootstrapDeathRecipient, 0) &&
                    bootstrapBinder.isBinderAlive
                ) {
                    unlinkSuccess = false
                    logWarn("Bootstrap unlinkToDeath returned false for live capability")
                }
            } catch (error: Throwable) {
                unlinkSuccess = false
                logWarn("Bootstrap unlinkToDeath failed", error)
            }
            runOnMain {
                capabilities.remove(this)
                activeSessionIds.forEach { sessionId ->
                    sessions[sessionId]?.machine?.release(reason)
                }
            }
            return unlinkSuccess
        }

        fun onSessionTerminal(sessionId: Long) {
            sessionLedger.markTerminal(sessionId)
        }

        /** True while this issued session still accepts broker-side lifecycle work. */
        fun isSessionActive(sessionId: Long): Boolean = sessionLedger.isActiveSession(sessionId)

        /** Reports whether dispose/death has permanently closed this private capability. */
        fun isClosed(): Boolean = sessionLedger.isClosed()

        private fun enforceCallerAndOpen() {
            enforceCaller()
            check(generationActive()) { "AppEmbed hook generation is inactive" }
            check(!sessionLedger.isClosed()) { "AppEmbed capability is closed" }
        }

        private fun enforceCaller() {
            callerPolicy.enforceBinderCaller(caller, Binder.getCallingUid())
        }

        /** Validates caller and issued ownership, then discards expected stale state commands. */
        private fun authorizeSessionCommand(sessionId: Long, label: String): Boolean {
            enforceCaller()
            val active = sessionLedger.requireOwnedSessionAndCheckActive(sessionId)
            if (!generationActive() || !active) {
                logDebug("Ignore $label for inactive owned AppEmbed session=$sessionId")
                return false
            }
            return true
        }
    }

    /** Immutable request copied at the Binder boundary before work reaches the main thread. */
    private data class SessionRequest(
        val sessionId: Long,
        val capability: ClientCapability,
        val ownerToken: IBinder,
        val hostToken: IBinder,
        val displayId: Int,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val taskBoundsOnScreen: Rect,
        val launchIntent: Intent,
        val touchable: Boolean,
        val callback: IAppEmbedCallback,
    )

    private fun routeSessionCommandOnMain(
        capability: ClientCapability,
        sessionId: Long,
        label: String,
        command: (SessionRecord) -> Unit,
    ) {
        if (!generationActive() || !capability.isSessionActive(sessionId)) {
            logDebug("Ignore $label for released AppEmbed session=$sessionId")
            return
        }
        when (reloadGate.route()) {
            AppEmbedReloadGate.Route.RUN_NOW -> {
                sessions[sessionId]?.let(command)
                    ?: logDebug("Ignore $label for missing owned session=$sessionId")
            }

            AppEmbedReloadGate.Route.DEFER -> deferredReloadWork += DeferredReloadWork(
                resume = {
                    routeSessionCommandOnMain(capability, sessionId, label, command)
                },
                discard = {
                    // Final broker close owns session teardown; commands have no separate resource.
                },
            )

            AppEmbedReloadGate.Route.DISCARD ->
                logDebug("Ignore $label for closed AppEmbed session=$sessionId")
        }
    }

    private fun createSessionOnMain(request: SessionRequest) {
        if (!request.capability.isSessionActive(request.sessionId)) {
            logDebug("Ignore creation for released AppEmbed session=${request.sessionId}")
            return
        }
        if (!generationActive() || request.capability.isClosed()) {
            rejectDeferredCreation(request, "AppEmbed capability closed before session creation")
            return
        }
        when (reloadGate.route()) {
            AppEmbedReloadGate.Route.RUN_NOW -> Unit
            AppEmbedReloadGate.Route.DEFER -> {
                deferredReloadWork += DeferredReloadWork(
                    resume = { createSessionOnMain(request) },
                    discard = {
                        rejectDeferredCreation(
                            request,
                            "AppEmbed generation reloaded before session creation",
                        )
                    },
                )
                return
            }

            AppEmbedReloadGate.Route.DISCARD -> {
                rejectDeferredCreation(request, "AppEmbed broker closed before session creation")
                return
            }
        }
        val validated = try {
            validateRequest(request)
        } catch (error: Throwable) {
            request.capability.onSessionTerminal(request.sessionId)
            notifyStandaloneError(
                request,
                AppEmbedErrorCode.INVALID_REQUEST,
                error.message ?: "Invalid AppEmbed request",
            )
            logWarn("Reject invalid AppEmbed session=${request.sessionId}", error)
            return
        }
        if (taskViewFactory.get() == null) {
            request.capability.onSessionTerminal(request.sessionId)
            notifyStandaloneError(
                request,
                AppEmbedErrorCode.FACTORY_UNAVAILABLE,
                "SystemUI TaskViewFactory is unavailable; restart SystemUI after module reload",
            )
            return
        }

        val session = SessionRecord(request, validated)
        sessions[request.sessionId] = session
        session.start()
    }

    /** Main-thread validated launch/display state used for all later session operations. */
    private data class ValidatedRequest(
        val display: Display,
        val displayContext: Context,
        val launchIntent: Intent,
    )

    /** Stable child identity retained after TaskViewTaskController resets its TaskInfo on vanish. */
    private data class NativeTaskIdentity(
        /** Organizer task id assigned to this exact TaskView controller. */
        val taskId: Int,
        /** Binder identity behind the child task's WindowContainerToken. */
        val token: IBinder,
    )

    /** Minimal outer-broker view of a session resource awaiting its native launch result. */
    private interface LaunchTransitionResource {
        fun onLaunchTransitionFinished()

        fun failAfterTransition(error: Throwable)
    }

    /** Operations the outer broker may perform on one exact owned TaskView resource. */
    private interface OwnedTaskResource : LaunchTransitionResource {
        val densityDpi: Int

        fun recordAppearedTaskCandidate(taskInfo: ActivityManager.RunningTaskInfo)

        fun onNativeTaskPrepared(taskInfo: ActivityManager.RunningTaskInfo)

        fun shouldSuppressTaskNotFound(): Boolean

        fun onTaskNotFoundSuppressed()

        fun onLaunchAwaitingLateTask()

        fun adoptLateTask(
            taskInfo: ActivityManager.RunningTaskInfo,
            leash: SurfaceControl,
            onFinished: (Boolean) -> Unit,
        )

        fun failLateAdoption(error: Throwable)

        fun recordNativeTaskVanished(taskInfo: ActivityManager.RunningTaskInfo): Boolean

        fun onNativeTaskVanished()

        fun failTaskDensity(message: String, error: Throwable)

        fun reloadBusyReason(): String?
    }

    private fun validateRequest(request: SessionRequest): ValidatedRequest {
        require(request.displayId != Display.DEFAULT_DISPLAY) {
            "AppEmbed refuses the default display"
        }
        AppEmbedTaskBoundsValidator.requireValid(
            request.widthPx,
            request.heightPx,
            request.taskBoundsOnScreen,
        )
        val displayManager = applicationContext.getSystemService(DisplayManager::class.java)
            ?: error("DisplayManager is unavailable")
        val display = displayManager.getDisplay(request.displayId)
            ?: error("Display ${request.displayId} does not exist")
        require(display.isValid) { "Display ${request.displayId} is invalid" }
        val displayContext = applicationContext.createDisplayContext(display)
        val launchIntent = Intent(request.launchIntent)
        val component = launchIntent.component
            ?: error("AppEmbed launch Intent must have an explicit component")
        require(launchIntent.selector == null) { "AppEmbed launch Intent must not use a selector" }
        require(launchIntent.clipData == null) { "AppEmbed launch Intent must not contain ClipData" }
        require(launchIntent.`package` == null || launchIntent.`package` == component.packageName) {
            "AppEmbed launch package conflicts with its component"
        }
        require(
            component.packageName != AppEmbedContract.SYSTEM_UI_PACKAGE &&
                    component.packageName != AppEmbedContract.SUBSCREEN_PACKAGE
        ) {
            "AppEmbed cannot launch a broker or host component"
        }
        require(launchIntent.flags and URI_GRANT_FLAGS == 0) {
            "AppEmbed launch Intent must not delegate URI grants"
        }

        val resolved = applicationContext.packageManager.resolveActivity(
            launchIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo ?: error("Unable to resolve AppEmbed activity $component")
        val resolvedComponent = android.content.ComponentName(resolved.packageName, resolved.name)
        require(resolvedComponent == component) {
            "Resolved activity $resolvedComponent differs from requested $component"
        }
        val requiredPermission = resolved.permission?.takeIf(String::isNotBlank)
        AppEmbedActivityLaunchPolicy.requireAllowed(
            componentDescription = component.flattenToShortString(),
            exported = resolved.exported,
            requiredPermission = requiredPermission,
            issuerUid = Process.myUid(),
            issuerPackageName = applicationContext.packageName,
            issuerHasStartAnyActivity = applicationContext.checkSelfPermission(
                START_ANY_ACTIVITY_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED,
            issuerHasRequiredPermission = requiredPermission == null ||
                    applicationContext.checkSelfPermission(requiredPermission) ==
                    PackageManager.PERMISSION_GRANTED,
        )
        require(
            UserHandle.getUserHandleForUid(resolved.applicationInfo.uid) ==
                    UserHandle.getUserHandleForUid(request.capability.caller.uid)
        ) {
            "Cross-user AppEmbed launch is not allowed"
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ValidatedRequest(display, displayContext, launchIntent)
    }

    /** One session's Android effects, callbacks, timeout, and death recipients. */
    private inner class SessionRecord(
        val request: SessionRequest,
        private val validated: ValidatedRequest,
    ) : AppEmbedSessionEffects, SystemUiTaskViewListener {
        val machine = AppEmbedSessionStateMachine(request.sessionId, this)
        private var resource: TaskViewHostResource? = null
        private var ownerLinked = false
        private var callbackLinked = false
        private var timeoutPosted = false
        private var pendingWidthPx = request.widthPx
        private var pendingHeightPx = request.heightPx
        private val pendingBounds = Rect(request.taskBoundsOnScreen)
        private var requestedVisible = true
        private var focusRequested = false
        private var terminalReleaseFailure: Throwable? = null
        private val ownerDeath = IBinder.DeathRecipient {
            runOnMain { machine.release(AppEmbedReleaseReason.OWNER_DIED) }
        }
        private val callbackDeath = IBinder.DeathRecipient {
            runOnMain { machine.release(AppEmbedReleaseReason.CALLBACK_DIED) }
        }
        private val timeout = Runnable {
            timeoutPosted = false
            machine.onTimeout()
        }

        override fun onAdapterFailure(error: Throwable) {
            try {
                logError("TaskView listener failed session=${request.sessionId}", error)
                machine.fail(
                    AppEmbedErrorCode.INTERNAL_ERROR,
                    "TaskView listener callback failed",
                    error,
                )
            } catch (containmentError: Throwable) {
                logError(
                    "Unable to contain TaskView listener failure session=${request.sessionId}",
                    containmentError,
                )
            }
        }

        fun start() {
            try {
                request.ownerToken.linkToDeath(ownerDeath, 0)
                ownerLinked = true
                request.callback.asBinder().linkToDeath(callbackDeath, 0)
                callbackLinked = true
            } catch (error: RemoteException) {
                fail(
                    AppEmbedErrorCode.INVALID_REQUEST,
                    "AppEmbed owner or callback Binder is already dead",
                    error,
                )
                return
            }
            timeoutPosted = mainHandler.postDelayed(timeout, SESSION_TIMEOUT_MILLIS)
            if (!timeoutPosted) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, "Unable to schedule AppEmbed timeout")
                return
            }
            machine.beginCreate()
        }

        fun resize(widthPx: Int, heightPx: Int, bounds: Rect) {
            try {
                AppEmbedTaskBoundsValidator.requireValid(widthPx, heightPx, bounds)
            } catch (error: IllegalArgumentException) {
                fail(
                    AppEmbedErrorCode.INVALID_REQUEST,
                    error.message ?: "Invalid AppEmbed resize bounds",
                    error,
                )
                return
            }
            val sizeChanged = pendingWidthPx != widthPx || pendingHeightPx != heightPx
            pendingWidthPx = widthPx
            pendingHeightPx = heightPx
            pendingBounds.set(bounds)
            val current = resource ?: return
            try {
                current.bounds.set(bounds)
                updateAuthoritativeBounds(current.taskView, current.taskController, bounds)
                if (sizeChanged) current.viewHost.relayout(widthPx, heightPx)
                adapter.onLocationChanged(current.taskView, bounds)
            } catch (error: Throwable) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, "Unable to resize embedded task", error)
            }
        }

        fun setVisibleFromClient(visible: Boolean) {
            requestedVisible = visible
            if (resource == null) return
            try {
                machine.setVisible(visible)
            } catch (error: Throwable) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, "Unable to update visibility", error)
            }
        }

        fun requestFocusFromClient() {
            if (!request.touchable) {
                fail(
                    AppEmbedErrorCode.INVALID_REQUEST,
                    "A non-touchable AppEmbed session cannot request focus",
                )
                return
            }
            if (machine.state != AppEmbedSessionState.ACTIVE) {
                focusRequested = true
                return
            }
            requestActiveTaskFocus()
        }

        private fun requestActiveTaskFocus() {
            try {
                machine.requestFocus()
            } catch (error: Throwable) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, "Unable to request focus", error)
            }
        }

        fun fail(code: Int, message: String, cause: Throwable? = null) {
            machine.fail(code, message, cause)
        }

        fun releaseForBroker(reason: AppEmbedReleaseReason): Boolean {
            return machine.release(reason) && terminalReleaseFailure == null
        }

        override fun requestHostCreation(sessionId: Long, generation: Long) {
            val factory = taskViewFactory.get()
            if (factory == null) {
                fail(
                    AppEmbedErrorCode.FACTORY_UNAVAILABLE,
                    "SystemUI TaskViewFactory disappeared during session creation",
                )
                return
            }
            try {
                adapter.createTaskView(
                    factory = factory,
                    context = validated.displayContext,
                    executor = mainExecutor,
                    consumer = Consumer { taskView ->
                        runOnMain { acceptTaskView(generation, taskView) }
                    },
                )
            } catch (error: Throwable) {
                fail(AppEmbedErrorCode.HOST_CREATION_FAILED, "TaskView creation failed", error)
            }
        }

        private fun acceptTaskView(generation: Long, taskView: Any) {
            var newResource: TaskViewHostResource? = null
            try {
                val view = taskView as? View
                    ?: error("TaskViewFactory returned ${taskView.javaClass.name}, not a View")
                val listener = adapter.newListener(this)
                adapter.setListener(taskView, mainExecutor, listener)
                view.isFocusable = request.touchable
                view.isFocusableInTouchMode = request.touchable
                view.visibility = if (requestedVisible) View.VISIBLE else View.GONE
                val viewHost = SurfaceControlViewHost(
                    validated.displayContext,
                    validated.display,
                    request.hostToken,
                )
                newResource = TaskViewHostResource(
                    taskView = taskView,
                    taskController = adapter.getController(taskView),
                    view = view,
                    viewHost = viewHost,
                    bounds = Rect(pendingBounds),
                )
                resource = newResource
                viewHost.setView(view, pendingWidthPx, pendingHeightPx)
                adapter.onLocationChanged(taskView, pendingBounds)
                machine.onHostCreated(generation, newResource)
            } catch (error: Throwable) {
                if (newResource != null) {
                    runCatching { newResource.release(AppEmbedReleaseReason.FAILURE) }
                        .onFailure { releaseError ->
                            logError("Unable to release rejected TaskView resource", releaseError)
                        }
                } else {
                    runCatching { adapter.release(taskView) }
                        .onFailure { releaseError ->
                            logError("Unable to release rejected TaskView", releaseError)
                        }
                }
                fail(AppEmbedErrorCode.HOST_CREATION_FAILED, "Unable to host TaskView", error)
            }
        }

        override fun deliverSurface(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
        ) {
            val current = resource as? TaskViewHostResource
                ?: error("Unexpected AppEmbed host resource ${resource.javaClass.name}")
            val surfacePackage = current.viewHost.surfacePackage
            if (surfacePackage == null) {
                fail(
                    AppEmbedErrorCode.HOST_CREATION_FAILED,
                    "SurfaceControlViewHost returned no SurfacePackage",
                )
                return
            }
            try {
                val bundle = Bundle().apply {
                    putParcelable(AppEmbedContract.SURFACE_PACKAGE_KEY, surfacePackage)
                }
                request.callback.onSurfaceReady(sessionId, bundle)
            } catch (error: RemoteException) {
                fail(
                    AppEmbedErrorCode.SURFACE_DELIVERY_FAILED,
                    "Unable to deliver SurfacePackage",
                    error,
                )
                return
            } finally {
                surfacePackage.release()
            }
            machine.onSurfaceDelivered(generation)
        }

        override fun requestTaskStart(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
        ) {
            val current = resource as? TaskViewHostResource
                ?: error("Unexpected AppEmbed host resource ${resource.javaClass.name}")
            when (reloadGate.route()) {
                AppEmbedReloadGate.Route.RUN_NOW -> Unit
                AppEmbedReloadGate.Route.DEFER -> {
                    deferredReloadWork += DeferredReloadWork(
                        resume = { resumeDeferredTaskStart(sessionId, generation, current) },
                        discard = {
                            // Final broker close releases the session and its unlaunched host resource.
                        },
                    )
                    return
                }

                AppEmbedReloadGate.Route.DISCARD -> return
            }
            launchTask(sessionId, current)
        }

        private fun resumeDeferredTaskStart(
            sessionId: Long,
            generation: Long,
            current: TaskViewHostResource,
        ) {
            if (!generationActive() || sessions[sessionId] !== this ||
                machine.generation != generation ||
                machine.state != AppEmbedSessionState.STARTING_TASK
            ) {
                return
            }
            launchTask(sessionId, current)
        }

        private fun launchTask(sessionId: Long, current: TaskViewHostResource) {
            try {
                val launchIntent =
                    AppEmbedPendingIntentIdentity.launchIntent(validated.launchIntent, sessionId)
                hk.uwu.reareye.internal.appembed.AppEmbedInitialLayout(
                    Rect(current.bounds),
                    request.densityDpi
                )
                    .writeTo(launchIntent)
                val launchOptions = AppEmbedActivityLaunchOptions.Builder()
                    .setLaunchDisplayId(request.displayId)
                    .build()
                val pendingIntent = PendingIntent.getActivity(
                    validated.displayContext,
                    AppEmbedPendingIntentIdentity.requestCode(sessionId),
                    launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    launchOptions.pendingIntentCreationOptions,
                ) ?: error("Unable to create AppEmbed PendingIntent")
                current.pendingIntent = pendingIntent
                current.markLaunchAttempted()
                adapter.startActivity(
                    taskView = current.taskView,
                    pendingIntent = pendingIntent,
                    fillInIntent = Intent(),
                    options = launchOptions.taskViewSenderOptions,
                    bounds = Rect(current.bounds),
                )
            } catch (error: Throwable) {
                current.markLaunchSubmissionFailed()
                fail(AppEmbedErrorCode.TASK_LAUNCH_FAILED, "Unable to launch embedded task", error)
            }
        }

        override fun onTaskActive(sessionId: Long, generation: Long, taskId: Int) {
            if (timeoutPosted) {
                mainHandler.removeCallbacks(timeout)
                timeoutPosted = false
            }
            try {
                request.callback.onTaskCreated(sessionId, taskId)
            } catch (error: RemoteException) {
                logWarn("Unable to report active AppEmbed task=$taskId session=$sessionId", error)
                machine.release(AppEmbedReleaseReason.CALLBACK_DIED)
                return
            }
            if (focusRequested) {
                focusRequested = false
                requestActiveTaskFocus()
            }
        }

        override fun removeLateTask(sessionId: Long, generation: Long, taskId: Int) {
            val current = resource ?: return
            runCatching { current.requestLateTaskRemoval(taskId) }
                .onFailure { error ->
                    logError(
                        "Unable to finish late AppEmbed task=$taskId session=$sessionId",
                        error
                    )
                }
        }

        override fun setVisible(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
            visible: Boolean,
        ) {
            val current = resource as? TaskViewHostResource
                ?: error("Unexpected AppEmbed host resource ${resource.javaClass.name}")
            current.view.visibility = if (visible) View.VISIBLE else View.GONE
        }

        override fun requestFocus(
            sessionId: Long,
            generation: Long,
            resource: AppEmbedHostResource,
        ) {
            val current = resource as? TaskViewHostResource
                ?: error("Unexpected AppEmbed host resource ${resource.javaClass.name}")
            check(current.view.requestFocus()) {
                "TaskView rejected focus for session=$sessionId"
            }
        }

        override fun onTerminal(
            sessionId: Long,
            generation: Long,
            state: AppEmbedSessionState,
            reason: AppEmbedReleaseReason,
            error: AppEmbedSessionError?,
            releaseFailure: Throwable?,
        ) {
            terminalReleaseFailure = releaseFailure
            if (timeoutPosted) {
                mainHandler.removeCallbacks(timeout)
                timeoutPosted = false
            }
            unlinkSessionDeaths()
            sessions.remove(sessionId)
            request.capability.onSessionTerminal(sessionId)
            if (reason == AppEmbedReleaseReason.TASK_REMOVED) {
                try {
                    request.callback.onTaskRemovalStarted(sessionId)
                } catch (callbackError: RemoteException) {
                    logWarn(
                        "Unable to report removed AppEmbed task session=$sessionId",
                        callbackError
                    )
                }
            }
            if (releaseFailure != null) {
                logError(
                    "AppEmbed resource cleanup failed session=$sessionId reason=$reason",
                    releaseFailure,
                )
            }
            if (state == AppEmbedSessionState.FAILED && error != null) {
                try {
                    request.callback.onError(sessionId, error.code, error.message)
                } catch (callbackError: RemoteException) {
                    logWarn("Unable to report AppEmbed failure session=$sessionId", callbackError)
                }
                error.cause?.let {
                    logError("AppEmbed session failed session=$sessionId: ${error.message}", it)
                }
            }
        }

        override fun onInitialized() {
            machine.onNativeInitialized(machine.generation)
        }

        override fun onSurfaceAlreadyCreated() {
            // Recreated TaskView surfaces keep the existing session; visibility callbacks cover it.
        }

        override fun onReleased() {
            runCatching { resource?.onTaskViewReleased() }
                .onFailure { error ->
                    logError(
                        "Unable to finish released TaskView session=${request.sessionId}",
                        error
                    )
                }
            if (!machine.state.isTerminal && machine.state != AppEmbedSessionState.RELEASING) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, "TaskView released unexpectedly")
            }
        }

        override fun onTaskCreated(taskId: Int, componentName: android.content.ComponentName?) {
            if (machine.state.isTerminal || machine.state == AppEmbedSessionState.RELEASING) {
                machine.onTaskCreated(machine.generation, taskId)
                return
            }
            val current = resource ?: run {
                removeLateTask(request.sessionId, machine.generation, taskId)
                return
            }
            val taskInfo = try {
                adapter.getTaskInfo(current.taskView)
            } catch (error: Throwable) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, "Unable to inspect embedded task", error)
                return
            }
            if (taskInfo == null || taskInfo.taskId != taskId) {
                fail(
                    AppEmbedErrorCode.INTERNAL_ERROR,
                    "TaskView callback did not expose matching task info for task=$taskId",
                )
                return
            }
            if (!validateTaskInfo(taskInfo)) return
            machine.onTaskCreated(machine.generation, taskId)
        }

        override fun onTaskInfoChanged(taskInfo: ActivityManager.RunningTaskInfo) {
            validateTaskInfo(taskInfo)
        }

        private fun validateTaskInfo(taskInfo: ActivityManager.RunningTaskInfo): Boolean {
            val taskDisplayId = try {
                adapter.getTaskDisplayId(taskInfo)
            } catch (error: Throwable) {
                fail(
                    AppEmbedErrorCode.INTERNAL_ERROR,
                    "Unable to read embedded task display",
                    error
                )
                return false
            }
            if (taskDisplayId != request.displayId) {
                fail(
                    AppEmbedErrorCode.TASK_DISPLAY_MISMATCH,
                    "Embedded task moved to display=$taskDisplayId, expected=${request.displayId}",
                )
                return false
            }
            val expectedPackage = validated.launchIntent.component?.packageName
            val actualPackage =
                taskInfo.baseActivity?.packageName ?: taskInfo.topActivity?.packageName
            if (actualPackage != null && actualPackage != expectedPackage) {
                fail(
                    AppEmbedErrorCode.SECURITY_VIOLATION,
                    "Embedded task package=$actualPackage differs from expected=$expectedPackage",
                )
                return false
            }
            return true
        }

        override fun onTaskRemovalStarted(taskId: Int) {
            val current = resource
            current?.observeTaskRemovalStarted()
            if (machine.state.isTerminal || machine.state == AppEmbedSessionState.RELEASING) {
                return
            }
            machine.release(AppEmbedReleaseReason.TASK_REMOVED)
        }

        override fun onTaskVisibilityChanged(taskId: Int, visible: Boolean) {
            logDebug(
                "AppEmbed task visibility session=${request.sessionId} task=$taskId visible=$visible"
            )
            if (machine.state.isTerminal) return
            try {
                request.callback.onTaskVisibilityChanged(request.sessionId, visible)
            } catch (error: RemoteException) {
                logWarn("Unable to report AppEmbed visibility session=${request.sessionId}", error)
                machine.release(AppEmbedReleaseReason.CALLBACK_DIED)
            }
        }

        override fun onBackPressedOnTaskRoot(taskId: Int) {
            try {
                resource?.requestTaskRemovalForBack() ?: return
            } catch (error: Throwable) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, "Unable to close task on back", error)
            }
        }

        private fun unlinkSessionDeaths() {
            if (ownerLinked) {
                ownerLinked = false
                unlinkDeath(request.ownerToken, ownerDeath, "owner", request.sessionId)
            }
            if (callbackLinked) {
                callbackLinked = false
                unlinkDeath(
                    request.callback.asBinder(),
                    callbackDeath,
                    "callback",
                    request.sessionId
                )
            }
        }

        /** Owns all Android objects created after TaskViewFactory completion. */
        private inner class TaskViewHostResource(
            val taskView: Any,
            val taskController: Any,
            val view: View,
            val viewHost: SurfaceControlViewHost,
            val bounds: Rect,
        ) : AppEmbedHostResource, OwnedTaskResource {
            /** Client terminal state; native ownership may intentionally outlive it. */
            private val releaseRequested = AtomicBoolean(false)

            /** True only after listener removal is safe because no owned native task remains. */
            private val finalized = AtomicBoolean(false)

            /** Native prepare has bound the appeared child into this TaskView. */
            private val nativeTaskResolved = AtomicBoolean(false)

            /** The original OPEN ended and the exact cookie-owned task still needs adoption. */
            private val awaitingLateTask = AtomicBoolean(false)

            /** Ensures only one native startRootTask call is queued for an appeared child. */
            private val lateAdoptionScheduled = AtomicBoolean(false)

            /** A native child-removal WCT has been requested and must finish with onTaskVanished. */
            private val removalRequested = AtomicBoolean(false)

            /** The organizer confirmed that the exact candidate/adopted child is gone. */
            private val nativeTaskVanished = AtomicBoolean(false)

            /** The app-owned surface host is released independently from the native listener. */
            private val viewHostReleased = AtomicBoolean(false)

            /** Avoids repeated diagnostics while native false-negative cleanup is suppressed. */
            private val taskNotFoundLogged = AtomicBoolean(false)

            /** Exact child observed from the owned cookie before asynchronous adoption starts. */
            private val appearedTaskCandidate = AtomicReference<NativeTaskIdentity?>()

            /** Exact child confirmed by a successful native prepareOpenAnimation call. */
            private val nativeTaskIdentity = AtomicReference<NativeTaskIdentity?>()

            /** Cleanup or adoption failure that prevents a safe hot reload. */
            private val containmentFailure = AtomicReference<Throwable?>()
            var pendingIntent: PendingIntent? = null

            @Volatile
            var taskAlreadyRemoving: Boolean = false

            @Volatile
            private var launchAttempted: Boolean = false

            @Volatile
            private var launchTransitionFinished: Boolean = false

            @Volatile
            private var launchSubmissionFailed: Boolean = false

            @Volatile
            private var taskViewAlreadyReleased: Boolean = false
            private var terminalReason: AppEmbedReleaseReason? = null

            override val densityDpi: Int
                get() = request.densityDpi

            init {
                registerTaskResource(taskView, taskController, bounds, this)
            }

            override fun release(reason: AppEmbedReleaseReason) {
                if (!releaseRequested.compareAndSet(false, true)) return
                terminalReason = reason
                val failures = ArrayList<Throwable>()
                pendingIntent?.let { intent ->
                    runCatching(intent::cancel).onFailure(failures::add)
                    pendingIntent = null
                }
                view.visibility = View.GONE
                releaseViewHost(failures)

                when {
                    !launchAttempted || launchSubmissionFailed ->
                        finishReleaseAfterNativeTaskGone(failures)

                    nativeTaskVanished.get() ->
                        finishReleaseAfterNativeTaskGone(failures)

                    nativeTaskResolved.get() || currentTaskInfo() != null ->
                        requestNativeTaskRemoval(failures)

                    else -> {
                        awaitingLateTask.set(true)
                        logInfo(
                            "Retaining native AppEmbed launch listener after terminal session=" +
                                    "${request.sessionId}; a late cookie-owned task will be adopted hidden"
                        )
                    }
                }
                reportReleaseFailures(failures)
            }

            fun markLaunchAttempted() {
                check(!releaseRequested.get()) {
                    "Cannot launch a task after session=${request.sessionId} started releasing"
                }
                launchAttempted = true
            }

            fun markLaunchSubmissionFailed() {
                if (!launchAttempted) return
                launchSubmissionFailed = true
                launchTransitionFinished = true
            }

            override fun recordAppearedTaskCandidate(
                taskInfo: ActivityManager.RunningTaskInfo,
            ) {
                val candidate = NativeTaskIdentity(
                    taskId = taskInfo.taskId,
                    token = adapter.getTaskTokenBinder(taskInfo),
                )
                val previous = appearedTaskCandidate.get()
                check(previous == null || previous == candidate) {
                    "AppEmbed appeared-task identity changed for session=${request.sessionId}"
                }
                check(!nativeTaskVanished.get()) {
                    "AppEmbed task appeared after its owned candidate vanished " +
                            "session=${request.sessionId}"
                }
                appearedTaskCandidate.set(candidate)
            }

            override fun onNativeTaskPrepared(taskInfo: ActivityManager.RunningTaskInfo) {
                val identity = NativeTaskIdentity(
                    taskId = taskInfo.taskId,
                    token = adapter.getTaskTokenBinder(taskInfo),
                )
                val candidate = appearedTaskCandidate.get()
                check(candidate == null || candidate == identity) {
                    "AppEmbed prepared task differs from its appeared candidate " +
                            "session=${request.sessionId}"
                }
                val previous = nativeTaskIdentity.get()
                check(previous == null || previous == identity) {
                    "AppEmbed native task identity changed for session=${request.sessionId}"
                }
                nativeTaskIdentity.set(identity)
                nativeTaskResolved.set(true)
                awaitingLateTask.set(false)
            }

            override fun shouldSuppressTaskNotFound(): Boolean =
                launchAttempted && !launchSubmissionFailed && !nativeTaskResolved.get() &&
                        !nativeTaskVanished.get() && !finalized.get()

            override fun onTaskNotFoundSuppressed() {
                if (taskNotFoundLogged.compareAndSet(false, true)) {
                    logDebug(
                        "Suppressed premature TaskView task-not-found for owned session=" +
                                request.sessionId
                    )
                }
            }

            override fun onLaunchAwaitingLateTask() {
                if (nativeTaskResolved.get() || finalized.get()) return
                awaitingLateTask.set(true)
                logDebug(
                    "AppEmbed launch transition ended before task ownership session=" +
                            "${request.sessionId}; retaining its native cookie listener"
                )
            }

            override fun adoptLateTask(
                taskInfo: ActivityManager.RunningTaskInfo,
                leash: SurfaceControl,
                onFinished: (Boolean) -> Unit,
            ) {
                if (nativeTaskVanished.get() || finalized.get()) {
                    onFinished(false)
                    return
                }
                if (!lateAdoptionScheduled.compareAndSet(false, true)) {
                    onFinished(false)
                    return
                }
                val valid = validateTaskInfo(taskInfo)
                if (!valid && !releaseRequested.get()) {
                    onFinished(false)
                    return
                }
                val shellExecutor = try {
                    adapter.getShellExecutor(taskController)
                } catch (error: Throwable) {
                    onFinished(false)
                    failLateAdoption(error)
                    return
                }
                try {
                    shellExecutor.execute {
                        var succeeded = false
                        try {
                            if (nativeTaskVanished.get() || finalized.get()) {
                                return@execute
                            }
                            val expectedToken = adapter.getTaskTokenBinder(taskInfo)
                            val expectedIdentity =
                                NativeTaskIdentity(taskInfo.taskId, expectedToken)
                            check(appearedTaskCandidate.get() == expectedIdentity) {
                                "Late AppEmbed adoption candidate changed for " +
                                        "session=${request.sessionId}"
                            }
                            val alreadyPrepared = nativeTaskIdentity.get()?.let { identity ->
                                identity.taskId == taskInfo.taskId && identity.token == expectedToken
                            } == true
                            if (!alreadyPrepared) {
                                adapter.adoptTask(taskController, taskInfo, leash)
                            }
                            succeeded = true
                            logInfo(
                                "Native late-adopt completed for AppEmbed session=" +
                                        "${request.sessionId} task=${taskInfo.taskId} " +
                                        "display=${request.displayId} bounds=${Rect(bounds)}"
                            )
                            runOnMain { onLateAdoptionCompleted() }
                        } catch (error: Throwable) {
                            runOnMain { failLateAdoption(error) }
                        } finally {
                            try {
                                onFinished(succeeded)
                            } catch (error: Throwable) {
                                logError(
                                    "Unable to finish AppEmbed late-adoption tracking " +
                                            "session=${request.sessionId}",
                                    error,
                                )
                            }
                        }
                    }
                } catch (error: Throwable) {
                    onFinished(false)
                    failLateAdoption(error)
                }
            }

            private fun onLateAdoptionCompleted() {
                if (finalized.get()) return
                launchTransitionFinished = true
                awaitingLateTask.set(false)
                if (releaseRequested.get()) {
                    requestNativeTaskRemoval(ArrayList())
                }
            }

            override fun failLateAdoption(error: Throwable) {
                containmentFailure.compareAndSet(null, error)
                logError(
                    "Unable to natively adopt late AppEmbed task session=${request.sessionId}",
                    error,
                )
                if (!releaseRequested.get()) {
                    fail(
                        AppEmbedErrorCode.INTERNAL_ERROR,
                        "Unable to adopt cookie-owned embedded task",
                        error,
                    )
                }
            }

            override fun recordNativeTaskVanished(
                taskInfo: ActivityManager.RunningTaskInfo,
            ): Boolean {
                val expected =
                    nativeTaskIdentity.get() ?: appearedTaskCandidate.get() ?: return false
                val actualToken = adapter.getTaskTokenBinder(taskInfo)
                if (expected.taskId != taskInfo.taskId || expected.token != actualToken) return false
                if (!nativeTaskVanished.compareAndSet(false, true)) return false
                return true
            }

            override fun onNativeTaskVanished() {
                if (releaseRequested.get()) {
                    finishReleaseAfterNativeTaskGone(ArrayList())
                } else {
                    machine.release(AppEmbedReleaseReason.TASK_REMOVED)
                }
            }

            override fun onLaunchTransitionFinished() {
                if (!launchAttempted || launchTransitionFinished || finalized.get()) return
                val taskAttached = adapter.getTaskInfo(taskView) != null
                if (!taskAttached) {
                    awaitingLateTask.set(true)
                    if (releaseRequested.get()) return
                    fail(
                        AppEmbedErrorCode.TASK_LAUNCH_FAILED,
                        "TaskView native open returned without attaching its owned task",
                    )
                    return
                }
                launchTransitionFinished = true
                if (releaseRequested.get()) {
                    requestNativeTaskRemoval(ArrayList())
                }
            }

            override fun failAfterTransition(error: Throwable) {
                containmentFailure.compareAndSet(null, error)
                if (releaseRequested.get()) {
                    return
                }
                fail(
                    AppEmbedErrorCode.INTERNAL_ERROR,
                    "Unable to finish TaskView launch transition",
                    error,
                )
            }

            override fun failTaskDensity(message: String, error: Throwable) {
                fail(AppEmbedErrorCode.INTERNAL_ERROR, message, error)
            }

            override fun reloadBusyReason(): String? {
                if (finalized.get()) return null
                containmentFailure.get()?.let {
                    return "session=${request.sessionId} lost native TaskView containment"
                }
                if (removalRequested.get()) {
                    return "session=${request.sessionId} is awaiting native task removal"
                }
                if (releaseRequested.get()) {
                    return "session=${request.sessionId} retains a late-task native listener"
                }
                if (awaitingLateTask.get() || launchAttempted && !launchTransitionFinished) {
                    return "session=${request.sessionId} is awaiting native task ownership"
                }
                if ((nativeTaskIdentity.get() != null || appearedTaskCandidate.get() != null) &&
                    !nativeTaskVanished.get()
                ) {
                    return "session=${request.sessionId} owns a native task; close AppEmbed " +
                            "and wait for removal before hot reload"
                }
                return null
            }

            fun requestLateTaskRemoval(taskId: Int) {
                val failures = ArrayList<Throwable>()
                requestNativeTaskRemoval(failures)
                logDebug("Requested late AppEmbed task removal task=$taskId session=${request.sessionId}")
                reportReleaseFailures(failures)
            }

            fun requestTaskRemovalForBack() {
                val failures = ArrayList<Throwable>()
                requestNativeTaskRemoval(failures)
                reportReleaseFailures(failures)
            }

            fun onTaskViewReleased() {
                taskViewAlreadyReleased = true
                if (!finalized.get() && !nativeTaskVanished.get()) {
                    val error = IllegalStateException(
                        "TaskView released before its native task vanished " +
                                "session=${request.sessionId}"
                    )
                    containmentFailure.compareAndSet(null, error)
                    logError(error.message ?: "AppEmbed TaskView containment was lost", error)
                }
            }

            fun observeTaskRemovalStarted() {
                taskAlreadyRemoving = true
                removalRequested.set(true)
            }

            private fun currentTaskInfo(): ActivityManager.RunningTaskInfo? =
                runCatching { adapter.getTaskInfo(taskView) }
                    .onFailure { error ->
                        containmentFailure.compareAndSet(null, error)
                        logWarn(
                            "Unable to inspect closing AppEmbed task session=${request.sessionId}; " +
                                    "retaining its native listener",
                            error,
                        )
                    }
                    .getOrNull()

            private fun requestNativeTaskRemoval(failures: ArrayList<Throwable>) {
                if (nativeTaskVanished.get()) {
                    finishReleaseAfterNativeTaskGone(failures)
                    return
                }
                if (!removalRequested.compareAndSet(false, true)) return
                if (taskAlreadyRemoving) return
                try {
                    adapter.removeTask(taskView)
                } catch (error: Throwable) {
                    containmentFailure.compareAndSet(null, error)
                    failures += error
                }
            }

            private fun releaseViewHost(failures: ArrayList<Throwable>) {
                if (!viewHostReleased.compareAndSet(false, true)) return
                runCatching(viewHost::release).onFailure(failures::add)
            }

            private fun finishReleaseAfterNativeTaskGone(failures: ArrayList<Throwable>) {
                if (!finalized.compareAndSet(false, true)) return
                removeAuthoritativeBounds(taskView, taskController)
                if (!taskViewAlreadyReleased) {
                    runCatching { adapter.release(taskView) }.onFailure(failures::add)
                }
                releaseViewHost(failures)
                reportReleaseFailures(failures)
            }

            private fun reportReleaseFailures(failures: ArrayList<Throwable>) {
                val reason = terminalReason ?: AppEmbedReleaseReason.FAILURE
                failures.forEach { failure ->
                    logError(
                        "AppEmbed cleanup step failed session=${request.sessionId} reason=$reason",
                        failure,
                    )
                }
                if (failures.isNotEmpty()) {
                    throw AppEmbedCleanupException(request.sessionId, failures)
                }
            }
        }
    }

    private fun rejectDeferredCreation(request: SessionRequest, message: String) {
        if (!request.capability.isSessionActive(request.sessionId)) {
            logDebug("Ignore rejection for released AppEmbed session=${request.sessionId}")
            return
        }
        request.capability.onSessionTerminal(request.sessionId)
        notifyStandaloneError(request, AppEmbedErrorCode.INTERNAL_ERROR, message)
    }

    private fun drainDeferredReloadWork(resume: Boolean): Boolean {
        var success = true
        while (deferredReloadWork.isNotEmpty()) {
            val work = deferredReloadWork.removeFirst()
            try {
                if (resume) work.resume() else work.discard()
            } catch (error: Throwable) {
                success = false
                logError(
                    if (resume) "Unable to resume deferred AppEmbed reload work"
                    else "Unable to discard deferred AppEmbed reload work",
                    error,
                )
            }
        }
        return success
    }

    /** Generation-owned deferred work contains both rollback and final-close behavior. */
    private data class DeferredReloadWork(
        val resume: () -> Unit,
        val discard: () -> Unit,
    )

    private fun notifyStandaloneError(request: SessionRequest, code: Int, message: String) {
        try {
            request.callback.onError(request.sessionId, code, message)
        } catch (error: RemoteException) {
            logWarn("Unable to report rejected AppEmbed session=${request.sessionId}", error)
        }
    }

    private fun unlinkDeath(
        binder: IBinder,
        recipient: IBinder.DeathRecipient,
        label: String,
        sessionId: Long,
    ) {
        try {
            if (!binder.unlinkToDeath(recipient, 0) && binder.isBinderAlive) {
                logWarn("$label unlinkToDeath returned false session=$sessionId")
            }
        } catch (error: Throwable) {
            logWarn("$label unlinkToDeath failed session=$sessionId", error)
        }
    }

    private fun nextCapabilityNamespace(): Long {
        val namespace = nextCapabilityNamespace.getAndIncrement()
        check(namespace in 1L..AppEmbedCapabilitySessionLedger.MAX_CAPABILITY_NAMESPACE) {
            "AppEmbed capability namespace exhausted"
        }
        return namespace
    }

    private fun runOnMain(runnable: Runnable) {
        if (Looper.myLooper() == mainHandler.looper) {
            runnable.run()
        } else if (!mainHandler.post(runnable)) {
            logError("SystemUI main Handler rejected AppEmbed work")
        }
    }

    private fun runOnMain(block: () -> Unit) = runOnMain(Runnable(block))

    private fun runOnMainBlocking(label: String, block: () -> Boolean): Boolean {
        if (Looper.myLooper() == mainHandler.looper) return block()
        val result = AtomicReference<Boolean>()
        val latch = CountDownLatch(1)
        if (!mainHandler.post {
                try {
                    result.set(block())
                } catch (error: Throwable) {
                    logError("AppEmbed main operation failed: $label", error)
                    result.set(false)
                } finally {
                    latch.countDown()
                }
            }
        ) {
            logError("SystemUI main Handler rejected AppEmbed operation: $label")
            return false
        }
        val completed = try {
            latch.await(MAIN_OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            logError("Interrupted waiting for AppEmbed operation: $label", error)
            false
        }
        if (!completed) {
            logError("Timed out waiting for AppEmbed operation: $label")
            return false
        }
        return result.get() == true
    }

    private fun logDebug(message: String, error: Throwable? = null) =
        logger.write(AppEmbedLogLevel.DEBUG, message, error)

    private fun logInfo(message: String, error: Throwable? = null) =
        logger.write(AppEmbedLogLevel.INFO, message, error)

    private fun logWarn(message: String, error: Throwable? = null) =
        logger.write(AppEmbedLogLevel.WARN, message, error)

    private fun logError(message: String, error: Throwable? = null) =
        logger.write(AppEmbedLogLevel.ERROR, message, error)

    private class AppEmbedCleanupException(
        sessionId: Long,
        failures: List<Throwable>,
    ) : IllegalStateException("AppEmbed cleanup failed session=$sessionId steps=${failures.size}") {
        init {
            failures.forEach(::addSuppressed)
        }
    }

    companion object {
        private const val START_ANY_ACTIVITY_PERMISSION = "android.permission.START_ANY_ACTIVITY"
        private const val INHERIT_DISPLAY_DENSITY = 0
        private const val SESSION_TIMEOUT_MILLIS = 8_000L
        private const val MAIN_OPERATION_TIMEOUT_MILLIS = 3_000L
        private const val URI_GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
