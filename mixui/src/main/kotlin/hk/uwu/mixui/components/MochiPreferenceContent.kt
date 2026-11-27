package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSectionFooter
import ui.feice.mochi.theme.MochiDefaults

/**
 * One native press surface for the title and the unbounded native description.
 * ListRow.subtitle is capped at two lines, and its press state is not public.
 * A full-size native row therefore supplies the library's actual highlight and
 * release/scroll-cancel behavior behind the non-clickable foreground row/footer.
 * Native trailing controls keep their own gestures and consume those taps once.
 */
@Composable
internal fun MochiPreferenceContent(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    row: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        // Native ListRow remains a merging semantics boundary even when it is
        // non-clickable. Give this full-row action its own accessible label; the Footer's
        // complete description merges naturally. Do not copy selected values:
        // those belong to the independent native trailing picker button.
        contentDescription = title
        if (!enabled) disabled()
        if (onClick != null) {
            role = Role.Button
            onClick {
                if (enabled) {
                    onClick()
                    true
                } else false
            }
        }
    }) {
        // matchParentSize does not participate in measurement: the full, wrapped
        // description determines the height. Never give the foreground row its
        // own onClick, which would intercept title taps and highlight half a row.
        if (onClick != null) {
            MochiListRow(
                title = "",
                enabled = enabled,
                onClick = onClick,
                modifier = Modifier.matchParentSize().clearAndSetSemantics {},
            )
        }
        Column(Modifier.fillMaxWidth()) {
            row()
            if (summary != null) {
                val color = MochiListDefaults.colors().secondary
                MochiListSectionFooter(
                    text = summary,
                    color = if (enabled) color else color.copy(alpha = color.alpha * MochiDefaults.DisabledAlpha),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }
        }
    }
}
