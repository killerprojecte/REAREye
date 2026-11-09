package hk.uwu.mixui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListSectionFooter
import ui.feice.mochi.theme.MochiDefaults

/**
 * Compose native list controls with native supporting text. Maven Mochi 0.2.0
 * hard-caps ListRow.subtitle at two lines. Its section footer is the native,
 * unbounded description API, so configuration descriptions must use that slot
 * instead of silently truncating them or rebuilding the library's row.
 */
@Composable
internal fun MochiPreferenceContent(
    summary: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    row: @Composable () -> Unit,
) {
    // Native children retain their own gestures and feedback. Their consumed
    // presses cancel this parent's standard clickable, so a title/control tap
    // runs exactly once. The same release/scroll-aware action also covers the
    // native footer and its padding, without drawing a substitute row or ripple.
    val interaction = remember { MutableInteractionSource() }
    val activation = if (onClick != null) Modifier.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    ) else Modifier
    Column(modifier.fillMaxWidth().then(activation).semantics { if (!enabled) disabled() }) {
        row()
        if (summary != null) {
            val color = MochiListDefaults.colors().secondary
            MochiListSectionFooter(
                text = summary,
                color = if (enabled) color else color.copy(alpha = color.alpha * MochiDefaults.DisabledAlpha),
                // Give the native footer the available row width instead of
                // shrink-wrapping its text; retain native padding and wrapping.
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}
