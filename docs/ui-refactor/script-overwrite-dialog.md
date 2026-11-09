# Script overwrite confirmation

The existing script/project overwrite request now renders through mixui FormDialog and ordinary mixui buttons. MIUIX retains its native root-scaffold overlay; Mochi uses its Maven in-window dialog. This is a native ordinary confirmation, not a claim of a recorded glass scene.

Requests have identity even when title/body are identical. The wrapper stays composed during native exit, retains display text, permits only one action per visible request, and rejects retained callbacks from an older request. Cancel and Back only dismiss; confirmation still invokes the existing import request callback. No filesystem/import transaction logic is replaced.

The focused ScriptOverwriteDialogTest uses actual production dialog controls with offline callbacks. It covers both backends in light/dark, cancel, repeated confirm, reopening, identical requests during interrupted exit, canceled held pointers on replaced clickable nodes and Back without underlying page navigation. Generated screenshots are local Robolectric native-graphics fixtures, not physical-device evidence or real filesystem overwrite tests.
