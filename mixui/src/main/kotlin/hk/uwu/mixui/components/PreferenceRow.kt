package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/**
 * Configuration action row rendered by the selected native list component.
 * Mochi's native footer preserves full descriptions beyond its two-line subtitle
 * limit. Its own release/scroll-aware highlight owns feedback; it has no forced
 * held-state API, so holdDownState only applies to MIUIX.
 */
@Composable
fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
) {
    val rowModifier = modifier.semantics(mergeDescendants = onClick != null) {
        if (!enabled) disabled()
    }
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(
            title = title, modifier = rowModifier, summary = summary,
            startAction = startAction, onClick = onClick,
            enabled = enabled, holdDownState = holdDownState,
        )
    } else {
        MochiPreferenceContent(summary = summary, modifier = rowModifier, enabled = enabled, onClick = onClick) {
            ui.feice.mochi.list.MochiListRow(
                title = title,
                leading = startAction,
                chevron = true,
                onClick = onClick,
                enabled = enabled,
            )
        }
    }
}
