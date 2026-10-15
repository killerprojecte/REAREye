package hk.uwu.reareye.hook.activityhost

internal data class ActivityHostAppliedSnapshot<S : Any, L : Any>(
    val surface: S, val surfaceEpoch: Long, val sequence: Long, val geometry: L,
)

/** The broker's actual main-queue reconciler. Surface epochs are independent of layout sequence. */
internal class ActivityHostReconciler<S : Any, L : Any>(
    private val post: (() -> Unit) -> Unit,
    private val attach: (S) -> Unit,
    private val detach: () -> Unit,
    private val release: (S) -> Unit,
    private val applyLayout: (L) -> Unit,
    private val applyVisibility: (Boolean) -> Unit,
    private val readyToLaunch: () -> Unit,
    private val failure: (String, Throwable) -> Unit,
    private val afterApply: (() -> Unit) -> Unit = { it() },
    private val settled: () -> Unit = {},
) {
    private var closed = false
    private var carrierReady = false
    private var initialized = false
    private var queued = false
    private var surfaceSequence = 0L
    private var surfaceEpoch = 0L
    private var attachedEpoch = 0L
    private var desiredSurface: S? = null
    private var attachedSurface: S? = null
    private val retired = mutableListOf<S>()
    private var layout: L? = null
    var layoutSequence = 0L
        private set
    private var appliedSequence = 0L
    private var visible = true
    private var appliedVisibility: Boolean? = null
    private var revision = 0L
    private var published: ActivityHostAppliedSnapshot<S, L>? = null

    @Synchronized
    fun desiredLayout(): L? = layout

    @Synchronized
    fun appliedSnapshot(): ActivityHostAppliedSnapshot<S, L>? {
        val snapshot = published ?: return null
        return snapshot.takeIf {
            !closed && !queued && appliedSequence == layoutSequence &&
                    attachedEpoch == surfaceEpoch && attachedSurface === desiredSurface &&
                    it.surface === attachedSurface && it.surfaceEpoch == surfaceEpoch &&
                    it.sequence == layoutSequence
        }
    }

    /** Keep submission and ACK linearizable without taking a Session monitor. */
    @Synchronized
    fun withAppliedSnapshot(action: (ActivityHostAppliedSnapshot<S, L>) -> Unit) {
        appliedSnapshot()?.let(action)
    }

    @Synchronized
    fun surface(value: S, geometry: L, sequence: Long): Boolean {
        if (closed || sequence <= surfaceSequence) {
            release(value); return false
        }
        surfaceSequence = sequence
        desiredSurface?.let { retired += it }
        desiredSurface = value
        surfaceEpoch++
        layout(geometry, sequence)
        schedule()
        return true
    }

    @Synchronized
    fun layout(value: L, sequence: Long): Boolean {
        if (closed || sequence <= layoutSequence) return false
        layout = value
        layoutSequence = sequence
        schedule()
        return true
    }

    @Synchronized
    fun carrierReady() {
        if (!closed) {
            carrierReady = true; schedule()
        }
    }

    @Synchronized
    fun initialized() {
        if (!closed) {
            initialized = true; schedule()
        }
    }

    @Synchronized
    fun taskCreated() {
        if (!closed) {
            appliedSequence = 0; appliedVisibility = null; schedule()
        }
    }

    @Synchronized
    fun visible(value: Boolean) {
        if (!closed) {
            visible = value; schedule()
        }
    }

    @Synchronized
    fun retryLaunch() {
        if (!closed) schedule()
    }

    private fun schedule() {
        revision++
        published = null
        if (queued || closed) return
        queued = true
        post { reconcile() }
    }

    private fun reconcile() {
        var phase = "surface_attach"
        try {
            val submission = synchronized(this) {
                queued = false
                if (closed || !carrierReady) return
                if (attachedEpoch != surfaceEpoch) {
                    if (attachedSurface != null) detach()
                    desiredSurface?.let(attach)
                    attachedSurface = desiredSurface
                    attachedEpoch = surfaceEpoch
                    appliedSequence = 0
                    appliedVisibility = null
                }
                while (retired.isNotEmpty()) release(retired.removeAt(0))
                if (attachedSurface == null) return
                phase = "bounds_reconcile"
                if (appliedSequence != layoutSequence) {
                    layout?.let(applyLayout)
                    appliedSequence = layoutSequence
                }
                phase = "visibility_reconcile"
                if (appliedVisibility != visible) {
                    applyVisibility(visible)
                    appliedVisibility = visible
                }
                Triple(
                    revision, initialized && layout != null, ActivityHostAppliedSnapshot(
                        requireNotNull(attachedSurface),
                        attachedEpoch,
                        appliedSequence,
                        requireNotNull(layout)
                    )
                )
            }
            // Session launch/dispose use a separate commit gate: never nest that gate inside
            // this monitor, since native Shell dispatch may read the desired geometry.
            phase = "launch_reconcile"
            if (submission.second) readyToLaunch()
            phase = "application_barrier"
            afterApply {
                val publish = synchronized(this) {
                    if (closed || queued || revision != submission.first) false
                    else {
                        published = submission.third; true
                    }
                }
                if (publish) settled()
            }
        } catch (error: Throwable) {
            failure(phase, error)
        }
    }

    @Synchronized
    fun cancel() {
        closed = true
    }

    @Synchronized
    fun close() {
        closed = true
        // Called after native carrier release on main; posted work can no longer attach anything.
        desiredSurface?.let { retired += it }
        desiredSurface = null
        attachedSurface = null
        retired.toList().forEach(release)
        retired.clear()
    }
}
