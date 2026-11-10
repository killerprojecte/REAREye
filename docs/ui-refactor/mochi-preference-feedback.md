# Mochi full-width preference feedback

Keep the existing native title/control row and full-width, unbounded native footer.
Do not place descriptions in ListRow.status: trailing controls constrain that
column and make long explanations unnecessarily narrow.

A matchParentSize native MochiListRow behind the foreground supplies the native
press, release and scroll-cancellation feedback across both title and footer.
Foreground rows do not have click handlers; native trailing switches and pickers
retain their own gestures. The wrapper owns the accessible action and label,
while checkbox/radio/switch state remains on the corresponding whole item.

Validation on 42b11ca plus this change:
- NativeWholePreferenceFeedbackTest: 7 passing tests (press/release/cancel,
  scroll cancellation, disabled state, exact-once actions, switch and picker).
- NativePickerMusicCandidateTest: 3 passing tests (production music page,
  complete descriptions at 1.5x font size, single/multiple selection persistence
  across both UI backends and light/dark themes).
- Six closed-state Robolectric captures match the approved a90cc7b layout
  pixel-for-pixel. Held-state pixels have equal title/footer background colors.
- No workflow changes. Screenshots are local Robolectric renders, not device
  screenshots; physical-device validation remains outstanding.
