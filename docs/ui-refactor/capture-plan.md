# Evidence capture status and procedure

No screenshots or animation recordings have yet been produced. Source inventory is not a route coverage claim.

## Execution prerequisites

The ordinary MainActivity checks AppVersionGate before rendering, then onboarding, then `remotePrefsManager.isRemoteReady()`. If remote settings are unavailable it renders an empty Box (MainActivity.kt, startup block). A clean emulator is not sufficient to prove all app routes. Use either a provisioned compatible test device or a clearly labeled fixture host rendering the real screen composables with deterministic repositories. Fixture-host images must never be labeled full application integration tests.

Capture the baseline commit and candidate on identical device density, resolution, font scale, locale and light/dark mode. A restored test-data snapshot is needed for reproducible before/after comparisons. Do not use private user conversations or credentials as fixtures. Capture MIUIX and Mochi separately after backend selection is wired through all startup branches. Currently backend selection is only an AppTheme parameter, not a complete selectable production setting.

## Recorder

`tools/ui-capture/capture.py` uses an explicitly selected ADB device and scenario JSON. It performs only listed launch/tap/swipe/back/wait operations and emits PNGs, an optional MP4, and SHA-256 evidence metadata. Device route identity must be reviewed from images; the recorder cannot infer that a desired screen was reached. One scenario records at most one animation.

Example scenario (initial screen only; not all-page evidence):

```json
{"component":"hk.uwu.reareye/.ui.MainActivity","steps":[{"action":"launch"},{"action":"wait","seconds":3},{"action":"screenshot","name":"startup.png"}]}
```

Run: `python tools/ui-capture/capture.py --serial SERIAL --scenario scenario.json --output evidence/baseline/startup`.

A gesture sequence can start `{"action":"record","seconds":10}`, then taps or swipes, then a final screenshot. Each independently reviewed route needs its own scenario and evidence directory. Coordinates must be taken from the target screen, not guessed across devices.

## Coverage inventory to complete

- Version gate, onboarding and feature guide, including return/finish states.
- Home, configuration dashboard, store, Mary and about.
- Configuration manager/editor routes and create/delete/confirm dialogs.
- Mary sidebar, model settings, approvals, tool results and conversation states.
- Store loading/empty/error/detail/install states.
- Both renderer variants, light/dark themes, shared press feedback and navigation transitions.

This list is a capture plan, not a checked-off test report. Expand it using the route call sites and `current-inventory.json`; named functions alone do not establish all UI states.
