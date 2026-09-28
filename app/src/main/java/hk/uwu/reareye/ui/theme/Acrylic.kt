package hk.uwu.reareye.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberAcrylicHazeState(): HazeState {
    return rememberHazeState()
}

@Composable
fun rememberAcrylicHazeStyle(): HazeBlurStyle {
    val surface = MiuixTheme.colorScheme.surface
    val tintAlpha = if (surface.luminance() < 0.5f) 0.72f else 0.82f

    return remember(surface, tintAlpha) {
        HazeBlurStyle {
            backgroundColor(surface)
            colorEffects(
                listOf(HazeColorEffect.tint(surface.copy(alpha = tintAlpha))),
            )
            noiseFactor(0f)
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
fun Modifier.rearAcrylicEffect(
    hazeState: HazeState,
    hazeStyle: HazeBlurStyle,
    blurRadius: Dp = 24.dp,
): Modifier = this.hazeBlur(
    input = HazeInput.Sources(hazeState),
    style = hazeStyle.then {
        blurRadius(blurRadius)
    },
    performanceMode = HazePerformanceMode.Fixed(0.35f),
)

@OptIn(ExperimentalHazeApi::class)
fun Modifier.rearAcrylicSource(hazeState: HazeState): Modifier {
    return this.hazeSource(state = hazeState)
}
