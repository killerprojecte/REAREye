# Final MIUIX application boundary

The application production and test Kotlin sources now use the mixui API rather
than direct MIUIX imports or qualified names. The application no longer declares
MIUIX UI, preference, blur or shader implementation dependencies. Those libraries
remain implementation details of mixui. The existing development workflow runs
`python3 tools/check_ui_boundary.py`; it checks imports and qualified references
without a test or utility allowlist.

Native Mochi comparison fixtures and the shared navigation backdrop dependencies
are not claimed to be removed. This boundary is specifically the MIUIX migration.

## Fixture preservation and gesture regression

Eleven existing application test files now enter through MixUiHost/MixTheme.
The surface content-color assertion reads the actual rendered Text layout instead
of reaching into a vendor CompositionLocal; pixel, clipping and interaction
assertions remain.

The complete affected run exposed three existing Mary sidebar failures. Running
those same cases with the original committed fixture and dependency declarations
reproduced all three. Two were native navigation claiming the same opening drag
as the Mary sidebar; one attempted to wait for idle while native press feedback
remained held.

An active Mary's navigation region now claims only opening-direction pointer
slop in Initial. The neutral navigation gate rejects that claimed stream without
consuming the ancestor's later events. Opposite-direction navigation, taps and
stationary hold menus remain native. The current page/registration and gesture
token must remain valid; leaving Mary cannot commit a late release. Held-close
sampling freezes the test clock and advances bounded frames rather than extending
timeouts or releasing the pointer before checking progress.

The existing 55 sidebar methods plus five added methods pass. Added coverage
includes both backends, exact held progress, cancellation, stationary hold,
opposite-direction selection, tap recovery, leaving Mary mid-drag, and RTL
opening/cancellation. These are local Compose/Robolectric input tests, not
physical-device evidence.

## Validation scope

- Strict boundary scan: zero direct MIUIX references outside mixui.
- Surface 4, Mary chat 43, other Mary/Markdown 79, sidebar 60: all pass with
  no skipped cases across the recorded focused runs.
- Release R8 succeeded after removing the four application MIUIX dependencies.
  That run preceded the later gesture-only changes; no new dependency or ABI
  change was made afterward. It was not an APK signing or device-install test.
- Native navigation interaction suite: 10/10 pass, including both directions, RTL,
  cancellation, mode retirement, accessibility and the actual quick editor. Its
  fixture now provides the same GlassBottomSheetScene as the application root.
- The complete style gallery is tracked separately. Historical screenshots are
  not relabelled as final captures.
