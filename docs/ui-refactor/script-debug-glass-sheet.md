# Script-debug compact glass sheet

The production script-debug panel uses the common compact `GlassBottomSheet` host in both backends. Provider/parameter text and Run validation remain owned by the existing script page. Empty/invalid input is forwarded unchanged; the presentation does not introduce a second validator or run a script by itself.

The sheet owns navigation and IME insets once. Form and result scrolling are independent; either scroll reveals MIUIX's header action material, and reopening resets their scroll positions. No content-to-sheet nested dragging is enabled for these controls. Run is disabled during exit.

## Cancellation compatibility

Exact Maven Mochi 0.2.0 treats an in-bounds synthetic pointer cancel release as a click. Compose 1.12.1 dispatches cancellation child-first as consumed pointer releases; a MotionEvent spy alone cannot observe it. `BottomSheetActionButton` now permits the native pointer callback only during an actual unconsumed release dispatch. It does not consume input or replace Maven styles, animation or gesture code. An independent enabled semantics action retains non-pointer activation, including after cancellation. No sources are vendored.

## Validation

Robolectric API36 native graphics: six tests passed, zero skipped.

- Four actual debug-sheet combinations: MIUIX/Mochi, light/dark. Edited and invalid inputs reach the inert Run callback, output scroll retains the sheet and changes MIUIX action material, navigation/IME synthetic insets are applied once, the final field remains reachable, canceled Run does not execute, Back/scrim close without running, and reopening retains inputs.
- Two native-action light/dark tests: normal touch, cancel, outside release, semantics following cancellation and while held, disable during hold, re-enable, three fresh sessions.

The first run deliberately failed on Mochi cancellation; the assertion was preserved and passes with the adapter. Screenshots use the real panel and controls over a diagnostic colored page; output and system insets are synthetic. This does not claim actual script execution, a physical keyboard, or device rendering.
