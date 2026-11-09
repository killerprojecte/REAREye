package hk.uwu.mixui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import ui.feice.mochi.effect.glass.LocalMochiBackdrop

/**
 * Scene-backed modal with ordinary content controls. A null callback blocks Back and outside taps.
 * Keep this call composed through show=false so the native host can finish its exit animation.
 * Owners must also guard mutable busy state inside the callback: a busy transition and Back
 * can occur in the same UI turn, before this composable receives the updated null callback.
 *
 * Protected dialogs deliberately keep a stationary panel during predictive Back. Their native
 * enter/exit animation is unchanged. Each native host has an unattached public navigation root:
 * real Back is consumed outside it, so a veto cannot strand native back-progress or scrim state.
 */
@Composable
fun GlassOverlayDialog(
    show: Boolean,
    title: String,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    val scene = LocalGlassSheetScene.current
    if (scene == null) {
        check(!show) { "GlassOverlayDialog requires GlassBottomSheetScene" }
        return
    }
    var retained by remember { mutableStateOf(show) }
    var rendered by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) retained = true
        else if (!rendered) retained = false
    }
    if (!retained) return

    val token = remember { Any() }
    val callerLocals = currentCompositionLocalContext
    val currentShow by rememberUpdatedState(show)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val entry by rememberUpdatedState<@Composable () -> Unit>({
        CompositionLocalProvider(callerLocals) {
            // Always consume while retained, including busy and the complete exit animation.
            // Latch the starting permission; unlocking during an already blocked gesture must
            // not turn that same gesture into a new dismiss request.
            PredictiveBackHandler(enabled = true) { events ->
                val allowedAtStart = currentShow && currentDismiss != null
                events.collect { }
                if (allowedAtStart && currentShow) currentDismiss?.invoke()
            }
            val isolatedNavigation = rememberNavigationEventDispatcherOwner(parent = null)
            val finished = {
                if (!currentShow) {
                    rendered = false
                    retained = false
                }
            }
            val body: @Composable () -> Unit = {
                SideEffect { rendered = true }
                CompositionLocalProvider(LocalGlassFormControlContrast provides true) {
                    content()
                }
            }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides isolatedNavigation) {
                when (LocalMixUiBackend.current) {
                    MixUiBackend.Miuix -> {
                        val focus = LocalFocusManager.current
                        val keyboard = LocalSoftwareKeyboardController.current
                        val modalFocus = remember { FocusRequester() }
                        LaunchedEffect(show) {
                            if (show) {
                                // Unlike a separate Android Dialog, this overlay shares the
                                // page's focus owner. End its editing session before taking focus.
                                focus.clearFocus(force = true)
                                keyboard?.hide()
                                modalFocus.requestFocus()
                            }
                        }
                        Scaffold(
                            modifier = Modifier.fillMaxSize().recalculateWindowInsets()
                                .semantics { dialog() }
                                .focusRequester(modalFocus)
                                .focusProperties { onExit = { cancelFocusChange() } }
                                .focusable()
                                .pointerInput(Unit) {
                                    // Reject new pointer gestures during exit without intercepting
                                    // ordinary child gestures while the dialog is open.
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        if (!currentShow) {
                                            down.consume()
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                event.changes.forEach { it.consume() }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    }
                                },
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            top.yukonga.miuix.kmp.overlay.OverlayDialog(
                                show = show,
                                title = title,
                                summary = summary,
                                modifier = modifier,
                                onDismissRequest = if (show && onDismissRequest != null) ({
                                    if (currentShow) currentDismiss?.invoke()
                                }) else null,
                                onDismissFinished = finished,
                                backgroundColor = top.yukonga.miuix.kmp.glass.GlassDialogDefaults.containerColor(),
                                dimColor = top.yukonga.miuix.kmp.glass.GlassDialogDefaults.ScrimColor,
                                surface = top.yukonga.miuix.kmp.glass.glassDialogSurface(scene.backdrop),
                                // The local Scaffold is already outside the recorded page. Routing
                                // to the root would lose both caller locals and navigation isolation.
                                renderInRootScaffold = false,
                                content = body,
                            )
                        }
                    }
                    MixUiBackend.Mochi -> CompositionLocalProvider(
                        LocalMochiBackdrop provides scene.mochiBackdrop,
                    ) {
                        StableMochiFormDialog(
                            show = show,
                            title = title,
                            onDismissRequest = if (show && onDismissRequest != null) ({
                                if (currentShow) currentDismiss?.invoke()
                            }) else null,
                            modifier = modifier,
                            onDismissFinished = finished,
                        ) {
                            summary?.let {
                                Text(it, color = MixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = MixTheme.textStyles.body1.fontSize)
                            }
                            body()
                        }
                    }
                }
            }
        }
    })
    DisposableEffect(scene, token) {
        scene.modalDialogs[token] = Unit
        scene.entries[token] = { entry() }
        onDispose {
            scene.entries.remove(token)
            scene.modalDialogs.remove(token)
        }
    }
}
