package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.layout.DialogDefaults

/**
 * Backend-neutral API for the ordinary separate-window dialog's established appearance.
 *
 * Keep the existing palette and spacing for both backends during host extraction. Substituting
 * MixTheme tokens here would change the Mochi appearance; that is a separate visual migration.
 * Vendor defaults and motion remain private implementation choices of mixui.
 */
object OverlayDialogDefaults {
    @Composable
    fun titleColor(): Color = DialogDefaults.titleColor()

    @Composable
    fun summaryColor(): Color = DialogDefaults.summaryColor()

    @Composable
    fun backgroundColor(): Color = DialogDefaults.backgroundColor()

    val outsideMargin: DpSize get() = DialogDefaults.outsideMargin
    val insideMargin: DpSize get() = DialogDefaults.insideMargin
}
