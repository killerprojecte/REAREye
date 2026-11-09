# Ordinary dialog host boundary

The existing separate-window `OverlayDialog` now belongs to `mixui`. Its public parameters, bounds local and defaults expose Compose types, not MIUIX types. Twelve application caller files use that common host, including former same-package references. The implementation from its first remembered visibility state through disposal is unchanged. This is boundary extraction, not a replacement with glass or a different native window.

The notification-access prompt separates presentation from its existing owner. Tests supply inert callbacks: no root, permission-grant or notification-listener operation is executed. Actual permission business logic remains in the owner.

## Local validation

Robolectric API 36 native graphics, both backends and light/dark:

- `OverlayDialogContractTest`: 8 passed. Separate Android window, absent platform dim flag, optional dismissal veto, content/outside/back handling, owner-controlled visibility, one exit-completion callback, reopening; disabled prompt controls and inert confirm/cancel/error presentation.
- `RearCardSuggestionsTest`: 4 passed, including no spontaneous callbacks, suggestions/filtering/selection/back and window-relative placement.
- `ColorEditorInteractionTest`: 5 passed, including existing picker/dialog interactions.

All 17 tests executed with zero skips. The original host body was independently compared byte-for-byte before validation. Screenshots capture the dialog's own window; black outside its panel is the separate-window capture background, not proof that the underlying application became black.

This focused run does not certify real-device IME, wide-window layouts, predictive back, or interrupted animations. Their existing implementation was preserved, not newly claimed as device-tested. Script-debug and quick-action sheet candidates are separate work and are not part of this commit.
