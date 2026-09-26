package hk.uwu.reareye.ui.components.config.draggable.library.draggable

import kotlinx.coroutines.android.awaitFrame
import kotlin.math.absoluteValue
import kotlin.math.pow

private const val BASE_SPEED = 20f // Default base speed

interface Autoscroller {
    suspend fun start(
        dragDirection: DragDirection,
        overscroll: Float,
        scrollBy: suspend (Float) -> Unit,
    )
}

internal class DefaultAutoscroller(
    private val speedFactor: Float = 1f,
) : Autoscroller {

    init {
        if (speedFactor <= 0f) {
            throw IllegalArgumentException("speedFactor must be higher than 0")
        }
    }

    override suspend fun start(
        dragDirection: DragDirection,
        overscroll: Float,
        scrollBy: suspend (Float) -> Unit,
    ) {
        val directionMultiplier = when (dragDirection) {
            DragDirection.START -> -1
            DragDirection.END -> 1
            else -> 0
        }

        awaitFrame()
        // Flip overscroll if going up
        val adjustedOverscroll =
            if (dragDirection == DragDirection.START) -overscroll else overscroll

        val scaledScroll = calculateScaledScroll(adjustedOverscroll, speedFactor)
        scrollBy(scaledScroll * directionMultiplier)
    }

    private fun calculateScaledScroll(overscroll: Float, speedFactor: Float): Float {
        // Apply a non-linear transformation to make the speed increase faster with bigger overscroll
        val nonLinearInterpolator = overscroll.coerceIn(-1f, 1f).absoluteValue.pow(2f)

        // Calculate scroll speed directly from overscroll percentage and speed factor
        return nonLinearInterpolator * BASE_SPEED * speedFactor
    }
}

