package hk.uwu.reareye.ui.theme

import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.components.NavigationBarStyle
import org.junit.Assert.*
import org.junit.Test

class AppNavigationBarStyleTest {
    @Test fun retiredPlacementOptionIsAbsentFromConfigurationTree() {
        fun children(node: hk.uwu.reareye.ui.config.ConfigNode): List<hk.uwu.reareye.ui.config.ConfigNode> = when (node) {
            is hk.uwu.reareye.ui.config.ConfigCategory -> node.children
            is hk.uwu.reareye.ui.config.ConfigGroup -> node.children
            else -> emptyList()
        }
        fun checkNode(node: hk.uwu.reareye.ui.config.ConfigNode) {
            assertNotEquals(hk.uwu.reareye.ui.config.ConfigKeys.MODULE_NAVIGATION_BAR_MODE, node.key)
            children(node).forEach(::checkNode)
        }
        hk.uwu.reareye.ui.config.REAREyeConfig.forEach(::checkNode)
    }


    @Test fun legacyIsMiuixOnlyAndOldPlacementCannotOverrideStyles() {
        assertEquals(AppNavigationBarStyle.Legacy, AppNavigationBarStyle.fromValue(2))
        for (style in AppNavigationBarStyle.entries) {
            assertEquals(hk.uwu.reareye.ui.config.ModuleNavigationBarMode.FLOATING_GLASS, style.mode(MixUiBackend.Mochi))
        }
        assertEquals(hk.uwu.reareye.ui.config.ModuleNavigationBarMode.NORMAL, AppNavigationBarStyle.Legacy.mode(MixUiBackend.Miuix))
        assertEquals(hk.uwu.reareye.ui.config.ModuleNavigationBarMode.FLOATING_GLASS, AppNavigationBarStyle.IOS.mode(MixUiBackend.Miuix))
        assertEquals(hk.uwu.reareye.ui.config.ModuleNavigationBarMode.FLOATING_GLASS, AppNavigationBarStyle.HyperOS.mode(MixUiBackend.Miuix))
    }

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
