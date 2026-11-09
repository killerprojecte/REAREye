package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

private class MochiMenuActivation {
    var primary: PointerId? = null
    var generation = 0L
    var request = 0L
    var held = false
    var released = false
    var completed = -1L

    fun cancel() {
        request++
        primary = null
        held = false
        released = false
        completed = -1L
    }
}

/**
 * Observe, never replace/consume, the native menu gesture. Mochi 0.2.0's
 * LongPress branch mistakes Compose's consumed synthetic cancel-UP for a tap.
 * A real release is authorized during its Initial-to-Final dispatch. A native
 * long-press request is checked one frame later, after cancellation has reached
 * every pointer node; it must still belong to a held or normally ended stream.
 * Native geometry, press animation, long-press timing and slide-selection stay
 * in Mochi. Accessibility is independent of the touch stream.
 */
@Composable
internal fun rememberMochiMenuActivation(
    modifier: Modifier,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
): Pair<Modifier, (Boolean) -> Unit> {
    val gate = remember(enabled) { MochiMenuActivation() }
    val scope = rememberCoroutineScope()
    val currentEnabled = rememberUpdatedState(enabled)
    val currentExpanded = rememberUpdatedState(expanded)
    val currentChange = rememberUpdatedState(onExpandedChange)
    val guarded: (Boolean) -> Unit = { next ->
        when {
            !next -> {
                gate.request++
                currentChange.value(false)
            }
            !currentEnabled.value -> Unit
            gate.released -> currentChange.value(true)
            gate.held -> {
                val generation = gate.generation
                val request = ++gate.request
                scope.launch {
                    withFrameNanos { }
                    if (currentEnabled.value && gate.generation == generation && gate.request == request &&
                        (gate.held || gate.completed == generation)) {
                        currentChange.value(true)
                    }
                }
            }
            else -> Unit
        }
    }
    val input = modifier
        .semantics {
            onClick {
                if (currentEnabled.value) {
                    gate.request++
                    currentChange.value(!currentExpanded.value)
                    true
                } else false
            }
        }
        .pointerInput(gate, enabled) {
            if (!enabled) return@pointerInput
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.previousPressed }) {
                            gate.cancel()
                            gate.generation++
                            gate.primary = event.changes.firstOrNull { it.changedToDown() }?.id
                        }
                        val primary = event.changes.firstOrNull { it.id == gate.primary }
                        gate.released = primary?.changedToUp() == true
                        gate.held = primary?.pressed == true
                        if (gate.released) gate.completed = gate.generation
                        if (!gate.held && !gate.released) gate.cancel()
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        val finalPrimary = final.changes.firstOrNull { it.id == gate.primary }
                        // A synthetic cancel can reach a node while it is already
                        // awaiting Final, bypassing this observer's Initial pass.
                        // Real UP is also consumed by Mochi in Main, so preserve
                        // the authorization recorded at Initial instead of testing
                        // changedToUp() on Final's now-consumed normal release.
                        if (finalPrimary?.pressed != true && !gate.released) gate.cancel()
                        gate.held = finalPrimary?.pressed == true
                        gate.released = false
                        if (!gate.held) gate.primary = null
                    }
                }
            } finally {
                gate.cancel()
            }
        }
    return input to guarded
}
