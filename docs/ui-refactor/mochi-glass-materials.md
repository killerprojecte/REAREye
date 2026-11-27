# Mochi glass materials and page sources

Mochi Maven 0.2.0 supplies public iosMaterials independently of the palette and typography. MixUiHost already supplies these materials for Default, iOS and Nothing, in both light and dark modes. Selecting another palette does not require selecting the iOS theme or copying vendor source.

Existing native material consumers include toolbar icon buttons, joined header tabs, anchored menus, preference popovers and form dialogs. Dialog input fields and explicit form buttons remain ordinary controls, as requested. Navigation preserves its separately selected style.

## Large sheets

The published MochiSheet exposes color. At Large it draws that color over its native glass surface with full opacity. Passing Transparent removes only this covering layer. It does not replace the native sheet implementation or its detents, IME handling, drag gesture, exit animation or draft ownership.

The overlay also needs a real source. GlassBottomSheetScene now records the page with the public MochiBackdrop API, including while the applied theme is MIUIX and the draft previews Mochi. Native MochiSheet creates a separate DecorView overlay outside this recording. The sheet receives this source explicitly; it does not record itself. Its three joined theme selector rows receive a dedicated glass source too, instead of silently falling back to ordinary tabs. The native overlay clears LocalMochiBackdrop deliberately; mixui does not override that policy for every child. Only GlassTabs consumes the explicitly supplied external page source, which excludes the sheet and its controls. Without a source or a sheet material, the ordinary native sheet color is retained.

Header actions currently use the native MochiIconButton Glass style. That control uses its own canvas; this is not claimed to be page-through sampling. The existing native allowDismiss recovery limitation remains: a disallowed hide is reversed, not blocked before its animation begins.

## Validation

The new local test uses the real ThemeStyleSheet and native Maven Large sheet at steady state across all six palette/mode combinations. It changes a high-contrast page while the sheet stays open and checks both transmission and a softened color seam, then exercises draft cancellation and saving. A colorful backing canvas is a sampling test fixture, not a proposed production page design. Final local validation passed all three methods, including the real theme host and three native-glass tab nodes in each palette: six steady-state palette/mode captures with changing-page and softened-seam assertions, plus draft save/cancel. Software renderer evidence is not a physical-device GPU claim.
