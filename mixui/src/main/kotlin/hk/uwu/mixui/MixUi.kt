package hk.uwu.mixui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import hk.uwu.mixui.backend.MiuixBackend
import hk.uwu.mixui.backend.MochiBackend
import hk.uwu.mixui.backend.MixUiRenderer
import hk.uwu.mixui.backend.MiuixColors
import hk.uwu.mixui.backend.MochiColors
import hk.uwu.mixui.backend.MiuixTextStyles
import hk.uwu.mixui.backend.MochiTextStyles
import hk.uwu.mixui.theme.LocalMixColors
import hk.uwu.mixui.theme.LocalMixTextStyles
import ui.feice.mochi.theme.MochiTheme
import hk.uwu.mixui.backend.rememberMochiPalette

/** Stable application choices, independent of either vendor's theme classes. */
enum class MixUiBackend { Miuix, Mochi }
enum class MixColorSchemeMode { System, Light, Dark, MonetSystem, MonetLight, MonetDark }

internal val LocalMixUiRenderer = staticCompositionLocalOf<MixUiRenderer> { MiuixBackend }
val LocalMixUiBackend = staticCompositionLocalOf { MixUiBackend.Miuix }

/** Owns both vendor themes during migration; application code only selects neutral tokens. */
@Composable
fun MixUiHost(backend: MixUiBackend = MixUiBackend.Miuix, colorMode: MixColorSchemeMode = MixColorSchemeMode.System, content: @Composable () -> Unit) {
    val renderer = when (backend) {
        MixUiBackend.Miuix -> MiuixBackend
        MixUiBackend.Mochi -> MochiBackend
    }
    val miuixController = remember(colorMode) {
        top.yukonga.miuix.kmp.theme.ThemeController(top.yukonga.miuix.kmp.theme.ColorSchemeMode.valueOf(colorMode.name))
    }
    top.yukonga.miuix.kmp.theme.MiuixTheme(miuixController) {
        CompositionLocalProvider(LocalMixUiBackend provides backend, LocalMixUiRenderer provides renderer) {
            if (backend == MixUiBackend.Mochi) {
                val palette = rememberMochiPalette(colorMode)
                MochiTheme(colors = ui.feice.mochi.theme.animateMochiColorScheme(palette), systemBarAppearance = false) {
                    val colors = MochiTheme.colorScheme
                    val styles = MochiTheme.textStyles
                    CompositionLocalProvider(
                        LocalMixColors provides remember(colors) { MochiColors(colors) },
                        LocalMixTextStyles provides remember(styles) { MochiTextStyles(styles) },
                        content = content,
                    )
                }
            } else {
                val colors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
                val styles = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
                CompositionLocalProvider(
                    LocalMixColors provides remember(colors) { MiuixColors(colors) },
                    LocalMixTextStyles provides remember(styles) { MiuixTextStyles(styles) },
                    content = content,
                )
            }
        }
    }
}
