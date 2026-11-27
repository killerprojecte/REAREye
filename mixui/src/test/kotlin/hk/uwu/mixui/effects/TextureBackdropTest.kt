package hk.uwu.mixui.effects

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import top.yukonga.miuix.kmp.blur.BlurBlendMode

class TextureBackdropTest {
    @Test fun everyOwnedOperationMapsToTheOriginalBlendMode() {
        val expected = mapOf(
            TextureBlendOperation.ColorDodge to BlurBlendMode.ColorDodge,
            TextureBlendOperation.ColorBurn to BlurBlendMode.ColorBurn,
            TextureBlendOperation.LinearLight to BlurBlendMode.LinearLight,
            TextureBlendOperation.Lab to BlurBlendMode.Lab,
            TextureBlendOperation.Overlay to BlurBlendMode.Overlay,
            TextureBlendOperation.HardLight to BlurBlendMode.HardLight,
            TextureBlendOperation.Luminosity to BlurBlendMode.Luminosity,
            TextureBlendOperation.PlusDarker to BlurBlendMode.PlusDarker,
        )
        assertEquals(TextureBlendOperation.entries.toSet(), expected.keys)
        expected.forEach { (operation, mode) -> assertEquals(mode, operation.toVendorBlendMode()) }
    }

    @Test fun conversionPreservesColorAlphaOrderAndRepeatedLayers() {
        val layers = listOf(
            TextureBlendLayer(Color(0xe6a1a1a1), TextureBlendOperation.ColorDodge),
            TextureBlendLayer(Color(0x4de6e6e6), TextureBlendOperation.LinearLight),
            TextureBlendLayer(Color(0xff1af500), TextureBlendOperation.Lab),
            TextureBlendLayer(Color(0x4de6e6e6), TextureBlendOperation.LinearLight),
        )
        val converted = textureBlurColors(layers)
        assertEquals(layers.map { it.color }, converted.blendColors.map { it.color })
        assertEquals(
            layers.map { it.operation.toVendorBlendMode() },
            converted.blendColors.map { it.mode },
        )
        assertEquals(0f, converted.brightness, 0f)
        assertEquals(1f, converted.contrast, 0f)
        assertEquals(1f, converted.saturation, 0f)
    }

    @Test fun emptyRecipeDoesNotAddATintOrColorAdjustment() {
        val converted = textureBlurColors(emptyList())
        assertEquals(emptyList<Any>(), converted.blendColors)
        assertEquals(0f, converted.brightness, 0f)
        assertEquals(1f, converted.contrast, 0f)
        assertEquals(1f, converted.saturation, 0f)
    }
}
