package hk.uwu.reareye.ui.theme

import hk.uwu.mixui.components.NavigationBarStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode

/** Stable persisted MIUIX navigation appearance, independent of placement and theme palette. */
enum class AppNavigationBarStyle(val value: Int, val style: NavigationBarStyle) {
    IOS(0, NavigationBarStyle.IOS),
    HyperOS(1, NavigationBarStyle.HyperOS),
    Legacy(2, NavigationBarStyle.IOS);

    /** The retired placement selector must never override a selected native style. */
    fun mode(backend: MixUiBackend): ModuleNavigationBarMode =
        if (backend == MixUiBackend.Miuix && this == Legacy) ModuleNavigationBarMode.NORMAL
        else ModuleNavigationBarMode.FLOATING_GLASS

    companion object {
        val default = IOS
        fun fromValue(value: Int): AppNavigationBarStyle = entries.firstOrNull { it.value == value } ?: default
    }
}
