package hk.uwu.mixui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorControlsTest {
    @Test fun argbRoundTripPreservesBusinessValuesIncludingTransparency() {
        val channels = listOf(0, 1, 17, 64, 127, 128, 254, 255)
        channels.forEach { alpha -> channels.forEach { red -> channels.forEach { green -> channels.forEach { blue ->
            val argb = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            assertEquals(argb, HsvChannels.from(Color(argb)).color().toArgb())
        } } } }
    }

    @Test fun paletteSelectionChangesRgbButPreservesExactAlpha() {
        val current = Color(0.1f, 0.2f, 0.3f, 0.123456f)
        val selected = paletteSelection(Color.Cyan, current)
        assertEquals(current.alpha, selected.alpha, 0f)
        assertEquals(Color.Cyan, selected.copy(alpha = 1f))
    }

    @Test fun alphaDragDoesNotQuantizeRgbToPaletteCells() {
        val initial = Color(0xFF4279AB)
        val hsv = HsvChannels.from(initial)
        listOf(0f, 0.1f, 0.5f, 1f).forEach { alpha ->
            val next = hsv.copy(alpha = alpha).color()
            assertEquals(initial.toArgb(), next.copy(alpha = 1f).toArgb())
        }
    }

    @Test fun hueAndSaturationSurviveBrightnessZeroAndReturn() {
        val initial = HsvChannels(210f, 0.75f, 0.8f, 0.5f)
        val black = initial.copy(value = 0f)
        assertEquals(0f, black.color().red, 0f)
        assertEquals(initial.color(), black.copy(value = 0.8f).color())
        assertEquals(initial.color(), initial.copy(saturation = 0f).copy(saturation = 0.75f).color())
    }

    @Test fun paletteIncludesGrayEndpointsAndTwelveHues() {
        val swatches = paletteSwatches()
        assertEquals(91, swatches.size)
        assertEquals(91, swatches.map { it.toArgb() }.distinct().size)
        assertEquals(Color.White, swatches[12])
        assertEquals(Color.Black, swatches.last())
        assertEquals(12, swatches.take(12).distinct().size)
        assertTrue(swatches.all { it.alpha == 1f })
    }

    @Test fun hexPreviewIncludesLeadingZerosAndAlpha() {
        assertEquals("#00010203", colorHex(Color(0x00010203)))
        assertEquals("#804279AB", colorHex(Color(0x804279AB)))
    }
}
