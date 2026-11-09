package hk.uwu.mixui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * A field-owned form in a recorded preference page. Register it with the page's
 * sibling overlay host, never inside the backdrop source. The entry remains
 * mounted while hidden so the native dialog owns exit animation and IME cleanup.
 */
@Composable
fun GlassPreferenceFormDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val overlays = checkNotNull(LocalPreferenceOverlays.current) {
        "GlassPreferenceFormDialog requires GlassTopBarScaffold or GlassPreferenceScene"
    }
    val backdrop = checkNotNull(LocalGlassPreferenceBackdrop.current)
    val direction = LocalLayoutDirection.current
    val token = remember { Any() }
    val entry by rememberUpdatedState<@Composable BoxScope.(Offset) -> Unit>({ _ ->
        CompositionLocalProvider(
            LocalGlassFormBackdrop provides backdrop,
            LocalLayoutDirection provides direction,
        ) {
            GlassFormDialog(show, title, onDismissRequest, modifier, content)
        }
    })
    DisposableEffect(overlays, token) {
        overlays.entries[token] = { origin -> entry(origin) }
        onDispose { overlays.entries.remove(token) }
    }
}
