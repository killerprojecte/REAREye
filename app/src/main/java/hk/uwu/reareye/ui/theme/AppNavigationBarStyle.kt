package hk.uwu.reareye.ui.theme

import hk.uwu.mixui.components.NavigationBarStyle

/** Stable persisted MIUIX navigation appearance, independent of placement and theme palette. */
enum class AppNavigationBarStyle(val value: Int, val style: NavigationBarStyle) {
    IOS(0, NavigationBarStyle.IOS),
    HyperOS(1, NavigationBarStyle.HyperOS);

    companion object {
        val default = IOS
        fun fromValue(value: Int): AppNavigationBarStyle = entries.firstOrNull { it.value == value } ?: default
    }
}
