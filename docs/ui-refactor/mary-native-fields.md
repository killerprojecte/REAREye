# Mary native settings fields

MarySettingsField now uses mixui PreferenceRow for complete summaries and held state, and mixui FormDialog for native in-window form presentation. MIUIX delegates to OverlayDialog with root scaffold rendering. Mochi uses the stationary editable-form host built with public Maven Mochi rendering APIs. The original direct MochiDialog delegation was replaced to keep text selection from moving the entire card; see [native API constraints](mochi-native-api-constraints.md). No vendor source is copied; model draft, endpoint, credential and provider semantics remain unchanged. A retained exit cannot submit a field a second time.

The first unpublished candidate accidentally resolved the app's same-package Android Dialog after removing the MIUIX import. The existing IME test detected a changed host: expected 260 px movement, observed 0 px. It was replaced with the native in-window abstraction before publication. The original IME movement/restoration assertions were not changed.

Validation:
- MaryChatLayoutTest.rootHostedSettingsEditorAndPageApplyTheKeyboardInsetOnlyOnce passes unchanged (local synthetic 260 px activity IME).
- MarySettingsFieldInteractionTest: both methods pass for MIUIX/Mochi and light/dark, covering draft cancellation, external value on reopening, confirm, masking and explicit credential clearing.
- Local native-graphics screenshots of the actual field editor are under build/reports/ui-capture/mary-settings-field. They are seeded component/page fixtures, not whole Mary navigation or physical-device IME evidence.

FormDialog does not claim the recorded background contract of GlassFormDialog. This preserves the ordinary Mary editor while removing direct app vendor calls. A glass Mary editor needs a page-level recorded overlay scene and is not claimed complete by this batch.
