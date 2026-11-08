package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/** Native slider on both backends, with caller-owned value and completion callbacks. */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    require(steps >= 0) { "steps should be >= 0" }
    require(valueRange.start < valueRange.endInclusive) { "valueRange start should be less than end" }
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.basic.Slider(
            value = value, onValueChange = onValueChange, modifier = modifier,
            enabled = enabled, valueRange = valueRange, steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
        MixUiBackend.Mochi -> ui.feice.mochi.basic.MochiSlider(
            value = value, onValueChange = onValueChange, modifier = modifier,
            enabled = enabled, valueRange = valueRange, steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}
