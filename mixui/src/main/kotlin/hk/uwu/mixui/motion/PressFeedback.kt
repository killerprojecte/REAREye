package hk.uwu.mixui.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.effect.elastic.mochiPressDeform

/** Backend-independent motion: observe the gesture without consuming the component's click. */
fun Modifier.mixPressFeedback(enabled: Boolean = true, scale: Float = 0.96f, follow: Dp = 3.dp): Modifier =
    if (enabled) mochiPressDeform(scale = scale, follow = follow) else this
