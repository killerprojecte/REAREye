package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.icons.Icons
import ui.feice.mochi.basic.MochiDropdownDefaults
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.theme.MochiDefaults

/** Explicit text fields for native settings adapters; never inferred from localized summary.
 * The legacy summary remains authoritative for other backends and older callers.
 */
data class PreferenceSupportingText(val description: String?, val selectedValue: String)

/** Public theme tokens produce the same opaque gray fill used by the native picker.
 * Internal MochiGlyphs is deliberately not accessed or copied.
 */
@Composable
internal fun nativePreferencePickerColors() = MochiDropdownDefaults.dropdownColors(
    buttonColor = MochiDefaults.fill().compositeOver(MochiDefaults.groupedContainer()),
    buttonContentColor = MochiListDefaults.colors().content,
)

@Composable
internal fun NativePreferencePickerIndicator(expanded: Boolean) {
    Spacer(Modifier.width(6.dp))
    Icon(Icons.Basic.ArrowRight, contentDescription = null,
        tint = MochiListDefaults.colors().secondary, modifier = Modifier.size(13.dp).rotate(if (expanded) -90f else 90f))
}
