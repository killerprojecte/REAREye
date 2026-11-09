package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop

/**
 * Native popup scene for pages without GlassTopBarScaffold, including embedded
 * editors. Content is recorded; popup hosts are siblings outside that recording.
 * Any Scaffold in [content] must disable its popupHost. This host owns no title,
 * list, insets or scroll behavior and does not change its caller's page layout.
 * Forms belong in [overlayContent], a sibling outside the recorded page. Keep
 * them composed during exit so the native host owns dismissal and IME behavior.
 */
@Composable
fun GlassPreferenceScene(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MixTheme.colorScheme.surface,
    overlayContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            val source = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
            PreferenceScene(
                modifier = modifier, backgroundColor = backgroundColor,
                sourceModifier = if (source != null) Modifier.layerBackdrop(source) else Modifier,
                backdrop = remember(source) { GlassFormBackdrop.Miuix(source) }, overlayContent = overlayContent, content = content,
            )
        }
        MixUiBackend.Mochi -> {
            val source = rememberMochiBackdrop(backgroundColor)
            PreferenceScene(
                modifier = modifier, backgroundColor = backgroundColor, sourceModifier = Modifier.mochiBackdropSource(source),
                backdrop = remember(source) { GlassFormBackdrop.Mochi(source) }, overlayContent = overlayContent, content = content,
            )
        }
    }
}

@Composable
private fun PreferenceScene(
    modifier: Modifier,
    backgroundColor: Color,
    sourceModifier: Modifier,
    backdrop: GlassFormBackdrop,
    overlayContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(modifier = modifier, containerColor = backgroundColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
        Box(Modifier.fillMaxSize()) {
            GlassPreferenceOverlayHost(backdrop) {
                Box(Modifier.fillMaxSize().then(sourceModifier).background(backgroundColor)) { content() }
            }
            CompositionLocalProvider(LocalGlassFormBackdrop provides backdrop) {
                overlayContent()
            }
        }
    }
}
