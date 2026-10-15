package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Minted only by a successful card commit; contains no root, Variables, View or task handle. */
class ActivityHostLaunchPermit internal constructor(
    val instance: String, val component: String, val dpi: Int, val request: String,
    private val issuedOrder: Long = newBoundary(),
) {
    internal fun isNewerThan(boundary: Long): Boolean = issuedOrder > boundary
    private val valid = AtomicBoolean(true)
    private val claimed = AtomicBoolean()
    internal fun matches(owner: String, activity: String, density: Int, token: String): Boolean =
        valid.get() && instance == owner && component == activity && dpi == density && request == token

    internal fun claim(owner: String, activity: String, density: Int, token: String): Boolean =
        matches(owner, activity, density, token) && claimed.compareAndSet(
            false,
            true
        ) && valid.get()

    internal fun isValid(): Boolean = valid.get()
    internal fun revoke() {
        valid.set(false)
    }

    companion object {
        private val order = AtomicLong()
        internal fun newBoundary(): Long = order.incrementAndGet()
    }
}
