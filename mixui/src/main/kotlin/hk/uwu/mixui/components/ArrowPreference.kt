package hk.uwu.mixui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.icons.Icons
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListRow

/**
 * A short action row using each backend's native list layout and pressed feedback.
 *
 * The backend owns its padding and pressed feedback. Forced held state, custom internal
 * margins and additional trailing/bottom content are deliberately outside this contract:
 * Mochi 0.2's native list row cannot represent all of those MIUIX-specific options.
 * Mochi uses a one-line title and two-line summary. Do not migrate rows that require
 * fully visible long or multiline descriptions until the native API can represent them.
 */
@Composable
fun ArrowPreference(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    // MIUIX removes its clickable modifier when disabled, but does not expose disabled
    // semantics itself. Keep one labelled row and the same disabled contract on both paths.
    val rowModifier = modifier.semantics(mergeDescendants = true) {
        if (!enabled) disabled()
    }
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(
            title = title,
            modifier = rowModifier,
            summary = summary,
            startAction = startAction,
            onClick = onClick,
            enabled = enabled,
        )
    } else {
        val layoutDirection = LocalLayoutDirection.current
        val colors = MochiListDefaults.colors()
        MochiListRow(
            title = title,
            modifier = rowModifier,
            subtitle = summary,
            leading = startAction,
            // The Maven native chevron is not mirrored in RTL. Use the native trailing
            // slot with our shared vector asset, reflecting only this decorative glyph.
            chevron = false,
            colors = colors,
            trailing = {
                Icon(
                    imageVector = Icons.Basic.ArrowRight,
                    contentDescription = null,
                    tint = colors.chevron,
                    modifier = Modifier.size(width = 10.dp, height = 16.dp).graphicsLayer {
                        scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                    },
                )
            },
            onClick = onClick,
            enabled = enabled,
        )
    }
}
