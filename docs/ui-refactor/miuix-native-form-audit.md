# MIUIX native form and overlay audit

The application resolves MIUIX `0.9.4-ea1b935d-SNAPSHOT`.

## Corrections

- `TextField` now retains native `TextFieldDefaults.textFieldColors()` in MIUIX forms. Previously the glass form context overrode both the fill and the label, setting the title to `onSurface`. The official “With title” example supplies no color override. Bytecode inspection of the application's resolved MIUIX AAR confirms its defaults are `secondaryContainer`, `onSecondaryContainer`, and `primary` for background, label, and focus border respectively.
- `GlassFormDialog` now retains `GlassDialogDefaults.material()`. The removed light-theme customization replaced its third color layer with 75% white, obscuring more of the underlying page than the native material. Protected `GlassOverlayDialog` already used the unmodified material.

## Dropdown and sheet findings

`GlassAnchoredMenu` uses `GlassDropdownPopup` with its native defaults. The MIUIX sheet uses `OverlayBottomSheet` with `glassBottomSheetSurface`; its material-only recording also calls the same surface factory. Neither adds a new opaque panel over the material. Their page recordings exclude overlay content. No speculative material or blur-strength overrides were added.

The official GlassPage demo can display a full-page `blur_test` wallpaper. Its native dialog and popup materials use a 60dp blur and three color layers. A mostly uniform application page naturally shows less visual variation through those materials than the wallpaper demo. This source audit does not replace GPU/device verification of sampling, open/close, or scrolling.

## References

- https://github.com/compose-miuix-ui/miuix/blob/799d3d0/example/shared/src/commonMain/kotlin/component/TextFieldSection.kt
- https://github.com/compose-miuix-ui/miuix/blob/799d3d0/example/shared/src/commonMain/kotlin/GlassPage.kt
- https://github.com/compose-miuix-ui/miuix/blob/799d3d0/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassMaterials.kt

The reference checkout is newer than the resolved dependency. The TextField defaults were independently verified in the actual dependency AAR, and compilation checks API compatibility without changing the dependency version.

## Validation

- `:mixui:testDebugUnitTest` passed with `-PisPublicBeta=true -PuiCapture=true` (also compiled the modified module).
- `tools/check_ui_boundary.py`: zero direct MIUIX references outside mixui.
- `tools/check_decorative_effects.py`: six checks passed.
- `git diff --check`: passed. No workflow files changed.
- ADB reported no connected devices. No new device screenshots or GPU sampling verification are claimed.
