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

The application still defaults to MIUIX; there is no selectable production backend setting yet. Full migration is not complete. Top bars/scroll infrastructure, dropdown/popups and theme controllers still contain direct vendor calls. Whole-tree `tools/check_ui_boundary.py` intentionally reports remaining violations, with no hidden allowlist.

The current app/main scan reports 201 direct MIUIX references in 37 files, versus 542 in 58 baseline files (comments/string literals excluded). This 62.9% reference reduction is a dependency metric, not functional completion. Whole app source plus tests still has 232 references.

## Dependencies and R8 correction

MIUIX uses verified `0.9.4-799d3d09-SNAPSHOT`, including the actual glass module. Actions resolves GitHub Packages; local builds resolve the configured anonymous mirror. No credential belongs in source or build logs.

Mochi 0.2.0 was compiled against MaterialKolor 4 and Backdrop 1. Its old binary references missing DynamicScheme classes under MaterialKolor 6 and a removed Android RenderEffect overload under Backdrop 2. A root palette bridge made the debug render path work but did not fix release R8. The e57e5a6 release CI therefore failed at minification.

Mochi must remain a Maven dependency. The temporary source-copy solution was rejected and all copied Mochi source has been removed. The build now consumes the published Maven `mochi-ios` and core 0.2.0 AARs.

The compatibility build logic isolates the original Maven MaterialKolor 4.1.1 artifact in a private namespace and updates Mochi's binary references to it. MIUIX retains MaterialKolor 6.0.0. A narrow adapter converts the one obsolete Android RenderEffect overload to the supported Compose wrapper while keeping Backdrop 2.0.1. Mochi AAR resources, manifest and consumer rules are preserved. See `buildSrc` for the explicit transform; no missing-class warnings are suppressed and no substitute library classes are invented.

The isolated MaterialKolor artifact resolves separately from the normal dependency graph so conflict resolution cannot silently upgrade it before transformation. The compatibility attribute is applied at artifact level in every consumer, including app runtime. Kotlin type-name metadata and archive/class descriptors are handled alongside bytecode. The local app compile and release R8 now pass with this route; independent compatibility review remains pending.

## Local verification and limits

A complete JDK 21, Android platforms 36/37 and Gradle 9.6 are used with the existing Robolectric 4.17 native graphics renderer, not an emulator. Local UI-only build mode is `-PisPublicBeta=true`; native hook integration and a signed release APK are not claimed by that mode.

Run `tools/run-local-gradle.sh -PisPublicBeta=true -PuiCapture=true :mixui:testDebugUnitTest :app:testDebugUnitTest :app:minifyReleaseWithR8`.

The Maven compatibility route has passed mixui compilation and 11 unit tests, including all six native palette styles in light/dark, release R8, and 64 actual page fixture renders. Both glass backends produced 24 pressed/released frames each, with distinct frames. Both backends also pass actual card-title and component-path editing tests. Two initial test failures were fixture issues: a manual clock needed frames after input, and the unlocked fixture path was incorrectly assumed disabled. These were corrected without changing production input behavior. Full production CI must independently pass on the published commit.

The previous 15-state comparison contains 30 baseline and 60 candidate images. Initial previews incorrectly missed three overlay states; those previews are superseded by root-popup/full-root captures with visible-title and nonblank-pixel checks. Current captured cards assert both real tab groups and sample tab selection at 16 ms intervals. Scene-route fixtures now include 16 in-memory routes and a scrolled state.

These are real production composables with controlled fixture data, not every application state or physical-device evidence. Home live state, configured Mary chat, Store/network/install flows and all nested modal/permission/error states remain to be covered. Native graphics screenshots do not by themselves prove physical GPU shader fidelity, accessibility, IME or interrupted gestures.
