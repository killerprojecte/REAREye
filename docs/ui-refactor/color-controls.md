# Color controls through mixui

CustomBoundsColorEditor and the widget template ColorValueEditor now call mixui
ColorPalette/ColorPicker. MIUIX delegates to its native controls. Maven Mochi 0.2.0
publicly exposes a ColorWell and modal ColorPickerSheet, but no inline palette or
picker. To preserve the existing editor/dialog layout, mixui composes its own
91-color grid and public native MochiSlider HSV/alpha controls. This is an inline
adapter, not a claim that Mochi exposes a native inline ColorPicker. No private
vendor source is copied and no second sheet is opened.

Callbacks remain immediate. The existing Advanced confirm action closes rather
than applying again; Back does not roll back already committed values. Alpha is
preserved across palette selection, and hue survives zero saturation/brightness.
External values synchronize without emitting extra callbacks. Dialog hosts and
ordinary input/button materials are unchanged.

Validation: six logic tests passed, including 4096 ARGB round trips, alpha and hue
recovery, 91 distinct swatches and hex formatting. App compilation passed. Five
Robolectric native-graphics interaction methods passed, including both production
editor bodies with both backends/light-dark, native slider pointer input, semantic
progress input, external updates, black-to-color recovery, and Advanced confirm /
Back / reopen. MIUIX's ea1b935 ColorSlider is draggable rather than tappable, so its
actual native track is tested with a swipe.

These tests capture actual editor bodies, not whole-page navigation or persisted
settings integration. Standalone dialog-window captures do not include the parent
window; black outside a captured dialog is not evidence of the app's backdrop.
Physical-device keyboard, TalkBack and complete persistence flows remain outside
this focused verification.
