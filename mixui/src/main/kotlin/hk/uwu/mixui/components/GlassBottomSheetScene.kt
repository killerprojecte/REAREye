package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

internal class GlassSheetScene(
    val backdrop: Backdrop?,
    val mochiBackdrop: ui.feice.mochi.effect.glass.MochiBackdrop,
) {
    val entries = mutableStateMapOf<Any, @Composable () -> Unit>()
    // Glass-sheet B contains the page plus scrim, never the sheet or controls.
    val glassSheetScrims = mutableStateMapOf<Any, Float>()
}

internal val LocalGlassSheetScene = staticCompositionLocalOf<GlassSheetScene?> { null }
internal val LocalGlassSheetActionAlpha = staticCompositionLocalOf { 0f }
internal val LocalGlassSheetActionBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Put inside the full-window root Scaffold, around its page and bars. Only that
 * page is recorded, with the opt-in Mochi host scrim added before its material
 * sampling. Sheet surfaces and controls remain sibling overlays outside that
 * recording. No cross-window backdrop is sampled.
 *
 * The source exists for either applied backend: a Mochi page may preview a MIUIX
 * draft without switching the applied theme. This host consumes no system insets.
 */
@Composable
fun GlassBottomSheetScene(content: @Composable () -> Unit) {
    val background = hk.uwu.mixui.theme.MixTheme.colorScheme.surface
    val backdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    } else null
    // Keep a public Mochi source even while the applied backend is MIUIX:
    // the draft theme can open a Mochi sheet without changing the underlying page.
    val mochiBackdrop = rememberMochiBackdrop(background)
    val scene = remember(backdrop, mochiBackdrop) { GlassSheetScene(backdrop, mochiBackdrop) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalGlassSheetScene provides scene) {
            // Both native overlays and our explicit glass-sheet entries are outside
            // this page/scrim source, so neither can record itself.
            Box(Modifier.fillMaxSize()
                .then(if (scene.glassSheetScrims.isNotEmpty()) Modifier.clearAndSetSemantics {} else Modifier)
                .mochiBackdropSource(mochiBackdrop)
                .then(if (backdrop != null && scene.entries.isNotEmpty()) Modifier.layerBackdrop(backdrop) else Modifier)) {
                content()
                scene.glassSheetScrims.values.forEach { alpha ->
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
                }
            }
        }
        scene.entries.forEach { (id, entry) -> key(id) { entry() } }
    }
}

/** Keep the caller's complete draft theme around the native title as well as its slots. */
@Composable
internal fun NativeGlassSheet(
    scene: GlassSheetScene,
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String?,
    startAction: (@Composable () -> Unit)?,
    endAction: (@Composable () -> Unit)?,
    allowDismiss: Boolean,
    isContentScrolled: Boolean,
    onDismissFinished: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val actionAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isContentScrolled) 1f else 0f,
        label = "sheetActionMaterial",
    )
    var retained by remember { mutableStateOf(show) }
    var rendered by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) retained = true
        // Native onDismissFinished is not called when hide precedes its first
        // visible frame. Remove that never-presented entry immediately.
        else if (!rendered) retained = false
    }
    if (!retained) return
    val token = remember { Any() }
    val callerLocals = currentCompositionLocalContext
    val currentShow by rememberUpdatedState(show)
    val entry by rememberUpdatedState<@Composable () -> Unit>({
        CompositionLocalProvider(callerLocals) {
            // A local popup owner preserves the caller's theme for MIUIX's native
            // header. Never route this entry back to the applied root theme.
            // Recalculate at full-window coordinates instead of inheriting a
            // scrolled page's consumed insets. Native sheet still owns top/IME.
            CompositionLocalProvider(
                LocalGlassSheetActionBackdrop provides scene.backdrop,
                LocalGlassSheetActionAlpha provides actionAlpha,
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize().recalculateWindowInsets().testTag("mixui-native-glass-sheet-host"),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    top.yukonga.miuix.kmp.glass.GlassBottomSheet(
                        show = show,
                        modifier = Modifier.testTag("mixui-native-glass-sheet"),
                        onDismissRequest = onDismissRequest,
                        title = title,
                        startAction = startAction,
                        endAction = endAction,
                        allowDismiss = allowDismiss,
                        backdrop = scene.backdrop,
                        insideMargin = androidx.compose.ui.unit.DpSize(horizontalPadding, androidx.compose.ui.unit.Dp(0f)),
                        renderInRootScaffold = false,
                        onDismissFinished = {
                            if (!currentShow) {
                                rendered = false
                                retained = false
                                onDismissFinished()
                            }
                        },
                        content = {
                            SideEffect { rendered = true }
                            CompositionLocalProvider(
                                LocalGlassTabsBackdrop provides scene.backdrop,
                                LocalGlassTabsSurfaceAlpha provides 1f,
                            ) { content() }
                        },
                    )
                }
            }
        }
    })
    DisposableEffect(scene, token) {
        scene.entries[token] = { entry() }
        onDispose { scene.entries.remove(token) }
    }
}
