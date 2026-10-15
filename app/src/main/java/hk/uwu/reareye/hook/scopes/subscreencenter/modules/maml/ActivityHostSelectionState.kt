package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import hk.uwu.reareye.hook.activityhost.ActivityHostSpec

/** Persisted values only. Drafts and launch requests deliberately never enter this snapshot. */
internal data class ActivityHostSelectionState(
    val instance: String,
    val activePackage: String? = null,
    val entries: Map<String, Entry> = emptyMap(),
) {
    data class Entry(val component: String, val dpi: Int)

    fun validate(): ActivityHostSelectionState = apply {
        require(MamlCardScopeFiles.validInstance(instance)) { "Invalid card instance" }
        require(entries.size <= 256) { "Too many saved applications" }
        entries.forEach { (pkg, entry) ->
            require(ActivityHostSpec.parseComponent(entry.component)?.packageName == pkg)
            require(ActivityHostSpec.isValidDpi(entry.dpi))
        }
        require(activePackage == null || entries.containsKey(activePackage))
    }

    fun save(pkg: String, component: String, dpi: Int): ActivityHostSelectionState =
        copy(activePackage = pkg, entries = entries + (pkg to Entry(component, dpi))).validate()

    fun reset(): ActivityHostSelectionState = copy(activePackage = null)
    fun remove(pkg: String): ActivityHostSelectionState =
        copy(activePackage = activePackage.takeUnless { it == pkg }, entries = entries - pkg)

    /** Strict wire grammar prevents ambiguous delimiters and precision loss in MAML doubles. */
    data class Action(
        val instance: String, val operation: String, val pkg: String,
        val component: String, val dpi: Int, val sequence: Long
    ) {
        companion object {
            fun parse(raw: String): Action {
                require(raw.length <= 2048)
                val fields = raw.split('|')
                require(fields.size == 6)
                require(MamlCardScopeFiles.validInstance(fields[0]))
                require(fields[1] in setOf("load", "save", "reset"))
                val dpi = requireNotNull(fields[4].toIntOrNull())
                val sequence = requireNotNull(fields[5].toLongOrNull())
                require(sequence in 1..9_007_199_254_740_991L)
                require(fields[5] == sequence.toString())
                return Action(fields[0], fields[1], fields[2], fields[3], dpi, sequence)
            }
        }
    }
}
