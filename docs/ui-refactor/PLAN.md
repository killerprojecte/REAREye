# UI base refactor status

Branch: `feat/ui-base-refactor`. Baseline: `dev@e554a382dabdb79994fa11a4482c072c9f968c82`. Neither dev nor main is modified.

## Contract

`mixui` owns neutral controls, theme tokens and motion; app business logic and stored configuration stay in the app. Public adapter signatures do not expose vendor types. Each backend should use its real controls rather than present a MIUIX control as Mochi. Transitional compatibility paths must be explicit.

## Implemented

- Neutral theme modes, 53 color and 14 typography tokens, Text, Icon, Button, Switch, Card, IconButton, progress indicators, Badge groups and icons.
- Shared ArtMotion and press feedback; native Mochi controls retain their own motion rather than receiving a second press effect.
- All five page TabRow sites use native MIUIX tabs or MochiSegmentedControl. CompactTabs has its own compact native implementations.
- All thirteen labelled SwitchPreference calls route through mixui. Two simple string spinner sites use native WindowSpinnerPreference or MochiDropdown/MochiListRow.
- All 43 text fields enter mixui. Compatible inputs use native backend controls; unsupported Mochi read-only/minimum-line/custom-transformation requests currently retain an explicit internal MIUIX compatibility renderer rather than drop behavior.
- Neutral Scaffold slots/insets and popup lifetime host. Both backends currently share the internal layout infrastructure.
- SceneRouteManagerScreen uses real MIUIX GlassTopAppBar/GlassIconButton with a non-null page-only backdrop. Popups and the bar itself are excluded from recording. Mochi uses native TitleBar/BarIconButton with Maven `iosMaterials` glass recipes. Both backends have local pressed/released glass frames.

## Incomplete

The application defaults to MIUIX. The single Theme style setting now opens a native-header BottomSheet with Style, Palette and Brightness tabs. MIUIX exposes Default/Monet, while Mochi exposes Default/iOS/Nothing; both support System/Light/Dark. Only the sheet previews the draft. Save atomically commits mode and Mochi appearance; Close/Back cancels. The former separate backend and appearance dropdowns are removed. Legacy backend values migrate once, preserving the prior appearance. Successful writes refresh the active Activity and closed setting rows, including writes finishing during Activity recreation. Full component migration is not complete: top bars/scroll infrastructure and dropdown/popups still contain direct vendor calls. Whole-tree `tools/check_ui_boundary.py` reports remaining violations without a hidden allowlist.

The current app/main scan reports 198 direct MIUIX references in 37 files, versus 542 in 58 baseline files (comments/string literals excluded). This 63.5% reference reduction is a dependency metric, not functional completion. Whole app source plus tests still has 232 references.

## Dependencies and R8 correction

MIUIX uses verified `0.9.4-799d3d09-SNAPSHOT`, including the actual glass module. Actions resolves GitHub Packages; local builds resolve the configured anonymous mirror. No credential belongs in source or build logs.

Mochi 0.2.0 was compiled against MaterialKolor 4 and Backdrop 1. Its old binary references missing DynamicScheme classes under MaterialKolor 6 and a removed Android RenderEffect overload under Backdrop 2. A root palette bridge made the debug render path work but did not fix release R8. The e57e5a6 release CI therefore failed at minification.

Mochi must remain a Maven dependency. The temporary source-copy solution was rejected and all copied Mochi source has been removed. The build now consumes the published Maven `mochi-ios` and core 0.2.0 AARs.

The compatibility build logic isolates the original Maven MaterialKolor 4.1.1 artifact in a private namespace and updates Mochi's binary references to it. MIUIX retains MaterialKolor 6.0.0. A narrow adapter converts the one obsolete Android RenderEffect overload to the supported Compose wrapper while keeping Backdrop 2.0.1. Mochi AAR resources, manifest and consumer rules are preserved. See `buildSrc` for the explicit transform; no missing-class warnings are suppressed and no substitute library classes are invented.

The isolated MaterialKolor artifact resolves separately from the normal dependency graph so conflict resolution cannot silently upgrade it before transformation. The compatibility attribute is applied at artifact level in every consumer, including app runtime. Kotlin type-name metadata and archive/class descriptors are handled alongside bytecode. The first local app compile and release R8 passed with this route. Independent review found callable-reference signature and array-descriptor strings that also need relocation. These have been corrected, with a build-logic JAR regression test; the final artifact retains only five original source-debug SMAP paths, not runtime type strings. Independent final-artifact inspection confirms those two issues are resolved (private JAR SHA-256 `43f4f372e1636fe7121e98f3ab2a018a56b78e00df891ef0adfb2979cc216278`). Final-patch release R8 passes. The 64-view test suite initially hit the default test JVM heap limit after 54 views; the separate UI-only test heap is now 2 GiB and the complete 64-view rerun, editor interaction test and two backend-selection tests all pass. The original MaterialKolor 4.1.1 MIT license and a relocation notice are packaged explicitly because its Maven AAR does not contain license resources.

## Local verification and limits

A complete JDK 21, Android platforms 36/37 and Gradle 9.6 are used with the existing Robolectric 4.17 native graphics renderer, not an emulator. Local UI-only build mode is `-PisPublicBeta=true`; native hook integration and a signed release APK are not claimed by that mode.

Run `tools/run-local-gradle.sh -PisPublicBeta=true -PuiCapture=true :mixui:testDebugUnitTest :app:testDebugUnitTest :app:minifyReleaseWithR8`.

The Maven compatibility route has passed mixui compilation and 11 unit tests, including all six native palette styles in light/dark, release R8, and 64 actual page fixture renders. Both glass backends produced 24 pressed/released frames each, with distinct frames. Both backends also pass actual card-title and component-path editing tests. Two initial test failures were fixture issues: a manual clock needed frames after input, and the unlocked fixture path was incorrectly assumed disabled. These were corrected without changing production input behavior. Full production CI must independently pass on the published commit.

The previous 15-state comparison contains 30 baseline and 60 candidate images. Initial previews incorrectly missed three overlay states; those previews are superseded by root-popup/full-root captures with visible-title and nonblank-pixel checks. Current captured cards assert both real tab groups and sample tab selection at 16 ms intervals. Scene-route fixtures now include 16 in-memory routes and a scrolled state.

These are real production composables with controlled fixture data, not every application state or physical-device evidence. Home live state, configured Mary chat, Store/network/install flows and all nested modal/permission/error states remain to be covered. Native graphics screenshots do not by themselves prove physical GPU shader fidelity, accessibility, IME or interrupted gestures.

## Unified theme choices, Mochi appearances and scene-route controls (working candidate)

The persisted `module_mochi_theme_style` preference selects Default Mochi, iOS or Nothing (stable values 0/1/2). The actual selector commits the theme before updating the activity. Failed saves leave the active choice intact and show a message. Legacy Mochi+Monet values retain their original colors until an explicit new theme selection; these compatibility values are not additional menu options. All three appearances use Maven 0.2.0 public theme tokens at one shared composition host, preserving page state. MIUIX ignores this Mochi-only preference. iOS and Nothing use their native palettes rather than the default Monet seed.

The Maven Nothing AAR explicitly states that redistribution rights for its bundled NType82 and Ndot57 fonts are not established. The resource-only compatibility transform omits those two font binaries, preserves the OFL Inter font and all upstream notices, and keeps their resource IDs as aliases to Inter. Nothing uses Inter/system fonts; explicit dot/display font requests use Inter, not the original dot appearance. This is documented in the packaged compatibility notice. No Mochi Kotlin source is vendored.

Scene-route controls now have localized accessibility labels, use the auto-mirrored back icon without a second RTL reflection, and disable Add while loading. Enabled MIUIX controls use the actual 799 GlassIconButton; because that native component has no enabled API, the disabled state uses a non-glass disabled IconButton with the same footprint. Initial transparent, pressed and scrolled glass states remain native behavior.

Six build-logic tests and fifteen mixui tests passed for the initial appearance patch, together with release R8. All twelve appearance/card captures, actual font loading and same-backend style switching passed. Scene-route Add/Edit presentation now uses native MIUIX GlassDialog / MochiDialog with a separate page-plus-bar backdrop that excludes the form and scrim. Its first eight-case backend/light-dark/RTL interaction suite passed and produced 24 images. The unified theme implementation has since shipped in 732d25d with the targeted results below; a new complete all-page capture rerun is not claimed. Debug and release runtime resolution independently confirms all MIUIX modules are exactly `0.9.4-799d3d09-SNAPSHOT`.


## Published theme checkpoint: 732d25d

The approved sheet and settings integration were published in commit `732d25d8d5a803bea957d7d2f396cbad2c064e28`. All 51 focused tests passed (6 build logic, 15 mixui, 30 app), including save/cancel, failure/retry, Back during delayed save, legacy migration and successful commit across Activity recreation followed by reopening and saving again. Eleven native-graphics sheet captures cover the palettes and light/dark, with no separate preview window or preview explanatory footer. Local release R8 and unsigned debug assembly also passed; routine UI edits use focused validation rather than repeating release on every change.

The next component checkpoint adds Mary native title adapters, Home/Store fixture coverage and Scene Route GlassDialog. The eight backend/light-dark/RTL dialog cases pass validation failure, cancellation, Back, repeated opening and successful route creation. Mary title/scroll tests and eight Home/Store fixture renders passed separately. Native navigation glass remains a private candidate and is not yet interaction-validated. None of these is claimed delivered by the theme checkpoint. Native-graphics screenshots are not physical-device GPU validation.
