# Search and rename glass-sheet adoption

MoreSearchBottomSheet and ScriptRenameDialog now use the shared GlassBottomSheet
host. Root's existing scene supplies the backdrop; no nested scene was added.
The theme editor remains the initial reference, not a special-case implementation.

More preserves deferred navigation in onDismissFinished, query retention on
programmatic hide, Back cancellation and interrupted reopening. Its 52–68% height
bounds are content constraints, not another IME inset. A 480dp short-screen test
with synthetic 220px IME verifies the reduced results viewport and scroll access
to the final result, then geometry restoration and one completed navigation.

Rename retains its header actions, editable field and blank-name validation.
Save is guarded once per naming session, including delayed closure. Reopening
restores initialName and resets the guard.

Seven local Robolectric methods passed across MIUIX and Mochi, including ordinary
legacy fallback and scene-backed mode. Scene-backed Mochi tests explicitly require
the new host tag. They cover filtering, selection completion, cancellation,
interrupted hide/selection, IME constraints, header placement and repeated Save.
This is simulated rendering/input, not physical-device keyboard verification.

Script debug, Mary reasoning, quick-action sorting and About license sheets are
not migrated by this change. Their specialized lifetime/gesture/inset behavior
needs separate tests; arbitrary nested sheets are not claimed supported.
