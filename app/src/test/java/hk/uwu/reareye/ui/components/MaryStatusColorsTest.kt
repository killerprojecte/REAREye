package hk.uwu.reareye.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaryStatusColorsTest {
    @Test
    fun errorBodyMeetsNormalTextContrastInBothThemes() {
        for (dark in listOf(false, true)) {
            val colors = maryErrorColors(dark)
            assertContrast(colors.content, colors.container, 4.5f)
            assertEquals(1f, colors.container.alpha, 0f)
            assertEquals(1f, colors.content.alpha, 0f)
            assertEquals(1f, colors.border.alpha, 0f)
            assertNotEquals(colors.container, colors.content)
        }
    }

    @Test
    fun warningCaptionRemainsLegibleWithoutAnErrorContainer() {
        // Light tool surfaces, including a conservative gray darker than the usual surface.
        for (container in listOf(
            Color.White,
            Color(0xfff5f5f5),
            Color(0xffeeeeee),
            Color(0xffe0e0e0)
        )) {
            assertContrast(maryErrorColors(false).content, container, 4.5f)
        }
        // Confirmation cards use 0xff2c2c2c in dark mode; also cover neighboring surfaces.
        for (container in listOf(Color(0xff202020), Color(0xff2c2c2c), Color(0xff383838))) {
            assertContrast(maryErrorColors(true).content, container, 4.5f)
        }
    }

    @Test
    fun bordersStayDistinctAndThemesPreserveRedErrorSemantics() {
        for (dark in listOf(false, true)) {
            val colors = maryErrorColors(dark)
            assertContrast(colors.border, colors.container, 3f)
            assertTrue(colors.container.red > colors.container.green)
            assertTrue(colors.content.red > colors.content.green)
            assertNotEquals(colors.border, colors.content)
        }
        assertTrue(maryErrorColors(false).container.luminance() > 0.8f)
        assertTrue(maryErrorColors(true).container.luminance() < 0.05f)
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        // Compose computes relative luminance in linear sRGB, not average channel brightness.
        val foregroundLuminance = foreground.luminance()
        val backgroundLuminance = background.luminance()
        val contrast = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
                (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
        assertTrue("Expected contrast >= $minimum, got $contrast", contrast >= minimum)
    }
}
