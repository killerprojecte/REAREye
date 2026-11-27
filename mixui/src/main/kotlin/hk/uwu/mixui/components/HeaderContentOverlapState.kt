package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * Tracks visible leading content, including a native overscroll layer's displacement.
 * Scroll-state offsets do not contain elastic translation. Observe the actual header
 * and first content layout nodes in the same window instead of guessing from input.
 * Only the overlap threshold is snapshot state; pixel motion never recomposes callers.
 */
@Stable
class HeaderContentOverlapState internal constructor() {
    var overlapsHeader by mutableStateOf(false)
        private set
    private var headerBottom: Float? = null
    private var leadingTop: Float? = null
    private var leadingOwner: Any? = null

    private fun update() {
        val bottom = headerBottom
        val top = leadingTop
        overlapsHeader = bottom != null && top != null && top < bottom
    }

    /** Apply to the real fixed header, not the content's outer viewport. */
    @Composable
    fun headerModifier(): Modifier {
        DisposableEffect(this) {
            onDispose { headerBottom = null; update() }
        }
        return Modifier.onGloballyPositioned { coordinates ->
            headerBottom = coordinates.positionInWindow().y + coordinates.size.height
            update()
        }
    }

    /**
     * Apply inside the scrolling/overscrolling hierarchy to its first real content.
     * Disposal clears a recycled Lazy item; an old item cannot clear its replacement.
     * Long lists must still combine [overlapsHeader] with their ordinary scroll state.
     */
    @Composable
    fun leadingContentModifier(): Modifier {
        val owner = remember(this) { Any() }
        DisposableEffect(this, owner) {
            onDispose {
                if (leadingOwner === owner) {
                    leadingOwner = null
                    leadingTop = null
                    update()
                }
            }
        }
        return Modifier.onGloballyPositioned { coordinates ->
            leadingOwner = owner
            leadingTop = coordinates.positionInWindow().y
            update()
        }
    }
}

/** A new content key prevents an old conversation's position leaking into a new one. */
@Composable
fun rememberHeaderContentOverlapState(contentKey: Any?): HeaderContentOverlapState =
    remember(contentKey) { HeaderContentOverlapState() }
