# Decorative effects boundary

The About page's gradient, textured cards and masked logo/title now enter through
`mixui.effects`. `TextureBackdrop` is an opaque handle, and app-owned branding
recipes use `TextureBlendLayer` and `TextureBlendOperation`. Vendor classes stay
inside mixui. Only the two live color presets remain in the app.

This is a shared decorative renderer for both MIUIX and Mochi. It preserves the
existing About artwork and is not a claim that the renderer is native Mochi glass.
The native component adapters and theme choices are unchanged.

The capture modifier belongs to the background Canvas only. The solid surface
is drawn first; the animated shader is drawn with `1f - scrollProgress` alpha.
Cards, the logo/title and foreground content are not recorded into that source.
The PHONE presets, 78% gradient bound, uniforms, frame timing and shader source
are unchanged. Texture radii are explicitly dp: cards use 60.dp with 0.001 noise,
and logo/title use 150.dp with the renderer's default noise. Cards retain SrcOver;
logo/title retain DstIn. Layer colors, ordering and corner shapes are unchanged.

Mary and Scene Route use the existing `pageScrollFeedback` adapter at their old
modifier position and continue disabling the Compose overscroll effect. The
unused `BlurredBar`, `rememberBlurBackdrop` and `BlurToken` were removed.
`FrameSampler` and app Gradle dependencies were not changed.

Validation performed locally:

- Six source-contract checks pass: `python3 tools/check_decorative_effects.py`.
- Three `TextureBackdropTest` conversion tests pass; app and test Kotlin compile.
- Three production-page methods in `RealPageCaptureTest` pass: About native routes,
  Scene Route buttons/navigation, and Scene Route safe insets/glass states. They
  cover both backends, light/dark and LTR/RTL combinations using Robolectric API36
  native graphics. About scrolling, contributor/license routes, Back and preset
  callback remain working. Actual rendered screenshots were inspected.
- Zero failures or skips in this focused run. This does not establish physical GPU
  fidelity, all densities, or a byte-identical animated frame against a baseline.

Production app source now has no direct MIUIX references. The whole-tree boundary
still reports 32 references in 11 test files, whose fixture migration is separate.
The four app production MIUIX dependencies remain until that cleanup is verified.
