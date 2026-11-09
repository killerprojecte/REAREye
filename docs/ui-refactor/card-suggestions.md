# Card business suggestions

`RearCardCreateDialog` now uses reusable `mixui.SuggestionMenu` instead of directly
calling MIUIX dropdown implementation/overlay helpers. The ordinary opaque menu
follows the selected backend's color tokens; it is intentionally not a glass input
or a glass button. The existing outer dialog is unchanged.

The public Compose Popup is non-focusable, so editing continues while filtering.
The dialog handles Back by closing visible suggestions first. Business identity,
case-insensitive filtering, distinct/sorted options, dynamic catalog changes and
default-priority selection remain caller-owned. Popup geometry is constrained by
the dialog/window/IME bounds, with RTL positioning and offscreen-anchor checks.

Verification: four actual Card dialog tests (MIUIX/Mochi, light/dark) passed with
filtering, Back, continued typing, dynamic catalog update, selection/priority,
confirm and cancel. Five pure geometry tests cover bounds/RTL/offscreen cases.
These are local Robolectric tests. Captures of the dialog and popup are separate
native-window images, not a fabricated full-screen composite. Device keyboard,
TalkBack and platform popup positioning still need device verification.
