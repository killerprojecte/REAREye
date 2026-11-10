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

## Short-page collapse displacement

`rememberMiuixPageScrollBehavior` retains MIUIX's native nested-scroll behavior
and adds the measured header displacement to its material `contentOffset`.
The vendor consumes collapse in pre-scroll but updates `contentOffset` only in
post-scroll. On short pages the body consumes nothing, leaving the progressive
band transparent even though reducing the header moves the page upward.
The adapter observes actual height changes, including native settling, and
clamps expansion at zero. It does not add an independent animation or timer.

Regression coverage in `ScriptListNativeTitleTest` includes:

- A non-scrolling short page: baseline band alpha was zero after collapse;
  fixed alpha matches measured page displacement / the native 48dp ramp.
  Two repeated expand/collapse cycles must restore the same alpha without drift.
- Red/blue lazy rows scrolled under the header and back, both backends/light/dark.
- One unchanged long Canvas, testing placement-only scrolling without content
  recomposition, both backends/light/dark.
- Actual ScriptList cards with contrasting text shapes moved by pointer input,
  both backends/light/dark, verifying replacement of sampled pixels.

These are SDK 36 native-graphics Robolectric checks. They do not reproduce or
certify the reported persistent first-card sampling on the user's physical GPU.
The short-page visibility fix should not be represented as proof that that
separate device-specific report has been resolved.

## Explicit scroll-driven draw invalidation

The physical-device recording after `fa5919b` shows stale first-row text and
switches on both script lists and short configuration categories. It supersedes
any inference that passing Robolectric sampling tests established GPU correctness.

`MiuixScrollBackdrop` adds draw-time subscriptions on **both** the body recorder
and native backdrop consumer. They observe actual list index/offset or column
offset, plus native header height/content offsets. The latter matters when a
short page moves solely because its title collapses. Merely reading the boolean
`isContentScrolled` or changing native band alpha is insufficient once alpha has
saturated. The source/consumer identities and native progressive shader pipeline
remain unchanged; no frame timer, bitmap copy or replacement blur is introduced.

`MiuixBackdropInvalidationTest` compares the unmodified native source with the
adapter inside separately retained layers at fixed geometry and alpha. It checks
that only the adapter subscribes both draw paths to repeated motion and return.
This proves the explicit invalidation contract, not physical GPU output. Device
verification must still confirm fresh first-card sampling and actual blur on
long lists and two-item categories, including repeated expand/collapse and return.
