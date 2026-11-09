# Search bar migration

Base: `9dc22801ca450d806a984f74d2f04924fd5e82e2`.

`mixui.components.SearchBar` owns the reusable controlled field. `SearchBarStyle.Default`
retains the original acrylic layout, dimensions, icon and IME/focus behavior.
`SearchBarStyle.Native` selects the native control of the current MixUi backend.
The existing app preference values DEFAULT and MIUIX remain unchanged; the app's
RearSearchBar maps them to Default and Native respectively. No application configuration
or preference dependency enters mixui. The material-icons-core dependency preserves
exactly the original Default search vector rather than changing that style's glyph.

## Backend boundaries

- MIUIX retains the original InputField/SearchBar arguments and interaction-source focus
  observation. Outer expanded remains false: the original adapter had a clear affordance,
  but no outside cancel button or expanded results overlay.
- Maven Mochi 0.2.0 / source tag v0.2.0 provides query, onQueryChange, placeholder,
  onSearch and onCancel. It does not expose interactionSource or a focus callback.
  The wrapper places its native control inside an onFocusChanged/focusGroup boundary
  and reports hasFocus to preserve descendant focus changes. Its IME callback clears
  focus, hides the keyboard and submits once. Native clearing/cancel behavior remains
  owned by Mochi; onCancel also hides the keyboard, without submitting.
- Default has neither clear nor cancel buttons. No new ones are added.
- No vendor sources are copied. The local MIUIX source checkout is 799d3d09, not the
  pinned ea1b935d snapshot, so its implementation is not claimed as exact pin evidence.
  The candidate preserves the existing pin-compatible MIUIX invocation unchanged.

## Validation gate

SearchBarContractTest is registered in the uiCapture include and ordinary-test exclude
lists. It uses real components under MixUiHost and covers both backends, both search
styles, light/dark, hint, input, native clear, IME, focus true/false, keyboard hide
requests, repeated entry, and Mochi native cancellation. Captures include empty,
focused and submitted states under `search-bars/`.

Run through the repository's configured local capture workflow, filtering
`hk.uwu.reareye.ui.components.SearchBarContractTest` with `-PuiCapture=true` and a
`ui.capture.output` directory. The test replaces only SoftwareKeyboardController to
count hide requests; actual device keyboard visibility remains an integration check.

The pinned application and mixui compiled successfully. The two SearchBarContractTest
methods and the actual Store root search/sort/navigation regression ran and passed
locally with Robolectric native graphics. This includes focusGroup aggregation,
IME submission, focus clearing, native clear/cancel, and repeated entry. The first
runner used an incorrect test package filter and executed only the Store test; the
corrected runner executed all three methods. These are functional local-render tests,
not physical-device keyboard or GPU-glass proof.
