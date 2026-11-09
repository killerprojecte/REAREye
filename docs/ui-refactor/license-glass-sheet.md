# Stable license-sheet host

About's license modal now uses mixui GlassBottomSheet. Its state and host live in
LicenseContent rather than inside a recyclable LibraryItem. Rows only request a
license selection. Selected text remains during exit and is released after the
matching session finishes; old dismiss/completion callbacks cannot clear a newly
opened license. Root's existing scene supplies the material source.

The original generated dependency license name, content/URL fallback and clickable
URL annotations are retained. The existing 560dp scrolling-body limit is unchanged.
No dependency license or embedded font was added or removed by this migration.

Four actual LicenseContent tests passed locally with generated Maven license data:
MIUIX/Mochi light/dark. Each clicks a real badge, scrolls its source row out of the
lazy viewport, verifies retained exit text, interrupts closing with a different
license, rejects old-session callbacks, closes through Back, and reopens the same
license. Tests compare exact text and URL annotation counts, and require the real
backend host tag. Screenshots use Robolectric native graphics, not a device.
