@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package hk.uwu.reareye.ui.components.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleScope
import androidx.compose.foundation.style.StyleStateKey
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.scale
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val DEFAULT_VISIBILITY_DELAY = 0

private enum class ArtRevealPhase {
    Hidden,
    Primed,
    Visible,
}

private val artRevealPhaseKey = StyleStateKey(ArtRevealPhase.Hidden)

private var MutableStyleState.artRevealPhase
    get() = this[artRevealPhaseKey]
    set(value) {
        this[artRevealPhaseKey] = value
    }

private fun StyleScope.artRevealHidden(value: () -> Unit) {
    state(artRevealPhaseKey, value) { key, styleState -> styleState[key] == ArtRevealPhase.Hidden }
}

private fun StyleScope.artRevealPrimed(value: () -> Unit) {
    state(artRevealPhaseKey, value) { key, styleState -> styleState[key] == ArtRevealPhase.Primed }
}

private fun StyleScope.artRevealVisible(value: () -> Unit) {
    state(artRevealPhaseKey, value) { key, styleState -> styleState[key] == ArtRevealPhase.Visible }
}

@Composable
fun ArtRevealItem(
    visible: Boolean,
    delayMillis: Int = 0,
    content: @Composable () -> Unit,
) {
    ArtVisibilityMotion(
        visible = visible,
        delayMillis = delayMillis,
        enterAlphaDurationMillis = 240,
        enterTransformDurationMillis = 360,
        exitAlphaDurationMillis = 120,
        exitTransformDurationMillis = 160,
        hiddenEnterScale = 0.985f,
        hiddenExitScale = 0.992f,
        slideDivisor = 14,
    ) {
        content()
    }
}

@Composable
fun ArtStaggeredReveal(
    visible: Boolean,
    revealKey: Any,
    delayMillis: Int = DEFAULT_VISIBILITY_DELAY,
    content: @Composable () -> Unit,
) {
    ArtVisibilityMotion(
        visible = visible,
        revealKey = revealKey,
        delayMillis = delayMillis,
        enterAlphaDurationMillis = 220,
        enterTransformDurationMillis = 320,
        exitAlphaDurationMillis = 90,
        exitTransformDurationMillis = 120,
        hiddenEnterScale = 0.988f,
        hiddenExitScale = 0.992f,
        slideDivisor = 18,
    ) {
        content()
    }
}

@Composable
fun ArtVisibilityMotion(
    visible: Boolean,
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    enterAlphaDurationMillis: Int,
    enterTransformDurationMillis: Int,
    exitAlphaDurationMillis: Int,
    exitTransformDurationMillis: Int,
    hiddenEnterScale: Float,
    hiddenExitScale: Float,
    slideDivisor: Int,
    hiddenOffsetFallback: Dp = 18.dp,
    revealKey: Any = Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var contentHeightPx by remember(revealKey) { mutableIntStateOf(0) }
    var revealPhase by remember(revealKey) {
        mutableStateOf(if (visible) ArtRevealPhase.Primed else ArtRevealPhase.Hidden)
    }

    LaunchedEffect(visible, revealKey, delayMillis) {
        if (visible) {
            revealPhase = ArtRevealPhase.Primed
            if (delayMillis > 0) {
                delay(delayMillis.toLong())
            }
            revealPhase = ArtRevealPhase.Visible
        } else {
            revealPhase = ArtRevealPhase.Hidden
        }
    }

    val hiddenOffsetPx = remember(contentHeightPx, density, slideDivisor) {
        if (contentHeightPx > 0) {
            contentHeightPx / slideDivisor.toFloat()
        } else {
            with(density) { hiddenOffsetFallback.toPx() }
        }
    }
    val revealStyleState = remember(revealKey) { MutableStyleState(null) }
    revealStyleState.artRevealPhase = revealPhase
    val revealStyle = remember(
        hiddenOffsetPx,
        hiddenEnterScale,
        hiddenExitScale,
        enterAlphaDurationMillis,
        enterTransformDurationMillis,
        exitAlphaDurationMillis,
        exitTransformDurationMillis,
    ) {
        Style {
            alpha(0f)
            scale(hiddenExitScale)
            translationY(hiddenOffsetPx)

            artRevealHidden {
                animate(
                    tween(
                        durationMillis = exitAlphaDurationMillis,
                        easing = FastOutLinearInEasing,
                    )
                ) {
                    alpha(0f)
                }
                animate(
                    tween(
                        durationMillis = exitTransformDurationMillis,
                        easing = FastOutLinearInEasing,
                    )
                ) {
                    scale(hiddenExitScale)
                    translationY(hiddenOffsetPx)
                }
            }

            artRevealPrimed {
                animate(
                    tween(
                        durationMillis = exitAlphaDurationMillis,
                        easing = FastOutLinearInEasing,
                    )
                ) {
                    alpha(0f)
                }
                animate(
                    tween(
                        durationMillis = exitTransformDurationMillis,
                        easing = FastOutLinearInEasing,
                    )
                ) {
                    scale(hiddenEnterScale)
                    translationY(hiddenOffsetPx)
                }
            }

            artRevealVisible {
                animate(
                    tween(
                        durationMillis = enterAlphaDurationMillis,
                        easing = LinearOutSlowInEasing,
                    )
                ) {
                    alpha(1f)
                }
                animate(
                    tween(
                        durationMillis = enterTransformDurationMillis,
                        easing = FastOutSlowInEasing,
                    )
                ) {
                    scale(1f)
                    translationY(0f)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { contentHeightPx = it.height }
            .styleable(revealStyleState, revealStyle)
    ) {
        content()
    }
}

@Composable
fun <T> ArtSwapContent(
    targetState: T,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any = { it as Any },
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        modifier = modifier.graphicsLayer { clip = true },
        targetState = targetState,
        contentKey = contentKey,
        transitionSpec = {
            (fadeIn(
                animationSpec = tween(
                    durationMillis = 200,
                    delayMillis = 40,
                    easing = LinearOutSlowInEasing,
                )
            ) + scaleIn(
                initialScale = 0.992f,
                animationSpec = tween(
                    durationMillis = 260,
                    easing = FastOutSlowInEasing,
                ),
            ) + slideInVertically(
                animationSpec = tween(
                    durationMillis = 260,
                    easing = FastOutSlowInEasing,
                )
            ) { it / 20 }) togetherWith (
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 100,
                            easing = FastOutLinearInEasing,
                        )
                    ) + scaleOut(
                        targetScale = 0.996f,
                        animationSpec = tween(
                            durationMillis = 120,
                            easing = FastOutLinearInEasing,
                        ),
                    ) + slideOutVertically(
                        animationSpec = tween(
                            durationMillis = 120,
                            easing = FastOutLinearInEasing,
                        )
                    ) { it / 24 }
                    )
        },
        label = "ArtSwapContent",
        content = { state -> content(state) },
    )
}
