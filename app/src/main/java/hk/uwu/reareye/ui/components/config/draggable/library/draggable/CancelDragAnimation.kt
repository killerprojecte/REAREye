package hk.uwu.reareye.ui.components.config.draggable.library.draggable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset


internal class CancelDragAnimation {

    private val animatable = Animatable(Offset.Zero, Offset.VectorConverter)

    internal var position by mutableStateOf<Int?>(null)
    internal var key by mutableStateOf<Any?>(null)
    internal val offset
        get() = animatable.value

    suspend fun animatableChangePosition(itemPosition: Int?, itemKey: Any?, snapTo: Offset) {
        key = itemKey
        position = itemPosition
        animatable.snapTo(snapTo)
        animatable.animateTo(
            Offset.Zero,
            spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = Offset.VisibilityThreshold
            )
        )
        position = null
        key = null
    }
}
