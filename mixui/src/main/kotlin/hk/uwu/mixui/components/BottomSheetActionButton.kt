package hk.uwu.mixui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Save

enum class BottomSheetAction { Close, Save }

/** Only native sheet header actions use this; Dialog form controls stay ordinary. */
@Composable
fun BottomSheetActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalGlassSheetActionBackdrop.current
    val glass = LocalMixUiBackend.current == MixUiBackend.Miuix && backdrop != null
    val buttonModifier = modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        if (!enabled) disabled()
    }
    if (LocalMixUiBackend.current == MixUiBackend.Mochi) {
        // Maven 0.2.0's native Glass body control creates its own padded canvas
        // backdrop. Do not give it transparent colors or a page source containing
        // itself. The native implementation owns disabled input and content tint.
        ui.feice.mochi.basic.MochiIconButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            style = ui.feice.mochi.basic.MochiButtonStyle.Glass,
            size = 44.dp,
            content = content,
        )
    } else if (glass && enabled) {
        top.yukonga.miuix.kmp.glass.GlassIconButton(
            onClick = onClick,
            modifier = buttonModifier,
            backdrop = backdrop,
            inheritTopBarMaterial = false,
            size = 44.dp,
            content = content,
        )
    } else {
        // ea1b935's GlassIconButton has no enabled API. Use a genuinely disabled
        // native control, not a glass button with a live accessibility action.
        IconButton(
            onClick = onClick,
            modifier = buttonModifier.alpha(if (enabled) 1f else 0.38f),
            enabled = enabled,
            minWidth = 44.dp,
            minHeight = 44.dp,
            content = content,
        )
    }
}

/** Upstream has Glass.Save, not Glass.Check; ordinary callers retain their check icon. */
@Composable
fun BottomSheetActionIcon(
    action: BottomSheetAction,
    ordinaryIcon: ImageVector,
    contentDescription: String?,
) {
    val glass = LocalMixUiBackend.current == MixUiBackend.Miuix && LocalGlassSheetActionBackdrop.current != null
    Icon(
        imageVector = if (glass) when (action) {
            BottomSheetAction.Close -> MiuixIcons.Glass.Close
            BottomSheetAction.Save -> MiuixIcons.Glass.Save
        } else ordinaryIcon,
        contentDescription = contentDescription,
        modifier = Modifier.size(if (glass) 28.dp else 24.dp),
    )
}
