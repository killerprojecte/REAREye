# UI base refactor status

Branch: `feat/ui-base-refactor`. Baseline: `dev@e554a382dabdb79994fa11a4482c072c9f968c82`. Neither dev nor main is modified.

## Contract

`mixui` owns neutral controls, theme tokens and motion; app business logic and stored configuration stay in the app. Public adapter signatures do not expose vendor types. Each backend should use its real controls rather than present a MIUIX control as Mochi. Transitional compatibility paths must be explicit.

## Implemented

- Neutral theme modes, 53 color and 14 typography tokens, Text, Icon, Button, Switch, Card, IconButton, progress indicators, Badge groups and icons.
- Shared ArtMotion and press feedback; native Mochi controls retain their own motion rather than receiving a second press effect.
- All five page TabRow sites use native MIUIX tabs or MochiSegmentedControl. CompactTabs has its own compact native implementations.
- All thirteen labelled SwitchPreference calls route through mixui. Two simple string spinner sites use native WindowSpinnerPreference or MochiDropdown/MochiListRow.
- Neutral Scaffold slots/insets and popup lifetime host. Both backends currently share the internal layout infrastructure.
- SceneRouteManagerScreen uses real MIUIX GlassTopAppBar/GlassIconButton with a non-null page-only backdrop. Popups and the bar itself are excluded from recording. Mochi uses its native solid TitleBar/BarIconButton; root glass materials are not enabled yet.

## Incomplete

The application still defaults to MIUIX; there is no selectable production backend setting yet. Full migration is not complete. Top bars/scroll infrastructure, text fields, dropdown/popups and theme controllers still contain direct vendor calls. Whole-tree `tools/check_ui_boundary.py` intentionally reports remaining violations, with no hidden allowlist.

At published commit e57e5a6, app/main direct MIUIX references were 218 in 38 files, versus 542 in 58 baseline files (comments/string literals excluded). This 59.8% reference reduction is a dependency metric, not a claim of 59.8% functional completion.

## Dependencies and R8 correction

MIUIX uses verified `0.9.4-799d3d09-SNAPSHOT`, including the actual glass module. Actions resolves GitHub Packages; local builds resolve the configured anonymous mirror. No credential belongs in source or build logs.

Mochi 0.2.0 was compiled against MaterialKolor 4 and Backdrop 1. Its old binary references missing DynamicScheme classes under MaterialKolor 6 and a removed Android RenderEffect overload under Backdrop 2. A root palette bridge made the debug render path work but did not fix release R8. The e57e5a6 release CI therefore failed at minification.

The pinned upstream core is now recompiled inside mixui against the shared dependencies. Original core binary is excluded; native mochi-ios recipes remain. See `mixui/vendor/mochi-compose/UPSTREAM.md`, Apache license, third-party notices and original source hashes. Of 170 upstream Kotlin files, 168 are unchanged and two adapt the moved DynamicScheme type and RenderEffect overload. There are no invented class stubs or added missing-class warning suppressions. The recompiled runtime has no old DynamicScheme descriptors.

## Local verification and limits

A complete JDK 21, Android platforms 36/37 and Gradle 9.6 are used with the existing Robolectric 4.17 native graphics renderer, not an emulator. Local UI-only build mode is `-PisPublicBeta=true`; native hook integration and a signed release APK are not claimed by that mode.

Run `tools/run-local-gradle.sh -PisPublicBeta=true -PuiCapture=true :mixui:testDebugUnitTest :app:testDebugUnitTest :app:minifyReleaseWithR8`.

The source-recompiled core passed local compilation, mixui unit tests, 64 captured fixture views (16 states, two themes, two backends), and real release R8 minification. A dedicated palette test additionally exercises every native palette style in light/dark. Full production CI must independently pass on the published commit.

The previous 15-state comparison contains 30 baseline and 60 candidate images. Initial previews incorrectly missed three overlay states; those previews are superseded by root-popup/full-root captures with visible-title and nonblank-pixel checks. Current captured cards assert both real tab groups and sample tab selection at 16 ms intervals. Scene-route fixtures now include 16 in-memory routes and a scrolled state.

These are real production composables with controlled fixture data, not every application state or physical-device evidence. Home live state, configured Mary chat, Store/network/install flows and all nested modal/permission/error states remain to be covered. Native graphics screenshots do not by themselves prove physical GPU shader fidelity, accessibility, IME or interrupted gestures.
