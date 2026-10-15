package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

/** Explicit, non-persistent save/launch command. Seeing a token consumes it even if launch fails. */
internal class ActivityHostLaunchGate {
    private var instance = ""
    private var sequence = 0L

    fun accept(owner: String, component: String, dpi: Int, request: String): Boolean {
        if (request.isEmpty() || !MamlCardScopeFiles.validInstance(owner)) return false
        val fields = request.split('|')
        if (fields.size != 4 || fields[0] != owner || fields[1] != component || fields[2] != dpi.toString()) return false
        val nonce = fields[3].toLongOrNull() ?: return false
        if (nonce !in 1..9_007_199_254_740_991L || fields[3] != nonce.toString()) return false
        if (instance.isEmpty()) instance = owner
        if (instance != owner) return false
        if (nonce <= sequence) return false
        sequence = nonce
        return true
    }
}
