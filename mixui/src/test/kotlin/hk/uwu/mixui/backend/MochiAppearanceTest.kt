package hk.uwu.mixui.backend

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import hk.uwu.mixui.MixMochiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ui.feice.mochi.ios.IosPalette
import ui.feice.mochi.ios.iosColorScheme
import ui.feice.mochi.ios.iosControlPalette
import ui.feice.mochi.nothing.NothingColors
import ui.feice.mochi.nothing.nothingColorScheme
import ui.feice.mochi.nothing.nothingControlPalette
import ui.feice.mochi.theme.defaultTextStyles

class MochiAppearanceTest {
    @Test fun compactInformationHeadingsNeverUsePageHeadlineScale() {
        for (style in MixMochiStyle.entries) for (dark in listOf(false, true)) {
            val native = mochiAppearance(style, mochiPalette(Color.Blue, dark)).textStyles
            val mapped = MochiTextStyles(native)
            assertEquals(native.navTitle, mapped.headline1)
            assertEquals(17f, mapped.headline1.fontSize.value, 0f)
            assertEquals(native.titleMedium, mapped.headline2)
            assertEquals(16f, mapped.headline2.fontSize.value, 0f)
            assertEquals(native.titleSmall, mapped.subtitle)
            assertEquals(native.largeTitle, mapped.title1)
        }
    }

    @Test fun defaultPreservesTheExistingPaletteAndTypographyInBothModes() {
        for (dark in listOf(false, true)) {
            val original = mochiPalette(Color(0xFF26776B), dark)
            val appearance = mochiAppearance(MixMochiStyle.Default, original)
            assertSame(original, appearance.colors)
            assertEquals(defaultTextStyles(), appearance.textStyles)
            assertNull(appearance.controls)
            assertNull(appearance.iosPalette)
        }
    }

    @Test fun iosUsesNativeSemanticColorsAndGreenSwitchTracksInBothModes() {
        for (dark in listOf(false, true)) {
            val appearance = mochiAppearance(MixMochiStyle.Ios, mochiPalette(Color.Red, dark))
            assertEquals(dark, appearance.colors.isDark)
            assertEquals(iosColorScheme(dark), appearance.colors)
            assertEquals(iosControlPalette(dark), appearance.controls)
            assertEquals(IosPalette.of(dark), appearance.iosPalette)
            assertEquals(IosPalette.of(dark).green, appearance.controls?.switchTrack)
            assertEquals(defaultTextStyles(), appearance.textStyles)
        }
    }

    @Test fun nothingUsesNativeColorsControlsAndFontFamiliesInBothModes() {
        for (dark in listOf(false, true)) {
            val appearance = mochiAppearance(MixMochiStyle.Nothing, mochiPalette(Color.Blue, dark))
            assertEquals(dark, appearance.colors.isDark)
            assertEquals(nothingColorScheme(dark), appearance.colors)
            assertEquals(NothingColors.Accent, appearance.colors.primary)
            assertEquals(nothingControlPalette(dark), appearance.controls)
            assertEquals(NothingColors.Accent, appearance.controls?.tabSelected)
            assertEquals(FontFamily.Serif, appearance.textStyles.largeTitle.fontFamily)
            assertNotEquals(defaultTextStyles().body.fontFamily, appearance.textStyles.body.fontFamily)
            assertEquals(defaultTextStyles().body.fontSize, appearance.textStyles.body.fontSize)
            assertNull(appearance.iosPalette)
        }
    }

    @Test fun wallpaperSeedsDoNotOverrideIosOrNothingAppearances() {
        for (dark in listOf(false, true)) {
            for (style in listOf(MixMochiStyle.Ios, MixMochiStyle.Nothing)) {
                assertEquals(
                    mochiAppearance(style, mochiPalette(Color.Red, dark)),
                    mochiAppearance(style, mochiPalette(Color.Blue, dark)),
                )
            }
        }
    }
}
