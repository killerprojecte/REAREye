#!/usr/bin/env python3
"""Source contracts for the decorative-effects migration; no JVM or device needed."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/hk/uwu/reareye"
EFFECTS = ROOT / "mixui/src/main/kotlin/hk/uwu/mixui/effects"


def compact(source):
    return re.sub(r"\s+", "", source)


def calls(source, name):
    """Extract balanced call arguments, including nested shape/color constructors."""
    for match in re.finditer(r"\b" + name + r"\(", source):
        start = match.end()
        depth = 1
        for index in range(start, len(source)):
            depth += (source[index] == "(") - (source[index] == ")")
            if not depth:
                yield compact(source[start:index])
                break
        else:
            raise AssertionError(f"Unbalanced {name} call")


class DecorativeEffectsContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.about = (APP / "ui/screen/AboutScreen.kt").read_text()
        cls.texture = (EFFECTS / "TextureBackdrop.kt").read_text()
        cls.gradient = (EFFECTS / "AnimatedGradientBackground.kt").read_text()

    def test_app_boundary_and_unused_utilities(self):
        paths = [APP / "ui/screen/AboutScreen.kt", APP / "ui/screen/MaryScreen.kt",
                 APP / "ui/components/config/SceneRouteManagerScreen.kt", APP / "utils/PageUtils.kt"]
        paths += list((APP / "utils/blend").glob("*.kt"))
        paths += list((APP / "utils/effect").glob("*.kt"))
        for path in paths:
            self.assertNotIn("top.yukonga.miuix", path.read_text(), str(path))
        for name in ["BlurExt.kt", "BlurToken.kt"]:
            self.assertFalse((APP / "utils/blend" / name).exists())
        self.assertNotIn("BlurredBar", (APP / "utils/PageUtils.kt").read_text())
        self.assertTrue((APP / "utils/effect/FrameSampler.kt").exists())

    def test_source_is_background_only_and_surface_precedes_fading_shader(self):
        args, = calls(self.about, "AnimatedGradientBackground")
        self.assertIn("backgroundModifier=Modifier.textureBackdropSource(backdrop)", args)
        self.assertIn("alpha={1f-scrollProgress}", args)
        self.assertEqual(1, self.about.count(".textureBackdropSource("))
        source = compact(self.gradient)
        self.assertEqual(1, source.count(".then(backgroundModifier)"))
        self.assertIn("Canvas(modifier=Modifier.fillMaxSize().then(backgroundModifier),)", source)
        self.assertLess(source.index("drawRect(surface)"), source.index("drawRect(painter.runtimeShader.asBrush(),alpha=alpha())"))
        self.assertLess(source.index("drawRect(painter.runtimeShader.asBrush(),alpha=alpha())"), source.index("content()"))
        self.assertIn("if(isFullSize)size.heightelsesize.height*0.78f", source)
        self.assertLess(source.index("if(!shaderSupported)"), source.index("rememberFrameTimeSeconds(dynamicBackground)"))
        self.assertIn("modifier=modifier.background(surface)", source)

    def test_card_and_mask_parameters_remain_exact(self):
        blur_calls = list(calls(self.about, "textureBlur"))
        self.assertEqual(7, len(blur_calls))
        logo, title, *cards = blur_calls
        for args, corners in [(logo, 24), (title, 16)]:
            self.assertIn(f"shape=RoundedCornerShape({corners}.dp)", args)
            self.assertIn("blurRadius=150.dp", args)
            self.assertIn("contentBlendMode=BlendMode.DstIn", args)
            self.assertIn("blendLayers=visualTokens.logoBlendColors", args)
            self.assertNotIn("noiseCoefficient", args)
        for args in cards:
            self.assertIn("shape=RoundedCornerShape(16.dp)", args)
            self.assertIn("blurRadius=60.dp", args)
            self.assertIn("noiseCoefficient=0.001f", args)
            self.assertIn("blendLayers=visualTokens.cardBlendColors", args)
            self.assertNotIn("contentBlendMode", args)
        self.assertTrue(all("backdrop=backdrop" in args and "enabled=true" in args for args in blur_calls))

    def test_blend_recipes_retain_colors_and_order(self):
        expected_logo = [
            ("0xe6a1a1a1", "ColorDodge"), ("0x4de6e6e6", "LinearLight"), ("0xff1af500", "Lab"),
            ("0xcc4a4a4a", "ColorBurn"), ("0xff4f4f4f", "LinearLight"), ("0xff1af200", "Lab"),
        ]
        pattern = r"TextureBlendLayer\(Color\((0x[0-9a-fA-F]+)\),\s*TextureBlendOperation\.(\w+)\)"
        self.assertEqual(expected_logo, re.findall(pattern, self.about))
        presets = (APP / "utils/blend/ColorBlendToken.kt").read_text()
        self.assertEqual([
            ("0x340034F9", "Overlay"), ("0xB3FFFFFF", "HardLight"),
            ("0x4DA9A9A9", "Luminosity"), ("0x1A9C9C9C", "PlusDarker"),
        ], re.findall(pattern, presets))
        self.assertIn("if(isDarkTheme){ColorBlendToken.Overlay_Thin_Light}else{ColorBlendToken.Pured_Regular_Light}", compact(self.about))

    def test_neutral_adapter_preserves_units_defaults_and_visibility(self):
        source = compact(self.texture)
        self.assertIn("classTextureBackdropinternalconstructor(internalvallayer:LayerBackdrop)", source)
        self.assertIn("blurRadius:Dp", source)
        self.assertIn("blurRadius=blurRadius.value", source)
        self.assertNotIn("toPx", source)
        self.assertIn("noiseCoefficient:Float=BlurDefaults.NoiseCoefficient", source)
        self.assertIn("contentBlendMode:BlendMode=BlendMode.SrcOver", source)
        self.assertIn("brightness=0f,contrast=1f,saturation=1f", source)
        self.assertIn("internalfunTextureBlendOperation.toVendorBlendMode():BlurBlendMode", source)
        self.assertNotIn("typealias", source)
        self.assertNotIn("inlinefun", source)
        for file, declaration in [
            ("BgEffectPainter", "internal class BgEffectPainter"),
            ("BgEffectConfig", "internal object BgEffectConfig"),
            ("DeviceType", "internal enum class DeviceType"),
            ("FrameTimeSeconds", "internal fun rememberFrameTimeSeconds"),
            ("OS2BgFrag", "internal const val OS2_BG_FRAG"),
        ]:
            self.assertIn(declaration, (EFFECTS / f"{file}.kt").read_text())

    def test_scroll_feedback_remains_at_the_scroll_container(self):
        mary = compact((APP / "ui/screen/MaryScreen.kt").read_text())
        scene = compact((APP / "ui/components/config/SceneRouteManagerScreen.kt").read_text())
        self.assertIn("modifier=Modifier.fillMaxSize().then(gestureModifier).pageScrollFeedback()", mary)
        for source in [mary, scene]:
            self.assertEqual(1, source.count(".pageScrollFeedback()"))
            self.assertIn("overscrollEffect=null", source)
            self.assertNotIn("scrollEndHaptic", source)
            self.assertNotIn("overScrollVertical", source)


if __name__ == "__main__":
    unittest.main()
