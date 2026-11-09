# Mochi sheet material sampling

The theme editor opts into the reusable `mixui.GlassBottomSheet` container inside
`GlassBottomSheetScene`. Other existing sheet callers are not switched in this
change. MIUIX continues to use its native glass sheet. Mochi materials and controls
remain Maven dependencies; the container is mixui-owned, not a copied native sheet.

## Source order

1. Record opaque page content plus the animated modal scrim as B.
2. Draw the public Mochi sheet glass material from B into a separate background S.
3. Draw tabs and foreground controls as siblings, sampling S rather than B.

The moving sheet applies one geometry to its background and foreground. S never
records foreground controls, and B never records the sheet. This avoids both
self-sampling and sharp original-page text bleeding through the tabs. Merely
changing tint or dropping glass was not the fix. The caller supplies a scene at
the page boundary; legacy calls without one retain their existing behavior.

The public `sessionKey` distinguishes close/reopen events coalesced into one
composition. Dismissal is guarded once per session. Back is consumed through the
retained exit animation. Content scroll takes priority before downward sheet drag;
saving can veto dismissal without disabling content scrolling. The page semantics
are hidden while this modal host is retained. Native dialog input/button styling
is unchanged.

## Local verification

All screenshots use Robolectric native graphics, not a physical device GPU.

- Two actual theme-editor preview tests passed, including backend draft switching,
  cancellation, and persistence callback behavior.
- Two material-source tests passed for light/dark at rest and during a real held
  pointer drag. Black/white high-frequency phase response is 0.75–1.42% of a direct
  page-sampling negative control; same-frame stability error is zero. Low-frequency
  RGB response remains 4.7–8.9%, proving the candidate has not become a solid fill.
- Four pointer/Back/session lifecycle integration tests passed: content-to-sheet
  scroll transfer, dismissal veto, interrupted/coalesced reopen, and Back isolation
  until exit finishes. Two pure dismissal-gate tests passed.
- Synthetic IME insets keep S full-height, reduce the foreground viewport, restore
  it when IME insets clear, and preserve save/close behavior. This is not evidence
  of physical keyboard animation or platform IME Back behavior.

Real-device predictive Back, TalkBack focus restoration, keyboard transitions,
and nested multi-sheet routing still require device validation. Other sheets will
be migrated to the shared host in separate verified batches.
