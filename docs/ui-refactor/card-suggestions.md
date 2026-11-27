# Card business suggestions

`RearCardCreateDialog` now uses reusable `mixui.SuggestionMenu` instead of directly
calling MIUIX dropdown implementation/overlay helpers. The ordinary opaque menu
follows the selected backend's color tokens; it is intentionally not a glass input
or a glass button. The outer form now uses `GlassFormDialog`; CardManager and
BusinessManager supply their native overlay slots, with embedded screens using
`GlassPreferenceScene`. The form stays composed during native exit animations.

The public Compose Popup is non-focusable, so editing continues while filtering.
The dialog handles Back by closing visible suggestions first. Business identity,
case-insensitive filtering, distinct/sorted options, dynamic catalog changes and
default-priority selection remain caller-owned. Popup geometry is constrained by
the measured, clipped form viewport and window/IME bounds, with RTL positioning
and offscreen-anchor checks. Ordinary Cancel/Confirm controls share one row.

Earlier opaque-host verification: four Card dialog tests (MIUIX/Mochi, light/dark) passed with
filtering, Back, continued typing, dynamic catalog update, selection/priority,
confirm and cancel. Five pure geometry tests cover bounds/RTL/offscreen cases.
These are local Robolectric tests. The migrated suite captures the native activity overlay and the suggestion popup
separately, not a fabricated full-screen composite. Device keyboard,
TalkBack and platform popup positioning still need device verification.


## Native form migration verification

`RearCardGlassDialogTest` covers populated production CardManager pages (standalone
and embedded editor), invalid form preservation, canceled pointer presses, one save
for repeated actions, create/autocomplete focus and Back ordering, retry after failed
app registration, and BusinessManager's shared card shortcut. The updated
`RearCardSuggestionsTest` retains dynamic catalog/filtering/priority checks and
asserts popup bounds against the form viewport. `ManagerNativeTopBarTest` now
recognizes CardManager's native overlay instead of requiring an Android Dialog.

A separate, explicitly controlled fixture renders the production create form over
populated `ModuleStyleManagerCard` content. Its differential captures change field
text, then the underlying cards, and compare a clear glass gap. It is not presented
as real-page evidence. Capture and shader assertions are pending the parent test run;
no screenshots or successful runs are claimed by this patch.

Run with the existing local Gradle/toolchain configuration:

```
./gradlew :app:testDebugUnitTest -PuiCapture=true \
  --tests 'hk.uwu.reareye.ui.capture.RearCardGlassDialogTest' \
  --tests 'hk.uwu.reareye.ui.capture.RearCardSuggestionsTest' \
  --tests 'hk.uwu.reareye.ui.capture.ManagerNativeTopBarTest'
```

Normal-card persistence remains the existing asynchronous repository path. This
migration does not redesign its transaction/error handling; app-registration failures
and ordinary form-validation failures remain editable. Real IME resize/focus, device
Back behavior and physical native-shader rendering still require device verification.
