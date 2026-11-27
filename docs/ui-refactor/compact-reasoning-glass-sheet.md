# Compact reasoning glass sheet

GlassBottomSheet has opt-in Content sizing and content nested-scroll control. Large remains the default for the existing theme, search, rename and license sheets. MIUIX delegates to the native content-sized GlassBottomSheet. Mochi's Content layout measures its bottom-aligned surface and places the native glass material and foreground under that same geometry; the material source never records foreground controls. A Content-sized Mochi sheet requires GlassBottomSheetScene instead of silently falling back to the old Large-only window.

Mary's reasoning strength picker uses Content and disables content nested scrolling. Its horizontal slider, supported levels and commit policy are unchanged. The shared header remains draggable; short content no longer expands to a Large page. Four unused MIUIX tooltip imports were also removed, not counted as migrated live controls.

ReasoningGlassSheetTest covers both backends, light/dark and LTR/RTL: compact geometry, horizontal drag commits once without moving the sheet, canceled slider gestures, disabled state, Back/reopen, scrim dismissal and material response to a changing opaque page source. Captures use a deliberately red/green diagnostic page, not the full Mary screen. The existing Large sheet high-frequency sampling and held-drag tests remain separate regressions; passing a response check alone is not evidence that unblurred page content is correct glass.

The original Mary reasoning tests additionally check supported model levels, retained empty composer editing after dismissal and real intermediate thumb animation. Their fixtures now establish the production glass scene. No physical-device keyboard, predictive-back gesture or accessibility-service certification is claimed.
