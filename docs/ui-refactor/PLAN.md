# UI base refactor work log

Branch: `feat/ui-base-refactor`. Baseline: `dev@e554a382dabdb79994fa11a4482c072c9f968c82`. Neither dev nor main is modified.

## Contract

- `mixui` owns backend-neutral controls, tokens and shared motion. Vendor types must not escape its public API.
- MIUIX and Mochi render the same application state/callbacks/layout contract. No duplicated screen business logic.
- Shared Mochi press feedback is usable on MIUIX/custom components. Do not layer it again over a Mochi component with its own press animation.
- Migrate controls/theme, then preferences/popups, then navigation/glass. Remove app vendor dependencies only after the final call site moves.
- Preserve all existing preference values, callbacks, accessibility semantics, disabled states, cancellation and back/IME behavior.

## Current implementation, incomplete

- Added the Android `mixui` submodule and MIUIX/Mochi button/switch adapters.
- Existing application switches now use the neutral API.
- Moved all existing `ArtMotion` helpers, unchanged apart from package, into `mixui`; their existing application usages now import that module.
- Exposed shared Mochi press deformation and applied it to the MIUIX button adapter.
- Existing AppTheme hosts the adapter context; default remains MIUIX. The rest of the application is not yet migrated. There is not yet a production backend selector.

## Dependency evidence

Mochi: Maven Central metadata verified 0.2.0; inspected tag `v0.2.0@3f4eda8603443af2774cf450b47d13f1f311eea7`, Apache-2.0. The consumed APIs match that tag, not unreleased main.

MIUIX: current app version 0.9.4. Inspected upstream main `b7a5f08f5a8c20350406e1665a97fbf99b5d9c8c`. Source publishes commit-suffixed snapshots to `https://maven.pkg.github.com/compose-miuix-ui/miuix`; unauthenticated metadata returned HTTP 401. The source-derived candidate `0.9.4-b7a5f08-SNAPSHOT` is **not verified as published/latest** and is not silently substituted. Latest source includes `miuix-glass` with GlassTopAppBar, GlassNavigationBar, GlassDialog, GlassBottomSheet and glassSurface adapters. Snapshot resolution and actual glass migration remain pending.

## Build and visual evidence

Baseline wrapper build was attempted, not passed: Gradle Java download reported network unreachable. Curl download worked, but local extraction ran out of /tmp space. Only that rebuildable partial tool download was removed. A separate Android task subsequently identified an SDK37 and adb installation that can be reused read-only; no emulator or connected device has been verified. A task-local Gradle cache will be used, without modifying that SDK. No screenshots or animation captures have been produced.

The source inventory is reproducible with `python tools/ui_inventory.py`; it is not a screen-coverage claim. Before acceptance, record every reachable route/modal state from baseline and both backends, with matching locale/theme/font scale/data. Required flows include onboarding, home/config/store/Mary/about, nested managers/editors, permission/error/empty/loading states and popups; dynamic root/Xposed-dependent data requires a controlled fixture or the real device, explicitly labeled.

For animation evidence record press/release/cancel while scrolling, switch drag, dialog/sheet interruption, route enter/back, light/dark changes and glass scrolling on both backends. Record device/API, refresh rate, animation scale and capture timing. Static screenshots do not prove animation or runtime-shader glass behavior. A library's demo GIF is not REAREye evidence.
