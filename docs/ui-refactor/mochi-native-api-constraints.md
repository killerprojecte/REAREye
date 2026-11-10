# Mochi 0.2.0 native API constraints

Reviewed against the published `io.github.feice11:mochi-compose:0.2.0` sources and the application at `4833ef9`. The source checkout is not used as a replacement dependency.

## Text fields

Ordinary fields and standard bullet-masked passwords already use `MochiTextField`. Its public signature exposes `enabled`, `singleLine`, `maxLines` and `secure`, but not `readOnly`, `minLines` or a `VisualTransformation` parameter.

Keep the explicit MIUIX compatibility route for unsupported requests:

- `enabled = false` is not equivalent to read-only: it changes focus, selection and copy behavior. Ignoring edits still opens an editable input/IME.
- An outer minimum height is not the native editor's minimum line contract and can change text positioning and scrolling.
- `secure` only supports the library's standard masking; custom masks and arbitrary display/offset transformations cannot be forwarded.

Current business call sites request read-only protected business identifiers and bounds package values, all with enabled=false. These now use native Mochi disabled fields: disabled inputs in either backend cannot focus, select or open the keyboard, and Mochi disables both its clear and password-reveal controls. The routing exception applies only while disabled; enabled read-only fields still retain compatibility behavior. The widget-template helper forwards minLines but currently keeps the default of one; custom transformations and larger minimum line counts are compatibility API guarantees, not evidence of widespread active fallback use. Do not silently drop these behaviors to eliminate the fallback. `TextFieldRoutingTest` protects this distinction and ensures disabling a field never bypasses minimum-line or transformation compatibility checks.

## Editable form dialogs

`MochiDialog` unconditionally adds `mochiPressDeform(scale = 1f, follow = 6.dp)` to the card, including its fields. Its public API has no parameter to disable this movement for text selection and cursor gestures. Suppressing child pointer events to avoid it would break native selection and scrolling. Built-in action buttons also invoke dismissal after their callback, which does not directly model validation that keeps a form open.

`StableMochiFormDialog` therefore remains a compatibility host, not a direct `MochiDialog` delegation. It uses public Mochi colors, shape, glass material, shadow and motion APIs while keeping the card stationary during field gestures. Native field selection and cursor blinking remain enabled. Its retained window layer additionally handles focus removal and blocks new gestures during exit. No vendor source is copied or patched.

A future library upgrade can remove this host only after exposing suitable motion/lifecycle control and passing text selection, repeated open/close, validation, dismissal, exit-touch blocking and real device keyboard checks. JVM tests alone do not establish IME behavior.

## Mary jump-to-latest surface

The app now imports `mixui.components.Surface` rather than Material3 Surface. Geometry, colors, elevation, click callback and visibility timing are unchanged. MIUIX delegates to its native surface; Mochi has no plain Surface API in 0.2.0, so mixui uses its established Compose solid-surface implementation with Mochi content colors. This is a unified boundary, not a claim that Mochi supplies a native Surface component.

The Mary layout regression runs the jump button's placement, click, scrolling hide, timed fade and disabled-during-fade assertions under both backends.

## Verification of this follow-up

- Five `TextFieldRoutingTest` cases pass, including disabled read-only inputs and retained fallback for unsupported transforms/minimum lines.
- Three focused `MaryChatLayoutTest` cases pass with `-PmaryUiResources=true`: MIUIX jump button, Mochi jump button, and disabled native read-only field. The disabled field exposes no SetText action, does not gain focus after a real Compose touch, receives no writes, and reflects externally updated protected text. Jump button accessibility retains the button role.
- `tools/check_ui_boundary.py`, `tools/check_decorative_effects.py` (six checks) and `git diff --check` pass.
- Both backend jump-button images were inspected. No full app suite, APK assembly, release shrinker run or real-device keyboard test is claimed for this follow-up. The editable-form host is unchanged.
