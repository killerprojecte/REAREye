package hk.uwu.reareye.ui.theme

import androidx.annotation.StringRes
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.reareye.R

/** Stable preference values, separate from the component backend and light/dark mode. */
enum class AppMochiThemeStyle(
    val value: Int,
    @param:StringRes val titleRes: Int,
    val style: MixMochiStyle,
) {
    Mochi(0, R.string.module_mochi_theme_style_default, MixMochiStyle.Default),
    Ios(1, R.string.module_mochi_theme_style_ios, MixMochiStyle.Ios),
    Nothing(2, R.string.module_mochi_theme_style_nothing, MixMochiStyle.Nothing);

    companion object {
        val default = Mochi
        fun fromValue(value: Int): AppMochiThemeStyle = entries.firstOrNull { it.value == value } ?: default
    }
}
