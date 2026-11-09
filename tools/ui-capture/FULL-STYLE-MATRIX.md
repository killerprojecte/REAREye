# Local full-style capture matrix

Production base: `563aa8fabcb017b9899a5aaae5fec7d9be0eefac` (2.0.1 DaVanci).
The capture changes are test/debug-only. They do not change release startup or workflows.

## Executed evidence

- `RealPageCaptureTest.renderFullStylePageMatrix`: 18 routes × five palettes × four modes = **360 PNGs**. Explicit light/dark and system-day/system-night were rendered separately. System modes change the actual Robolectric night configuration before creating the Activity.
- `NavigationQuickEditorSheetTest.renderFullStyleQuickEditorMatrix`: 10 PNGs, real editor/save/cancel/Back exercise over a solid-blue fixture background.
- `ScriptRenameHeaderTest.renderFullStyleScriptRenameMatrix`: 60 PNGs, project/file/import headers and empty-input validation, labeled production-component fixture.
- `ScriptDebugGlassSheetTest.renderFullStyleScriptDebugMatrix`: 30 PNGs, empty/scrolled synthetic output/synthetic IME. No scripts were executed.
- `StoreDetailNativeHeaderTest.renderFullStyleDetailMatrix`: 30 PNGs, top/releases/info with local nonempty detail and README loaders.
- `MarySidebarLayoutTest.renderFullStyleMarySidebarMatrix`: 20 PNGs, open/history-scrolled, 30 local conversation entries and production conversation/sidebar components.

The five palettes are MIUIX default/Monet and Mochi default/iOS/Nothing. The latter five component matrices cover explicit light/dark. All listed matrix methods completed successfully without skipped cases. The page matrix validates actual visible route content and image pixels, not just Activity startup. Per-directory manifests record requested and rendered modes, qualifiers, fixtures, and image names.

Page routes: cards, card-editor, components, component-editor, wallpapers, more, scene-routes, scene-routes-populated, business-extras, business-extra-editor, custom-bounds, home-offline, store-catalog, onboarding, about, scripts, mary-settings-unconfigured, version-dialog.

Theme-sheet draft previews add 11 PNGs from `ThemeSheetPreviewTest`; both existing transaction/backdrop tests passed on this same tree. Saving applies persisted choices, closing discards draft changes, and backend switching retains the draft. The combined gallery contains 521 PNGs.

## Reproduction

Use the repository local Gradle wrapper/environment with `:app:testDebugUnitTest`, `-PuiCaptureVariants=miuix-default,miuix-monet,mochi-default,mochi-ios,mochi-nothing`, and `-PuiCaptureModes=light,dark` (or `system-day,system-night` for the page matrix). Select the exact method above with `--tests`.

Use `-PuiCapture=true` for capture-package tests. For `MarySidebarLayoutTest` use `-PmaryUiResources=true` **without** `-PuiCapture=true`: the capture package filter intentionally excludes the Mary test package. Empty variant selection skips these opt-in matrix methods, preserving ordinary test runtime. Invalid explicit selections fail.

Outputs are under `build/reports/ui-capture/full-style-{pages,quick-editor,script-rename,script-debug,details,mary-sidebar}`. Page manifests live at variant/mode/page; other manifests at variant/mode. Read a manifest before interpreting an image.

## Limits

These are local Robolectric native-graphics simulations, not device screenshots, GPU/physical-system-bar certification, or end-to-end MainActivity/network integration. Fixed repositories and component hosts are documented rather than hidden. Monet uses simulated system colors, not the user's wallpaper. Nothing uses the existing compliant font substitutions and does not certify the original dot font.

Store README HTML handoff is asserted against the production WebView. Robolectric does not lay out the Chromium body; README text pixels and scrolling are **not** verified. No fake height or fake README renderer is substituted. Store release/info screenshots contain actual Compose production content.

Home uses deterministic offline status, onboarding shows its first page, Mary settings are unconfigured, and some manager lists are empty. Mary sidebar is a production-component fixture, not the complete live conversation flow. Main-page captures use English; detail/sidebar and some sheets use Chinese. This batch is not an exhaustive matrix of every network/error/loading/dialog state. Static PNGs do not prove animation timing; existing gesture tests are separate evidence. A fresh pre-refactor baseline or physical-device recording was not produced by this batch.
