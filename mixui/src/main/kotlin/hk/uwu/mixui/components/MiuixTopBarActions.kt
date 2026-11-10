package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

// Matches the GlassTopAppBar actions in MIUIX ea1b935d's official GlassPage.
internal val MiuixTopBarActionSpacing = 8.dp

/** Keep adjacent glass circles apart without changing button size or hit targets. */
@Composable
internal fun MiuixTopBarActions(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MiuixTopBarActionSpacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
