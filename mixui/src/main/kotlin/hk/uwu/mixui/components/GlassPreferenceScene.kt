package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 */
@Composable
fun GlassPreferenceScene(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> {
            val source = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
            PreferenceScene(
                modifier = modifier,
                sourceModifier = if (source != null) Modifier.layerBackdrop(source) else Modifier,
                backdrop = remember(source) { GlassFormBackdrop.Miuix(source) }, content = content,
            )
        }
        MixUiBackend.Mochi -> {
            val source = rememberMochiBackdrop(MixTheme.colorScheme.surface)
            PreferenceScene(
                modifier = modifier, sourceModifier = Modifier.mochiBackdropSource(source),
                backdrop = remember(source) { GlassFormBackdrop.Mochi(source) }, content = content,
            )
        }
    }
}

@Composable
private fun PreferenceScene(
    modifier: Modifier,
    sourceModifier: Modifier,
    backdrop: GlassFormBackdrop,
    content: @Composable () -> Unit,
) {
    Scaffold(modifier = modifier, containerColor = MixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
        GlassPreferenceOverlayHost(backdrop) {
            Box(Modifier.fillMaxSize().then(sourceModifier).background(MixTheme.colorScheme.surface)) { content() }
        }
    }
}
