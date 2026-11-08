package hk.uwu.mixui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.movableContentOf
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
import hk.uwu.mixui.backend.mochiAppearance
import ui.feice.mochi.ios.LocalIosPalette
import ui.feice.mochi.theme.LocalMochiControlPalette

/** Stable application choices, independent of either vendor's theme classes. */
enum class MixUiBackend { Miuix, Mochi }
/** Mochi appearance only; MIUIX ignores this choice. No vendor theme types escape mixui. */
enum class MixMochiStyle { Default, Ios, Nothing }
enum class MixColorSchemeMode { System, Light, Dark, MonetSystem, MonetLight, MonetDark }

internal val LocalMixUiRenderer = staticCompositionLocalOf<MixUiRenderer> { MiuixBackend }
val LocalMixUiBackend = staticCompositionLocalOf { MixUiBackend.Miuix }

/** Owns both vendor themes during migration; application code only selects neutral tokens. */
@Composable
fun MixUiHost(
    backend: MixUiBackend = MixUiBackend.Miuix,
    colorMode: MixColorSchemeMode = MixColorSchemeMode.System,
    mochiStyle: MixMochiStyle = MixMochiStyle.Default,
    content: @Composable () -> Unit,
) {
    // Move only app content across vendor wrappers. Theme internals remain branch-local,
    // while remembered navigation, editors and drafts keep their composition identity.
    val currentContent = rememberUpdatedState(content)
    val hostedContent = remember { movableContentOf { currentContent.value() } }
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
                val appearance = remember(mochiStyle, palette) { mochiAppearance(mochiStyle, palette) }
                // Keep one theme/content call site: changing style must not reset screen state.
                CompositionLocalProvider(
                    LocalMochiControlPalette provides appearance.controls,
                    LocalIosPalette provides (appearance.iosPalette ?: LocalIosPalette.current),
                ) {
                    MochiTheme(
                        colors = ui.feice.mochi.theme.animateMochiColorScheme(appearance.colors),
                        textStyles = appearance.textStyles,
                        systemBarAppearance = false,
                        materials = ui.feice.mochi.ios.iosMaterials(
                            isDark = palette.isDark,
                            density = androidx.compose.ui.platform.LocalDensity.current.density,
                            glass = true,
                            blur = true,
                            themeAccent = mochiStyle != MixMochiStyle.Ios,
                        ),
                    ) {
                        val colors = MochiTheme.colorScheme
                        val styles = MochiTheme.textStyles
                        val windowDimming = ui.feice.mochi.theme.MochiDefaults.scrim()
                        CompositionLocalProvider(
                            LocalMixColors provides remember(colors, windowDimming) { MochiColors(colors, windowDimming) },
                            LocalMixTextStyles provides remember(styles) { MochiTextStyles(styles) },
                            content = hostedContent,
                        )
                    }
                }
            } else {
                val colors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
                val styles = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
                CompositionLocalProvider(
                    LocalMixColors provides remember(colors) { MiuixColors(colors) },
                    LocalMixTextStyles provides remember(styles) { MiuixTextStyles(styles) },
                    content = hostedContent,
                )
            }
        }
    }
}
