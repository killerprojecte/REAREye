package hk.uwu.reareye.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Opaque semantic error colors, shared by notices, tool results and submission failures. */
internal data class MaryErrorColors(
    val container: Color,
    val content: Color,
    val border: Color,
)

/** Content also remains legible as a warning caption on ordinary tool containers. */
internal fun maryErrorColors(dark: Boolean): MaryErrorColors = if (dark) {
    MaryErrorColors(
        container = Color(0xff382126),
        content = Color(0xffffbdc5),
        border = Color(0xffb86a78),
    )
} else {
    MaryErrorColors(
        container = Color(0xfffff0f0),
        content = Color(0xff781c2c),
        border = Color(0xffbd6670),
    )
}

@Composable
internal fun maryErrorColors(): MaryErrorColors =
    maryErrorColors(dark = MiuixTheme.colorScheme.surfaceContainerHigh.luminance() < 0.5f)
