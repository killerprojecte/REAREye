package hk.uwu.mixui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Save

enum class BottomSheetAction { Close, Save }

private class MochiSheetActionClickGate {
    var released = false
}

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
    val surfaceAlpha = LocalGlassSheetActionAlpha.current
    val buttonModifier = modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        if (!enabled) disabled()
    }
    if (LocalMixUiBackend.current == MixUiBackend.Mochi) {
        // Maven 0.2.0's native Glass body control creates its own padded canvas
        // backdrop. Do not give it transparent colors or a page source containing
        // itself. The native implementation owns disabled input and content tint.
        val clickGate = remember { MochiSheetActionClickGate() }
        ui.feice.mochi.basic.MochiIconButton(
            onClick = {
                if (clickGate.released) {
                    clickGate.released = false
                    onClick()
                }
            },
            modifier = buttonModifier
                // Semantics activation is independent of a touch release, including
                // when an accessibility action follows a canceled or held pointer.
                .semantics {
                    onClick {
                        if (enabled) {
                            onClick()
                            true
                        } else {
                            false
                        }
                    }
                }
                .pointerInput(clickGate) {
                    // Mochi 0.2.0 mistakes Compose's consumed synthetic cancel-up
                    // for a click. Cancellation visits the native child first, so
                    // allow its callback only during a real, unconsumed up dispatch.
                    // Observe without consuming to retain its press/drag animation.
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                clickGate.released = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.any { it.changedToUp() }
                                awaitPointerEvent(PointerEventPass.Final)
                                clickGate.released = false
                            }
                        }
                    } finally {
                        clickGate.released = false
                    }
                },
            enabled = enabled,
            style = ui.feice.mochi.basic.MochiButtonStyle.Glass,
            size = 44.dp,
            content = content,
        )
    } else if (glass && enabled) {
        top.yukonga.miuix.kmp.glass.GlassIconButton(
            onClick = onClick,
            // Keep the native icon node stable while only its material fades. Replacing
            // the whole button at zero can retire the icon's retained draw layer.
            modifier = buttonModifier.testTag(if (surfaceAlpha > 0f) "mixui-sheet-glass-action" else "mixui-sheet-plain-action"),
            // surfaceAlpha only controls the native colour/stroke layers. Detach
            // the sampler at zero so the idle button cannot retain a blurred disc.
            backdrop = backdrop.takeIf { surfaceAlpha > 0f },
            fill = androidx.compose.ui.graphics.Color.Transparent,
            inheritTopBarMaterial = false,
            surfaceAlpha = surfaceAlpha,
            size = 44.dp,
            content = content,
        )
    } else {
        // ea1b935's GlassIconButton has no enabled API. Use a genuinely disabled
        // native control, not a glass button with a live accessibility action.
        IconButton(
            onClick = onClick,
            modifier = buttonModifier.testTag("mixui-sheet-plain-action").alpha(if (enabled) 1f else 0.38f),
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
        tint = hk.uwu.mixui.theme.MixTheme.colorScheme.onSurface,
        modifier = Modifier.size(if (glass) 28.dp else 24.dp),
    )
}
