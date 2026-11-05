package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

/** Pure state machine: Android calls and persistence are injected by the host adapter. */
internal enum class WidgetPhase { ALLOCATED, BOUND, CONFIGURING, COMPLETE }
internal data class WidgetBinding(
    val id: Int,
    val provider: String,
    val complete: Boolean,
    val phase: WidgetPhase? = null
)

internal enum class WidgetState { NEW, NEED_BIND, NEED_CONFIGURE, WAIT_BIND, WAIT_CONFIGURE, READY, ERROR, CONFLICT, FINISHED }
internal enum class WidgetRequest { BIND, CONFIGURE }
internal data class WidgetResult(val accepted: Boolean, val id: Int?)
internal interface WidgetPlatform {
    fun read(key: String): WidgetBinding?
    fun write(key: String, binding: WidgetBinding?)
    fun ownedIds(): Set<Int>
    fun providerOf(id: Int): String?
    fun providerExists(provider: String): Boolean
    fun needsConfiguration(provider: String): Boolean
    fun allocate(): Int
    fun delete(id: Int)
    fun bind(id: Int, provider: String): Boolean
    fun request(
        kind: WidgetRequest,
        id: Int,
        provider: String,
        result: (WidgetResult) -> Unit
    ): (() -> Unit)?

    /** Diagnostic only; implementations must not change widget state. */
    fun reportFailure(operation: String, throwable: Throwable) {}

    fun startListening()
    fun stopListening()
    fun diagnose(
        key: String, state: WidgetState, id: Int?, complete: Boolean, phase: WidgetPhase?,
        owned: Boolean?, providerMatches: Boolean?, providerExists: Boolean?
    ) {
    }
}

internal class WidgetSessions(private val platform: WidgetPlatform) {
    private val claimed = mutableMapOf<String, WidgetSession>()
    private val listening = mutableSetOf<WidgetSession>()
    private var hostListening = false

    fun open(key: String, provider: String, changed: () -> Unit): WidgetSession {
        val session = WidgetSession(this, platform, key, provider, changed)
        if (claimed.containsKey(key)) session.state = WidgetState.CONFLICT
        else claimed[key] = session
        return session
    }

    internal fun active(session: WidgetSession, active: Boolean) {
        if (active) listening.add(session) else listening.remove(session)
        if (listening.isNotEmpty() && !hostListening) {
            platform.startListening()
            hostListening = true
        } else if (listening.isEmpty() && hostListening) {
            platform.stopListening()
            hostListening = false
        }
    }

    internal fun close(session: WidgetSession) {
        try {
            active(session, false)
        } finally {
            if (claimed[session.key] === session) claimed.remove(session.key)
        }
    }

    /** Called only by the verified host card removal callback, never by pause/destroy. */
    fun deleteCardBinding(key: String): Boolean {
        claimed[key]?.finish()
        return deleteBinding(key)
    }

    /** Explicit user deletion. A live session owns its key even while paused. */
    fun deleteBinding(key: String): Boolean {
        if (claimed.containsKey(key)) return false
        return try {
            val binding = platform.read(key) ?: return true
            if (binding.id in platform.ownedIds()) platform.delete(binding.id)
            platform.write(key, null)
            true
        } catch (exception: Exception) {
            // A caller must retain the card/key when its binding could not be released. In
            // particular, malformed storage does not authorize deleting an arbitrary host ID.
            runCatching { platform.reportFailure("delete-binding", exception) }
            false
        }
    }
}

internal class WidgetSession(
    private val owner: WidgetSessions,
    private val platform: WidgetPlatform,
    val key: String,
    val provider: String,
    private val changed: () -> Unit,
) {
    var state = WidgetState.NEW
        internal set
    var id: Int? = null
        private set
    var error: String? = null
        private set
    private var complete = false
    private var restorationDone = false
    private var phase: WidgetPhase? = null
    private var savedProvider: String? = null
    private var allocatedHere = false
    private var observedOwned: Boolean? = null
    private var observedProviderMatches: Boolean? = null
    private var observedProviderExists: Boolean? = null
    private var diagnosedState: WidgetState? = null
    private var generation = 0
    private var cancelRequest: (() -> Unit)? = null
    private var resumed = false
    private var visible = true
    private var attached = false

    fun initialize() {
        if (restorationDone || state == WidgetState.CONFLICT || state == WidgetState.FINISHED) return
        guarded("initialize") {
            val saved = platform.read(key)
            if (saved != null) {
                id = saved.id
                savedProvider = saved.provider
                complete = saved.complete
                phase = saved.phase
                restorationDone = true
                recoverSaved()
            } else {
                state = WidgetState.NEED_BIND
                restorationDone = true
            }
        }
    }

    /** Only a deliberate placeholder click may allocate, bind, authorize or configure. */
    fun click() {
        if (!restorationDone) {
            initialize()
            if (!restorationDone) return
        }
        if (state in setOf(
                WidgetState.WAIT_BIND,
                WidgetState.WAIT_CONFIGURE,
                WidgetState.CONFLICT,
                WidgetState.FINISHED,
                WidgetState.READY
            )
        ) return
        guarded("click") {
            if (id != null) {
                recoverSaved()
                if (state == WidgetState.READY) return@guarded
            }
            if (!platform.providerExists(provider)) error("Widget provider unavailable")
            if (id == null) {
                val allocated = platform.allocate()
                id = allocated
                allocatedHere = true
                savedProvider = provider
                phase = WidgetPhase.ALLOCATED
                persist(allocated)
            }
            val current = requireNotNull(id)
            if (current !in platform.ownedIds()) error("Widget ID is not owned by this host")
            val actual = platform.providerOf(current)
            if (actual != null && actual != provider) error("Widget provider mismatch")
            if (actual == null && phase != null && phase != WidgetPhase.ALLOCATED) {
                error("Widget binding unavailable; tap to retry")
            }
            if (actual == provider || platform.bind(current, provider)) configureOrComplete(current)
            else launch(WidgetRequest.BIND, current)
        }
    }

    /** Absence is not proof of deletion: vendor services can temporarily return empty results. */
    private fun recoverSaved() {
        val current = requireNotNull(id)
        check(savedProvider == provider) { "Saved widget provider differs from selected provider" }
        observedOwned = current in platform.ownedIds()
        observedProviderExists = platform.providerExists(provider)
        val actual = platform.providerOf(current)
        observedProviderMatches = actual?.let { it == provider }
        if (actual == provider) rememberBound(current)
        check(observedOwned == true) { "Widget ownership unavailable; tap to retry" }
        check(observedProviderExists == true) { "Widget provider unavailable; tap to retry" }
        check(actual == null || actual == provider) { "Widget provider mismatch" }
        if (actual == null) {
            check(!complete && (phase == null || phase == WidgetPhase.ALLOCATED)) {
                "Widget binding unavailable; tap to retry"
            }
            state = WidgetState.NEED_BIND
        } else if (complete) {
            state = WidgetState.READY
        } else if (platform.needsConfiguration(provider)) {
            phase = WidgetPhase.BOUND
            persist(current)
            state = WidgetState.NEED_CONFIGURE
        } else {
            finishBinding(current)
        }
    }

    private fun rememberBound(current: Int) {
        if (!complete && (phase == null || phase == WidgetPhase.ALLOCATED)) {
            phase = WidgetPhase.BOUND
            persist(current)
        }
    }

    private fun persist(current: Int) {
        platform.write(key, WidgetBinding(current, provider, complete, phase))
    }

    private fun validate(current: Int) {
        observedOwned = current in platform.ownedIds()
        observedProviderExists = platform.providerExists(provider)
        observedProviderMatches = platform.providerOf(current)?.let { it == provider }
        if (observedProviderMatches == true) rememberBound(current)
        check(observedOwned == true) { "Widget ID is not owned by this host" }
        check(observedProviderExists == true && observedProviderMatches == true) { "Widget provider mismatch or unavailable" }
    }

    private fun configureOrComplete(current: Int) {
        validate(current)
        phase = WidgetPhase.BOUND
        persist(current)
        if (platform.needsConfiguration(provider)) launch(WidgetRequest.CONFIGURE, current)
        else finishBinding(current)
    }

    private fun finishBinding(current: Int) {
        validate(current)
        platform.write(key, WidgetBinding(current, provider, true, WidgetPhase.COMPLETE))
        complete = true
        phase = WidgetPhase.COMPLETE
        state = WidgetState.READY
    }

    private fun launch(kind: WidgetRequest, current: Int) {
        if (kind == WidgetRequest.CONFIGURE) {
            phase = WidgetPhase.CONFIGURING
            persist(current)
        }
        state =
            if (kind == WidgetRequest.BIND) WidgetState.WAIT_BIND else WidgetState.WAIT_CONFIGURE
        val token = ++generation
        val cancel = platform.request(kind, current, provider) { result ->
            if (token != generation || state == WidgetState.FINISHED || id != current) return@request
            cancelRequest = null
            ++generation
            guarded("activity-result") {
                if (!result.accepted || (kind == WidgetRequest.BIND && result.id != current) || (result.id != null && result.id != current)) {
                    recyclePending(explicit = true)
                    state = WidgetState.NEED_BIND
                } else {
                    // Failed observations retain the binding; they are not user cancellation.
                    validate(current)
                    // Configuration needs another deliberate click, never an automatic second Activity.
                    if (kind == WidgetRequest.BIND && platform.needsConfiguration(provider)) {
                        phase = WidgetPhase.BOUND
                        persist(current)
                        state = WidgetState.NEED_CONFIGURE
                    } else finishBinding(current)
                }
            }
        }
        if (token == generation) {
            cancelRequest = cancel
            if (cancel == null) {
                state =
                    if (kind == WidgetRequest.BIND) WidgetState.NEED_BIND else WidgetState.NEED_CONFIGURE
                error = "Open the launcher, then tap to continue"
            }
        }
    }

    fun lifecycle(resumed: Boolean? = null, visible: Boolean? = null, attached: Boolean? = null) {
        if (state == WidgetState.FINISHED || state == WidgetState.CONFLICT) return
        val resumeEdge = resumed == true && !this.resumed
        resumed?.let { this.resumed = it }
        visible?.let { this.visible = it }
        attached?.let { this.attached = it }
        guarded("lifecycle", clearError = resumeEdge) {
            owner.active(this, this.resumed && this.visible && this.attached)
            if (resumeEdge && state != WidgetState.WAIT_BIND && state != WidgetState.WAIT_CONFIGURE) {
                if (!restorationDone) initialize()
                else if (id != null) recoverSaved()
            }
        }
    }

    fun fail(message: String) {
        if (state == WidgetState.FINISHED || state == WidgetState.CONFLICT) return
        error = message
        state = WidgetState.ERROR
        notifyChanged()
    }

    fun finish() {
        if (state == WidgetState.FINISHED) return
        ++generation
        val cancel = cancelRequest
        cancelRequest = null
        try {
            cancel?.invoke()
            if (state != WidgetState.CONFLICT) recyclePending()
        } catch (exception: Exception) {
            reportFailure("finish", exception)
            error = exception.message
        } finally {
            state = WidgetState.FINISHED
            try {
                owner.close(this)
            } catch (exception: Exception) {
                reportFailure("close", exception)
                error = exception.message
            }
            notifyChanged()
        }
    }

    private fun recyclePending(explicit: Boolean = false) {
        if (complete) return
        val current = id ?: return
        // Destroy is not cancel. Preserve restored IDs and all bound/configuring IDs.
        if (!explicit && (!allocatedHere || phase != WidgetPhase.ALLOCATED)) return
        val owned = current in platform.ownedIds()
        val actual = platform.providerOf(current)
        if (!explicit && (!owned || actual != null)) return
        check(owned) { "Widget ownership unavailable; cancellation retained binding" }
        check(actual == null || actual == provider) { "Widget provider mismatch; cancellation retained binding" }
        platform.delete(current)
        platform.write(key, null)
        id = null
        savedProvider = null
        phase = null
        allocatedHere = false
    }

    private fun notifyChanged() {
        if (diagnosedState != state) {
            diagnosedState = state
            runCatching {
                platform.diagnose(
                    key, state, id, complete, phase,
                    observedOwned, observedProviderMatches, observedProviderExists
                )
            }
        }
        changed()
    }

    private fun reportFailure(operation: String, throwable: Throwable) {
        // Logging must never replace the original failure or interrupt recovery.
        runCatching { platform.reportFailure(operation, throwable) }
    }

    private inline fun guarded(operation: String, clearError: Boolean = true, block: () -> Unit) {
        try {
            if (clearError) error = null
            block()
        } catch (exception: Exception) {
            reportFailure(operation, exception)
            error = exception.message ?: exception.javaClass.simpleName
            state = WidgetState.ERROR
        }
        notifyChanged()
    }
}

/** Merge rather than replace provider options. Pixel sizes are rounded upwards to dp. */
internal object WidgetSizeOptions {
    fun merge(
        existing: Map<String, Int>,
        widthPx: Int,
        heightPx: Int,
        density: Float
    ): Map<String, Int> {
        require(density.isFinite() && density > 0)
        val width = kotlin.math.ceil(widthPx.coerceAtLeast(0) / density.toDouble()).toInt()
        val height = kotlin.math.ceil(heightPx.coerceAtLeast(0) / density.toDouble()).toInt()
        return existing + mapOf(
            "appWidgetMinWidth" to width, "appWidgetMaxWidth" to width,
            "appWidgetMinHeight" to height, "appWidgetMaxHeight" to height
        )
    }
}

/** Main-thread cache. Commit only after a successful platform call; retries must not be suppressed. */
internal class WidgetSizeCache {
    private val successful = mutableMapOf<Int, Map<String, Int>>()

    fun update(
        id: Int,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        apply: (Map<String, Int>) -> Unit
    ) {
        val sizes = WidgetSizeOptions.merge(emptyMap(), widthPx, heightPx, density)
        if (successful[id] == sizes) return
        apply(sizes)
        successful[id] = sizes
    }

    fun clear(id: Int) {
        successful.remove(id)
    }
}

/** Identity-aware host listener cleanup, with clear-all allowed only after the final live view leaves. */
internal class WidgetViewRegistry {
    private val live = mutableMapOf<Int, Any>()
    private val deferred = mutableSetOf<Int>()

    fun register(id: Int, view: Any) {
        live[id] = view
        deferred.remove(id) // createView replaces any previous listener for this ID.
    }

    fun release(id: Int, view: Any, removeSingle: () -> Boolean, clearAll: () -> Unit): Boolean {
        if (live[id] !== view) return true // A stale backend must not detach a newer view's listener.
        live.remove(id)
        if (removeSingle()) deferred.remove(id) else deferred.add(id)
        if (live.isEmpty() && deferred.isNotEmpty()) {
            clearAll()
            deferred.clear()
        }
        return id !in deferred
    }
}

internal data class WidgetNaturalSize(val widthPx: Int, val heightPx: Int)
internal data class WidgetFitTransform(val scale: Float, val left: Float, val top: Float) {
    fun naturalPoint(x: Float, y: Float): Pair<Float, Float>? =
        if (scale > 0f) (x - left) / scale to (y - top) / scale else null
}

/** Provider dimensions belong to the manager's display density, not necessarily the rear display. */
internal object WidgetNaturalGeometry {
    fun naturalSize(
        providerWidthPx: Int, providerHeightPx: Int, sourceDensity: Float, density: Float,
        paddingWidthPx: Int = 0, paddingHeightPx: Int = 0,
        fixedWidthPx: Int = 0, fixedHeightPx: Int = 0
    ): WidgetNaturalSize {
        require(sourceDensity.isFinite() && sourceDensity > 0 && density.isFinite() && density > 0)
        fun dimension(providerPx: Int, fallbackDp: Int, fixedPx: Int, paddingPx: Int): Int {
            val dp =
                if (providerPx > 0) providerPx / sourceDensity.toDouble() else fallbackDp.toDouble()
            val content = kotlin.math.ceil(dp * density).toInt().coerceAtLeast(1)
            return maxOf(content, fixedPx.coerceAtLeast(0)) + paddingPx.coerceAtLeast(0)
        }
        return WidgetNaturalSize(
            dimension(providerWidthPx, 240, fixedWidthPx, paddingWidthPx),
            dimension(providerHeightPx, 120, fixedHeightPx, paddingHeightPx)
        )
    }

    fun fit(size: WidgetNaturalSize, widthPx: Int, heightPx: Int): WidgetFitTransform {
        if (size.widthPx <= 0 || size.heightPx <= 0 || widthPx <= 0 || heightPx <= 0)
            return WidgetFitTransform(0f, 0f, 0f)
        val scale = minOf(widthPx.toFloat() / size.widthPx, heightPx.toFloat() / size.heightPx)
        return WidgetFitTransform(
            scale, (widthPx - size.widthPx * scale) / 2f,
            (heightPx - size.heightPx * scale) / 2f
        )
    }
}
