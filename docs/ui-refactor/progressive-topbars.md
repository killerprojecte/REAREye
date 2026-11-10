# Progressive MIUIX top bars

All current MIUIX page-header routes use the pinned MIUIX version's native progressive backdrop renderer:

- `LargeTitleColumnScaffold`: Home and Mary settings.
- `LargeTitleLazyScaffold`: configuration categories, script list and store list.
- `GlassTopBarScaffold`: configuration/dashboard, management and secondary pages.
- `CompactGlassTopBarScene`: Mary conversation and the MIUIX Lua editor.

The basic top-bar branches remain shader-unavailable fallbacks. The Lua editor's plain `CompactTopBar` is now used only by the unchanged Mochi branch.

## Fixed headers

Mary must retain a fixed measured height: collapsing the header would change transcript content padding. Its compact adapter therefore uses the same public `progressiveTextureBlur`, gradient, radius, blend color, background recording and mask motion as native `GlassTopAppBar(progressiveBlur = true)`, without the large-title layout. The material is driven by the active content's scroll/actual overlap, including native elastic movement of a short conversation. It is not enabled merely because a conversation is nonempty.

Only the body and progressive background are recorded for toolbar controls. Toolbar controls are not sampled into their own background. Mochi retains its native title-bar fade and edge blur.

Mary settings' back, provider presets and save actions use the shared `TopBarIconButton`; enabled state, draft/save behavior and callbacks remain unchanged.

## Native Lua editor

Sora owns its own scrolling. Read its actual `offsetY` at native pre-draw, not `ScrollEvent.endY` (which can be a predicted destination). The initial header clearance is consumed by actual scrolling, allowing the real AndroidView to enter the pinned header's backdrop. The editor viewport is stable after that clearance is consumed and returns to its initial geometry at offset zero. Derived state limits composition updates to the header visibility/clearance changes; native source redraws still follow real scrolling.

## Verification

SDK 36 native-graphics Robolectric coverage:

- `MaryScreenGlassLayoutTest`: real empty/long/short conversations, elastic overlap, return and session changes; MIUIX progressive renderer and animated visibility; Mochi native edge material.
- `MarySettingsTopBarLayoutTest`: both backends in light/dark, real scrolling, all three controls, repeated preset selection, save and return.
- `HomeNativeTitleTest`: long/short real pointer scrolling and restored toolbar material across styles/themes.
- `ConfigDashboardTopBarTest` and `ConfigNodeNativeTitleTest`: dashboard scrolling/actions and category/favorites headers/back callbacks. The category typography assertion follows the existing 32sp normal alignment adapter.
- `MaryNativeTopBarTest`: transcript geometry, controls, context popup and focus regressions.
- `ScriptEditorNativeTitleTest`: native Android motion, first-line restoration, cursor selection, save/back, progressive renderer visibility and real Sora-pixel sampling. Android event time and the native Looper advance together; Compose's test clock alone does not drive the native editor's scroller.

Use `-PmaryUiResources=true -PuiCapture=false` for the two `Mary*LayoutTest` suites; use `-PmaryUiResources=true -PuiCapture=true` for the capture suites. These tests and screenshots are not physical-device GPU, IME or performance certification.
