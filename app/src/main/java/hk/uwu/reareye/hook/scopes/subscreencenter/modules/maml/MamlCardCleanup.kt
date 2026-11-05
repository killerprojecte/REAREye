package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

/** Keep the card's last key mapping until discovery and every known widget release succeed. */
internal object MamlCardCleanup {
    fun deleteConfigIfReleased(
        bindingKeysKnown: Boolean,
        widgetReleaseResults: Collection<Boolean>,
        deleteConfig: () -> Unit
    ): Boolean {
        if (!bindingKeysKnown || widgetReleaseResults.any { !it }) return false
        deleteConfig()
        return true
    }
}
