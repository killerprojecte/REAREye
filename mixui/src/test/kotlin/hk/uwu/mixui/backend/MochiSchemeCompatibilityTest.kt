package hk.uwu.mixui.backend

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import ui.feice.mochi.theme.PaletteStyle
import ui.feice.mochi.theme.lightColorScheme
import ui.feice.mochi.theme.darkColorScheme

/** Exercise the upstream generator that the old palette bridge could only bypass. */
class MochiSchemeCompatibilityTest {
    @Test fun everyNativePaletteBuildsAgainstSharedMaterialKolor() {
        for (dark in listOf(false, true)) {
            for (style in PaletteStyle.entries) {
                val colors = if (dark) darkColorScheme(Color(0xFF26776B), style, 0.0)
                    else lightColorScheme(Color(0xFF26776B), style, 0.0)
                assertEquals(dark, colors.isDark)
                assertEquals(1f, colors.primary.alpha, 0f)
                assertEquals(1f, colors.surface.alpha, 0f)
            }
        }
    }
}
