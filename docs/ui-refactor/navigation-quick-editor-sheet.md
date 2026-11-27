# Quick-action editor shared glass sheet

Navigation's quick-action editor now uses compact `GlassBottomSheet` and mixui controls. Draft selection, maximum count, reorder calculation and Save ownership remain in the navigation owner. The shared host consumes navigation/IME insets once; body scrolling controls header material, and content-to-sheet nested dragging is disabled so reorder does not move the sheet.

A real cancellation regression exposed an existing `changedToUpIgnoreConsumed` mistake: Compose's consumed synthetic cancel release committed a reordered draft. The handler now recognizes only unconsumed release as completion and reliably calls cancellation cleanup from `finally`. This retains the pointer-driven reorder, rather than substituting click simulation.

## Local evidence

Six Robolectric API36 native-graphics tests passed without skips:

- MIUIX and Mochi, light and dark: canceled drag retains order; valid drag does not displace sheet; Cancel and Back discard the draft; Save commits once and reopening loads committed order.
- Both backends at 400dp viewport height: 48dp synthetic navigation inset moves the body viewport by exactly 48dp, and the Save control remains scroll-reachable within it.

The first run failed five tests. Cancellation business assertions were retained. The inset test was corrected to measure viewport geometry rather than a button that `performScrollTo` merely makes visible, without promising alignment to its trailing padding. No tolerance expansion was used.

Screenshots render the actual editor with three diagnostic action entries over a colored background. Short-viewport screenshots are scrolled to Save, so the top helper text is outside the viewport. This is not device, predictive-back or real navigation-destination testing. Header buttons and form buttons remain native to their backend; Save retains its existing primary style.
