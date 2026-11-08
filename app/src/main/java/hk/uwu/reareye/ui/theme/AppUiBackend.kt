package hk.uwu.reareye.ui.theme

import androidx.annotation.StringRes
import hk.uwu.mixui.MixUiBackend
import hk.uwu.reareye.R

/** Persist stable values rather than enum ordinals, keeping existing installs on MIUIX. */
enum class AppUiBackend(val value: Int, @param:StringRes val titleRes: Int, val backend: MixUiBackend) {
    Miuix(0, R.string.module_ui_backend_miuix, MixUiBackend.Miuix),
    Mochi(1, R.string.module_ui_backend_mochi, MixUiBackend.Mochi);

    companion object {
        val default = Miuix
        fun fromValue(value: Int): AppUiBackend = entries.firstOrNull { it.value == value } ?: default
    }
}
