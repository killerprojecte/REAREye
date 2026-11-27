package hk.uwu.mixui.backend

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class MochiPaletteTest {
    @Test fun sharedMaterialKolorGeneratesBothMochiModesWithoutBinaryMismatch() {
        val seed = Color(0xFF2B6A5A)
        val light = mochiPalette(seed, false)
        val dark = mochiPalette(seed, true)
        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        assertTrue(light.surface.luminance() > dark.surface.luminance())
        assertEquals(light, mochiPalette(seed, false))
        assertNotEquals(light.primary, light.onPrimary)
        assertNotEquals(dark.primary, dark.onPrimary)
    }
}
