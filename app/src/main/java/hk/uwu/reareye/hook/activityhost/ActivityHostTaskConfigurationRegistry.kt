package hk.uwu.reareye.hook.activityhost

import java.util.IdentityHashMap

/** Pure ownership ledger: no package-wide overrides and no dependence on task IDs being unique. */
internal class ActivityHostTaskConfigurationRegistry<C : Any, L : Any>(
    private val limit: Int = 4,
) {
    data class Spec(
        val component: String,
        val displayId: Int,
        val dpi: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        init {
            require(component.isNotBlank() && component.length <= 512)
            require(displayId == 1)
            require(dpi == 0 || dpi in 120..960)
            // Coordinate origin belongs to the display adapter, not this ownership ledger.
            require(right.toLong() - left.toLong() in 2L..8192L)
            require(bottom.toLong() - top.toLong() in 2L..8192L)
        }
    }

    private data class Launch<L>(val lifetime: L, val uid: Int, val spec: Spec)

    private val launches = HashMap<C, Launch<L>>()
    private val tasks = IdentityHashMap<Any, C>()
    private val configured = HashSet<C>()

    @Synchronized
    fun register(cookie: C, lifetime: L, uid: Int, spec: Spec): Boolean {
        val existing = launches[cookie]
        if (existing != null) {
            // Replays are idempotent; neither stale owners nor live geometry/DPI replacements win.
            require(existing.lifetime == lifetime && existing.uid == uid && existing.spec == spec) {
                "Launch cookie already registered"
            }
            return false
        }
        check(launches.size < limit) { "Launch registry full" }
        launches[cookie] = Launch(lifetime, uid, spec)
        return true
    }

    @Synchronized
    fun resolve(task: Any, cookies: List<C>, component: String, displayId: Int): Spec? {
        tasks[task]?.let { cookie ->
            return launches[cookie]?.spec?.takeIf { it.displayId == displayId }
        }
        val matches = cookies.distinct().filter { cookie ->
            launches[cookie]?.spec?.let { it.component == component && it.displayId == displayId } == true
        }
        if (matches.size != 1) return null
        val cookie = matches.single()
        // A launch cookie cannot acquire two independent tasks, even within the same package.
        if (tasks.values.any { it == cookie }) return null
        tasks[task] = cookie
        return launches[cookie]?.spec
    }

    /** Called only after the final Activity configuration hook actually succeeds. */
    @Synchronized
    fun markConfigured(task: Any): Boolean {
        val cookie = tasks[task] ?: return false
        if (!launches.containsKey(cookie)) return false
        configured.add(cookie)
        return true
    }

    @Synchronized
    fun isConfigured(cookie: C, uid: Int): Boolean {
        val launch = launches[cookie] ?: return false
        if (launch.uid != uid) throw SecurityException("Launch cookie owner mismatch")
        return cookie in configured && tasks.values.any { it == cookie }
    }

    @Synchronized
    fun unregister(cookie: C, uid: Int): List<Any> {
        val existing = launches[cookie] ?: return emptyList()
        require(existing.uid == uid) { "Launch cookie owner mismatch" }
        return remove(cookie)
    }

    @Synchronized
    fun lifetimeDied(lifetime: L): List<Any> = launches.entries
        .filter { it.value.lifetime == lifetime }.map { it.key }.flatMap(::remove)

    @Synchronized
    fun clear(): List<Any> = launches.keys.toList().flatMap(::remove)

    @Synchronized
    fun hasLifetime(lifetime: L): Boolean = launches.values.any { it.lifetime == lifetime }

    @Synchronized
    fun isEmpty(): Boolean = launches.isEmpty()

    private fun remove(cookie: C): List<Any> {
        launches.remove(cookie)
        configured.remove(cookie)
        val owned = tasks.entries.filter { it.value == cookie }.map { it.key }
        owned.forEach(tasks::remove)
        return owned
    }
}
