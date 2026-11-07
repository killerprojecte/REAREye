package hk.uwu.mixui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import hk.uwu.mixui.backend.MiuixBackend
import hk.uwu.mixui.backend.MochiBackend
import hk.uwu.mixui.backend.MixUiRenderer
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.theme.ColorSchemeMode

/** Stable application choices, independent of either vendor's theme classes. */
enum class MixUiBackend { Miuix, Mochi }

internal val LocalMixUiRenderer = staticCompositionLocalOf<MixUiRenderer> { MiuixBackend }
val LocalMixUiBackend = staticCompositionLocalOf { MixUiBackend.Miuix }

/** During migration the application still supplies its existing MIUIX theme outside this host. */
@Composable
fun MixUiHost(backend: MixUiBackend = MixUiBackend.Miuix, dark: Boolean, content: @Composable () -> Unit) {
    val renderer = when (backend) {
        MixUiBackend.Miuix -> MiuixBackend
        MixUiBackend.Mochi -> MochiBackend
    }
    CompositionLocalProvider(LocalMixUiBackend provides backend, LocalMixUiRenderer provides renderer) {
        if (backend == MixUiBackend.Mochi) {
            val controller = remember(dark) { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            MochiTheme(controller = controller, systemBarAppearance = false, content = content)
        } else content()
    }
}
