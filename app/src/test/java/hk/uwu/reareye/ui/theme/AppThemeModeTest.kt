package hk.uwu.reareye.ui.theme

import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.reareye.ui.components.config.MoreConfigGroups
import hk.uwu.reareye.ui.components.config.flattenMoreSourceItems
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.REAREyeConfig
import org.junit.Assert.*
import org.junit.Test

class AppThemeModeTest {
    @Test fun oldValuesKeepTheSameMiuixColorModes() {
        val colors = listOf(MixColorSchemeMode.Light, MixColorSchemeMode.Dark, MixColorSchemeMode.MonetSystem,
            MixColorSchemeMode.System, MixColorSchemeMode.MonetLight, MixColorSchemeMode.MonetDark)
        colors.forEachIndexed { value, color ->
            val mode = AppThemeMode.fromValue(value)
            assertEquals(value, mode.value)
            assertEquals(value, mode.selectionValue)
            assertEquals(MixUiBackend.Miuix, mode.backend)
            assertEquals(color, mode.colorSchemeMode)
        }
    }

    @Test fun newMochiValuesDeriveTheRealBackendAndColorMode() {
        val colors = listOf(MixColorSchemeMode.System, MixColorSchemeMode.Light, MixColorSchemeMode.Dark)
        colors.forEachIndexed { index, color ->
            val mode = AppThemeMode.fromValue(6 + index)
            assertEquals(MixUiBackend.Mochi, mode.backend)
            assertEquals(color, mode.colorSchemeMode)
            assertEquals(6 + index, mode.selectionValue)
        }
    }

    @Test fun lightDarkAndSystemWorkForEveryStoredMode() {
        for (mode in AppThemeMode.entries) {
            for (systemDark in listOf(false, true)) {
                val expected = when (mode.colorSchemeMode) {
                    MixColorSchemeMode.System, MixColorSchemeMode.MonetSystem -> systemDark
                    MixColorSchemeMode.Light, MixColorSchemeMode.MonetLight -> false
                    MixColorSchemeMode.Dark, MixColorSchemeMode.MonetDark -> true
                }
                assertEquals(mode.name, expected, mode.isDark(systemDark))
            }
        }
    }

    @Test fun onlyThreeMochiChoicesAppearAndLegacyMonetMapsToTheirLabels() {
        assertEquals(listOf(3, 0, 1, 2, 4, 5, 6, 7, 8), AppThemeMode.selectableEntries.map { it.value })
        assertEquals(3, AppThemeMode.selectableEntries.count { it.backend == MixUiBackend.Mochi })
        for (value in 9..11) {
            val legacy = AppThemeMode.fromValue(value)
            val choice = AppThemeMode.fromValue(value - 3)
            assertEquals(MixUiBackend.Mochi, legacy.backend)
            assertEquals(choice.value, legacy.selectionValue)
            assertEquals(choice.titleRes, legacy.titleRes)
            assertFalse(legacy in AppThemeMode.selectableEntries)
        }
    }

    @Test fun settingsHaveNoRetiredSearchStyleEntry() {
        assertFalse(flattenMoreSourceItems(REAREyeConfig).any { it.key == "module_search_bar_style" })
        assertFalse(MoreConfigGroups.any { "module_search_bar_style" in it.itemKeys })
    }

    @Test fun settingsHaveNoSeparateBackendEntry() {
        val items = flattenMoreSourceItems(REAREyeConfig)
        assertFalse(items.any { it.key == AppThemePreferences.LEGACY_BACKEND_KEY })
        assertFalse(MoreConfigGroups.any { AppThemePreferences.LEGACY_BACKEND_KEY in it.itemKeys })
        val type = items.single { it.key == ConfigKeys.MODULE_THEME_MODE }.type as ConfigType.EnumSingleSelect
        assertEquals(AppThemeMode.selectableEntries.map { it.value }, type.options.map { it.value })
        assertEquals(AppThemeMode.default.value, type.defaultValue)
        val settings = MoreConfigGroups.single { it.id == "reareye_settings" }.itemKeys
        assertEquals(ConfigKeys.MODULE_THEME_MODE, settings.first())
        assertFalse(ConfigKeys.MODULE_MOCHI_THEME_STYLE in settings)
    }

    @Test fun unknownValuesRemainOnTheExistingDefault() {
        for (value in listOf(-1, 12, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(AppThemeMode.MIUIX_SYSTEM, AppThemeMode.fromValue(value))
        }
        assertEquals(AppThemeMode.entries.size, AppThemeMode.entries.map { it.value }.distinct().size)
    }
}
