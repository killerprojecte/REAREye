package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.Backdrop
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.MochiBackdrop

/** Internal scene recording; vendor types never enter the public form API. */
internal sealed interface GlassFormBackdrop {
    class Miuix(val value: Backdrop?) : GlassFormBackdrop
    class Mochi(val value: MochiBackdrop) : GlassFormBackdrop
}

internal val LocalGlassFormBackdrop = staticCompositionLocalOf<GlassFormBackdrop?> { null }

/**
 * Backend glass form presentation for [GlassTopBarScaffold] or [GlassPreferenceScene]'s overlayContent slot.
 * Keep this call composed when [show] becomes false: the host owns its exit
 * animation, keyboard dismissal and overlay removal.
 *
 * The form owns its validation and explicit confirm/cancel buttons in [content].
 * The host never dismisses after a submit callback on the caller's behalf. Back
 * and tapping the scrim request dismissal; IME avoidance stays in the native
 * overlay, outside the captured page. A missing host is a programming error,
 * not a silent null-backdrop imitation of glass.
 */
@Composable
fun GlassFormDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalGlassFormBackdrop.current
    if (backdrop == null) {
        check(!show) { "GlassFormDialog must be inside GlassTopBarScaffold.overlayContent or GlassPreferenceScene.overlayContent" }
        return
    }
    val direction = LocalLayoutDirection.current
    val styledContent: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalLayoutDirection provides direction,
            LocalGlassFormControlContrast provides true,
            content = content,
        )
    }
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> top.yukonga.miuix.kmp.glass.GlassDialog(
            show = show,
            title = title,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            backdrop = backdrop.value,
            // Match the official dialog material; extra white tint hides the page blur.
            renderInRootScaffold = true,
            content = styledContent,
        )
        is GlassFormBackdrop.Mochi -> CompositionLocalProvider(
            LocalMochiBackdrop provides backdrop.value,
        ) {
            StableMochiFormDialog(
                show = show,
                title = title,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                content = styledContent,
            )
        }
    }
}
