package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

/**
 * One broker session's monotonic lifecycle.
 *
 * The state machine owns ordering and late-callback disposal only. Android views, Binder callbacks,
 * task launches, and timers are supplied through [AppEmbedSessionEffects], which keeps the lifecycle
 * directly testable without loading SystemUI or hidden framework classes.
 */
internal class AppEmbedSessionStateMachine(
    val sessionId: Long,
    private val effects: AppEmbedSessionEffects,
) {
    init {
        require(sessionId > 0L) { "sessionId must be positive" }
    }

    private val lock = Any()
    private var generationCounter = 0L
    private var hostResource: AppEmbedHostResource? = null
    private var activeTaskId: Int? = null
    private var nativeInitialized = false
    private var surfaceDelivered = false

    @Volatile
    var state: AppEmbedSessionState = AppEmbedSessionState.NEW
        private set

    @Volatile
    var generation: Long = 0L
        private set

    fun beginCreate() {
        val currentGeneration = synchronized(lock) {
            check(state == AppEmbedSessionState.NEW) {
                "Session $sessionId cannot begin from $state"
            }
            generationCounter += 1L
            generation = generationCounter
            state = AppEmbedSessionState.CREATING_HOST
            generation
        }
        effects.requestHostCreation(sessionId, currentGeneration)
    }

    fun onHostCreated(callbackGeneration: Long, resource: AppEmbedHostResource) {
        val accepted = synchronized(lock) {
            if (callbackGeneration != generation || state != AppEmbedSessionState.CREATING_HOST) {
                false
            } else {
                hostResource = resource
                state = AppEmbedSessionState.HOST_READY
                true
            }
        }
        if (!accepted) {
            resource.release(AppEmbedReleaseReason.LATE_HOST_CALLBACK)
            return
        }
        effects.deliverSurface(sessionId, callbackGeneration, resource)
    }

    fun onSurfaceDelivered(callbackGeneration: Long) {
        val resourceToStart = synchronized(lock) {
            if (callbackGeneration != generation || state != AppEmbedSessionState.HOST_READY) {
                return
            }
            surfaceDelivered = true
            state = AppEmbedSessionState.SURFACE_DELIVERED
            prepareTaskStartLocked()
        }
        if (resourceToStart != null) {
            effects.requestTaskStart(sessionId, callbackGeneration, resourceToStart)
        }
    }

    /** Records native TaskView readiness and starts only after the surface was delivered too. */
    fun onNativeInitialized(callbackGeneration: Long) {
        val resourceToStart = synchronized(lock) {
            if (callbackGeneration != generation || state.isTerminal ||
                state == AppEmbedSessionState.RELEASING
            ) {
                return
            }
            nativeInitialized = true
            prepareTaskStartLocked()
        }
        if (resourceToStart != null) {
            effects.requestTaskStart(sessionId, callbackGeneration, resourceToStart)
        }
    }

    private fun prepareTaskStartLocked(): AppEmbedHostResource? {
        if (!nativeInitialized || !surfaceDelivered ||
            state != AppEmbedSessionState.SURFACE_DELIVERED
        ) {
            return null
        }
        val resource = hostResource
            ?: error("Session $sessionId reached SURFACE_DELIVERED without a host resource")
        state = AppEmbedSessionState.STARTING_TASK
        return resource
    }

    fun onTaskCreated(callbackGeneration: Long, taskId: Int) {
        require(taskId > 0) { "taskId must be positive" }
        val result = synchronized(lock) {
            when {
                callbackGeneration == generation &&
                        state == AppEmbedSessionState.ACTIVE &&
                        activeTaskId == taskId -> TaskCallbackResult.DUPLICATE_ACTIVE

                callbackGeneration == generation &&
                        state == AppEmbedSessionState.STARTING_TASK -> {
                    activeTaskId = taskId
                    state = AppEmbedSessionState.ACTIVE
                    TaskCallbackResult.ACTIVATED
                }

                else -> TaskCallbackResult.LATE_OR_CONFLICTING
            }
        }
        when (result) {
            TaskCallbackResult.ACTIVATED ->
                effects.onTaskActive(sessionId, callbackGeneration, taskId)

            TaskCallbackResult.LATE_OR_CONFLICTING ->
                effects.removeLateTask(sessionId, callbackGeneration, taskId)

            TaskCallbackResult.DUPLICATE_ACTIVE -> Unit
        }
    }

    fun setVisible(visible: Boolean) {
        val resource = synchronized(lock) {
            check(!state.isTerminal) { "Session $sessionId is terminal: $state" }
            check(state >= AppEmbedSessionState.HOST_READY) {
                "Session $sessionId has no host view in state $state"
            }
            hostResource ?: error("Session $sessionId has no host resource")
        }
        effects.setVisible(sessionId, generation, resource, visible)
    }

    fun requestFocus() {
        val resource = synchronized(lock) {
            check(state == AppEmbedSessionState.ACTIVE) {
                "Session $sessionId cannot request focus in state $state"
            }
            hostResource ?: error("Session $sessionId has no host resource")
        }
        effects.requestFocus(sessionId, generation, resource)
    }

    fun release(reason: AppEmbedReleaseReason): Boolean {
        return terminate(
            terminalState = AppEmbedSessionState.RELEASED,
            reason = reason,
            error = null,
        )
    }

    fun fail(code: Int, message: String, cause: Throwable? = null): Boolean {
        require(code > 0) { "error code must be positive" }
        require(message.isNotBlank()) { "error message must not be blank" }
        return terminate(
            terminalState = AppEmbedSessionState.FAILED,
            reason = AppEmbedReleaseReason.FAILURE,
            error = AppEmbedSessionError(code, message, cause),
        )
    }

    fun onTimeout(): Boolean = terminate(
        terminalState = AppEmbedSessionState.FAILED,
        reason = AppEmbedReleaseReason.TIMEOUT,
        error = AppEmbedSessionError(
            code = AppEmbedErrorCode.TIMEOUT,
            message = "App embed session timed out",
        ),
    )

    private fun terminate(
        terminalState: AppEmbedSessionState,
        reason: AppEmbedReleaseReason,
        error: AppEmbedSessionError?,
    ): Boolean {
        val resource = synchronized(lock) {
            if (state.isTerminal || state == AppEmbedSessionState.RELEASING) return false
            state = AppEmbedSessionState.RELEASING
            hostResource.also { hostResource = null }
        }

        var releaseFailure: Throwable? = null
        if (resource != null) {
            runCatching { resource.release(reason) }
                .onFailure { releaseFailure = it }
        }

        synchronized(lock) {
            state = terminalState
        }
        effects.onTerminal(
            sessionId = sessionId,
            generation = generation,
            state = terminalState,
            reason = reason,
            error = error,
            releaseFailure = releaseFailure,
        )
        return true
    }

    private enum class TaskCallbackResult {
        ACTIVATED,
        DUPLICATE_ACTIVE,
        LATE_OR_CONFLICTING,
    }
}

internal enum class AppEmbedSessionState {
    NEW,
    CREATING_HOST,
    HOST_READY,
    SURFACE_DELIVERED,
    STARTING_TASK,
    ACTIVE,
    RELEASING,
    RELEASED,
    FAILED;

    val isTerminal: Boolean
        get() = this == RELEASED || this == FAILED
}

internal enum class AppEmbedReleaseReason {
    CLIENT_RELEASE,
    OWNER_DIED,
    CALLBACK_DIED,
    CLIENT_CAPABILITY_DIED,
    CLIENT_DISPOSE,
    TASK_REMOVED,
    MODULE_RELOAD,
    FAILURE,
    TIMEOUT,
    LATE_HOST_CALLBACK,
}

internal data class AppEmbedSessionError(
    val code: Int,
    val message: String,
    val cause: Throwable? = null,
)

internal object AppEmbedErrorCode {
    const val INVALID_REQUEST = 1
    const val FACTORY_UNAVAILABLE = 2
    const val HOST_CREATION_FAILED = 3
    const val SURFACE_DELIVERY_FAILED = 4
    const val TASK_LAUNCH_FAILED = 5
    const val TASK_DISPLAY_MISMATCH = 6
    const val TIMEOUT = 7
    const val SECURITY_VIOLATION = 8
    const val INTERNAL_ERROR = 9
}

/** A host resource must make TaskView removal and SurfaceControlViewHost release idempotent. */
internal fun interface AppEmbedHostResource {
    fun release(reason: AppEmbedReleaseReason)
}

/** Side effects emitted by [AppEmbedSessionStateMachine] in strict lifecycle order. */
internal interface AppEmbedSessionEffects {
    fun requestHostCreation(sessionId: Long, generation: Long)

    fun deliverSurface(sessionId: Long, generation: Long, resource: AppEmbedHostResource)

    fun requestTaskStart(
        sessionId: Long,
        generation: Long,
        resource: AppEmbedHostResource,
    )

    fun onTaskActive(sessionId: Long, generation: Long, taskId: Int)

    fun removeLateTask(sessionId: Long, generation: Long, taskId: Int)

    fun setVisible(
        sessionId: Long,
        generation: Long,
        resource: AppEmbedHostResource,
        visible: Boolean,
    )

    fun requestFocus(sessionId: Long, generation: Long, resource: AppEmbedHostResource)

    fun onTerminal(
        sessionId: Long,
        generation: Long,
        state: AppEmbedSessionState,
        reason: AppEmbedReleaseReason,
        error: AppEmbedSessionError?,
        releaseFailure: Throwable?,
    )
}
