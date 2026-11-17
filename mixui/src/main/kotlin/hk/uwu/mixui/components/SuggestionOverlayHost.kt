package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/** Same-window, non-focusable suggestions, above sheets and outside all backdrop recordings. */
internal class SuggestionOverlays {
    val entries = mutableStateMapOf<Any, @Composable BoxScope.() -> Unit>()
}

internal val LocalSuggestionOverlays = staticCompositionLocalOf<SuggestionOverlays?> { null }

@Composable
internal fun SuggestionOverlayHost(content: @Composable () -> Unit) {
    val overlays = remember { SuggestionOverlays() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSuggestionOverlays provides overlays, content = content)
        overlays.entries.forEach { (token, entry) -> key(token) { entry() } }
    }
}

@Composable
internal fun SuggestionOverlay(content: @Composable BoxScope.() -> Unit) {
    val overlays = checkNotNull(LocalSuggestionOverlays.current)
    val token = remember { Any() }
    val locals = rememberUpdatedState(currentCompositionLocalContext)
    val latest = rememberUpdatedState(content)
    DisposableEffect(overlays, token) {
        overlays.entries[token] = { RenderSuggestionOverlay(latest, locals) }
        onDispose { overlays.entries.remove(token) }
    }
}

/** A real restart scope keeps portal content responsive to query and IME changes. */
@Composable
private fun BoxScope.RenderSuggestionOverlay(
    content: State<@Composable BoxScope.() -> Unit>,
    locals: State<CompositionLocalContext>,
) {
    val render = content.value
    CompositionLocalProvider(locals.value) { render() }
}

/** Observe outside presses without stealing the editor, sheet controls or scrolling underneath. */
internal fun Modifier.observeSuggestionOutsideTap(onDown: (Offset) -> Unit): Modifier =
    then(SuggestionOutsideTapElement(onDown))

private data class SuggestionOutsideTapElement(val onDown: (Offset) -> Unit) :
    ModifierNodeElement<SuggestionOutsideTapNode>() {
    override fun create() = SuggestionOutsideTapNode(onDown)
    override fun update(node: SuggestionOutsideTapNode) { node.onDown = onDown }
    override fun InspectorInfo.inspectableProperties() { name = "suggestionOutsideTap" }
}

private class SuggestionOutsideTapNode(var onDown: (Offset) -> Unit) :
    Modifier.Node(), PointerInputModifierNode {
    private var press: Offset? = null
    override fun sharePointerInputWithSiblings() = true
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        pointerEvent.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { press = it.position }
        if (pointerEvent.changes.any { it.changedToUpIgnoreConsumed() }) {
            val start = press
            press = null
            // Let siblings finish the same click/drag before removing this hit path.
            if (start != null) onDown(start)
        }
    }
    override fun onCancelPointerInput() { press = null }
}
