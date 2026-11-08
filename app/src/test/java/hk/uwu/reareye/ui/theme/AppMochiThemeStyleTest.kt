package hk.uwu.reareye.ui.theme

import hk.uwu.mixui.MixMochiStyle
import hk.uwu.reareye.ui.components.config.MoreConfigGroups
import hk.uwu.reareye.ui.components.config.flattenMoreSourceItems
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.REAREyeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMochiThemeStyleTest {
    @Test fun storedValuesSelectAllThreeAppearances() {
        assertEquals(listOf(0, 1, 2), AppMochiThemeStyle.entries.map { it.value })
        assertEquals(MixMochiStyle.Default, AppMochiThemeStyle.fromValue(0).style)
        assertEquals(MixMochiStyle.Ios, AppMochiThemeStyle.fromValue(1).style)
        assertEquals(MixMochiStyle.Nothing, AppMochiThemeStyle.fromValue(2).style)
    }

    @Test fun unsetOrUnknownValuesRetainTheExistingMochiAppearance() {
        assertEquals(AppMochiThemeStyle.Mochi, AppMochiThemeStyle.default)
        for (value in listOf(-1, 3, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(AppMochiThemeStyle.default, AppMochiThemeStyle.fromValue(value))
        }
    }

    @Test fun appearanceIsEditedInsideTheSingleThemeEntry() {
        val items = flattenMoreSourceItems(REAREyeConfig)
        assertTrue(items.none { it.key == ConfigKeys.MODULE_MOCHI_THEME_STYLE })
        val group = MoreConfigGroups.single { it.id == "reareye_settings" }
        assertTrue(ConfigKeys.MODULE_THEME_MODE in group.itemKeys)
        assertTrue(ConfigKeys.MODULE_MOCHI_THEME_STYLE !in group.itemKeys)
        for (style in AppMochiThemeStyle.entries) {
            assertEquals(style, ThemeStyleDraft.from(AppThemeMode.MOCHI_DARK, style).mochiStyle)
        }
        assertNotEquals(ConfigKeys.MODULE_THEME_MODE, ConfigKeys.MODULE_MOCHI_THEME_STYLE)
    }
}
