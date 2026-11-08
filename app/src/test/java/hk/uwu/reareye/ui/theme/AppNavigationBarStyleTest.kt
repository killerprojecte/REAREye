package hk.uwu.reareye.ui.theme

import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.components.NavigationBarStyle
import org.junit.Assert.*
import org.junit.Test

class AppNavigationBarStyleTest {
    @Test fun stableValuesMapToBothSupportedMiuixAppearances() {
        assertEquals(NavigationBarStyle.IOS, AppNavigationBarStyle.fromValue(0).style)
        assertEquals(NavigationBarStyle.HyperOS, AppNavigationBarStyle.fromValue(1).style)
        assertEquals(AppNavigationBarStyle.IOS, AppNavigationBarStyle.default)
        assertEquals(AppNavigationBarStyle.IOS, AppNavigationBarStyle.fromValue(Int.MAX_VALUE))
    }

    @Test fun switchingBackendPaletteAndBrightnessRetainsIndependentNavigationDraft() {
        val applied = ThemeStyleDraft.from(AppThemeMode.MONET_DARK, AppMochiThemeStyle.Nothing, AppNavigationBarStyle.HyperOS)
        val draft = applied.copy(backend = MixUiBackend.Mochi, brightness = 0)
            .copy(backend = MixUiBackend.Miuix, monet = false)
        assertEquals(AppNavigationBarStyle.HyperOS, draft.navigationStyle)
        assertEquals(AppMochiThemeStyle.Nothing, draft.mochiStyle)
        assertEquals(AppThemeMode.MIUIX_SYSTEM, draft.mode)
        assertEquals(AppThemeMode.MONET_DARK, applied.mode)
        assertEquals(AppNavigationBarStyle.IOS, ThemeStyleDraft.from(AppThemeMode.MIUIX_LIGHT, AppMochiThemeStyle.default).navigationStyle)
    }
}
