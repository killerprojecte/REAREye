package hk.uwu.mixui.backend

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/** No rendering or gesture consumption. Mochi 0.2.0 mistakes synthetic cancel-up for release. */
internal class MochiButtonActivation {
    var primary: PointerId? = null
    var released = false
    var key: Key? = null

    fun clear() {
        primary = null
        released = false
        key = null
    }
}

/**
 * The release gate follows BottomSheetActionButton's existing compatibility fix.
 * Track the original finger, not any finger's UP. The native control still owns
 * its slop, visual deformation, glow, click hit testing and pointer consumption.
 * Accessibility and keyboard activation are separate from touch authorization.
 */
@Composable
internal fun rememberMochiButtonActivation(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
): Pair<Modifier, () -> Unit> {
    val gate = remember(enabled) { MochiButtonActivation() }
    val currentClick = rememberUpdatedState(onClick)
    val currentEnabled = rememberUpdatedState(enabled)
    val activate = {
        if (currentEnabled.value) currentClick.value()
    }
    val guarded = {
        if (currentEnabled.value && gate.released) {
            gate.released = false
            currentClick.value()
        }
    }
    val input = modifier
        .semantics {
            onClick {
                if (currentEnabled.value) { activate(); true } else false
            }
        }
        .onFocusChanged { if (!it.isFocused) gate.key = null }
        .onPreviewKeyEvent { event ->
            val activationKey = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                event.key == Key.Spacebar || event.key == Key.DirectionCenter
            if (!currentEnabled.value || !activationKey) false
            else when (event.type) {
                KeyEventType.KeyDown -> {
                    if (gate.key == null && event.nativeKeyEvent.repeatCount == 0) gate.key = event.key
                    true
                }
                KeyEventType.KeyUp -> {
                    val armed = gate.key == event.key && !event.nativeKeyEvent.isCanceled
                    gate.key = null
                    if (armed) activate()
                    true
                }
                else -> false
            }
        }
        .focusable(enabled)
        .pointerInput(gate, enabled) {
            if (!enabled) return@pointerInput
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // Cancellation may bypass this observer's Initial pass.
                        // A new all-down stream must discard a canceled stream's ID.
                        if (event.changes.none { it.previousPressed }) {
                            gate.primary = event.changes.firstOrNull { it.changedToDown() }?.id
                        }
                        gate.released = event.changes.any { it.id == gate.primary && it.changedToUp() }
                        val allReleased = event.changes.none { it.pressed }
                        awaitPointerEvent(PointerEventPass.Final)
                        gate.released = false
                        if (allReleased) gate.primary = null
                    }
                }
            } finally {
                gate.clear()
            }
        }
    return input to guarded
}
