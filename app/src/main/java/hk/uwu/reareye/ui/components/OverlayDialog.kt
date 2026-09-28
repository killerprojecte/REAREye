package hk.uwu.reareye.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun OverlayDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(),
    backgroundColor: Color = DialogDefaults.backgroundColor(),
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    content: @Composable () -> Unit,
) {
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val internalVisible = remember { mutableStateOf(false) }
    val animationProgress = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    val dimProgress = remember { Animatable(0f) }

    if (!show && !internalVisible.value) return

    Dialog(
        onDismissRequest = { currentOnDismissRequest?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = currentOnDismissRequest != null,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val windowProvider = LocalView.current.parent as? DialogWindowProvider
        DisposableEffect(windowProvider) {
            val window = windowProvider?.window
            window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            onDispose {}
        }

        val density = LocalDensity.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val imeInsets = WindowInsets.ime
        val windowInfo = LocalWindowInfo.current
        val windowWidth = windowInfo.containerDpSize.width
        val windowHeight = windowInfo.containerDpSize.height
        val windowHeightPx = with(density) { windowHeight.toPx() }
        val isLargeScreen = windowHeight >= 480.dp && windowWidth >= 840.dp
        val safeTopInset = maxOf(
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            WindowInsets.captionBar.asPaddingValues().calculateTopPadding(),
            WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
        )
        val imeBottomInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val shape =
            androidx.compose.foundation.shape.RoundedCornerShape(if (isLargeScreen) 28.dp else 32.dp)

        LaunchedEffect(show, isLargeScreen) {
            if (show) {
                internalVisible.value = true
                launch {
                    dimProgress.animateTo(1f, tween(300, easing = DecelerateEasing(1.5f)))
                }
                animationProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = if (isLargeScreen) {
                        folmeSpring(damping = 0.9f, response = 0.3f)
                    } else {
                        spring(
                            dampingRatio = 0.88f,
                            stiffness = 450f,
                            visibilityThreshold = 0.0001f
                        )
                    },
                )
            } else {
                if (!internalVisible.value) return@LaunchedEffect
                if (imeInsets.getBottom(density) > 0) {
                    keyboardController?.hide()
                }
                launch {
                    dimProgress.animateTo(0f, tween(250, easing = DecelerateEasing(1.5f)))
                }
                animationProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 260, easing = DecelerateEasing(1.5f)),
                )
                dimProgress.stop()
                withFrameNanos { }
                internalVisible.value = false
                currentOnDismissFinished?.invoke()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (enableWindowDim) {
                val baseColor = MiuixTheme.colorScheme.windowDimming
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(baseColor.copy(alpha = baseColor.alpha * dimProgress.value))
                        },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentOnDismissRequest) {
                        detectTapGestures { currentOnDismissRequest?.invoke() }
                    }
                    .then(
                        if (defaultWindowInsetsPadding) {
                            Modifier.padding(top = safeTopInset, bottom = imeBottomInset)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = outsideMargin.width, vertical = outsideMargin.height),
            ) {
                Column(
                    modifier = modifier
                        .align(if (isLargeScreen) Alignment.Center else Alignment.BottomCenter)
                        .graphicsLayer {
                            val progress = animationProgress.value
                            if (isLargeScreen) {
                                val scale = 0.8f + 0.2f * progress
                                scaleX = scale
                                scaleY = scale
                                alpha = progress
                            } else {
                                translationY = (1f - progress) * windowHeightPx
                                alpha = 1f
                            }
                        }
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .heightIn(max = if (isLargeScreen) windowHeight * (2f / 3f) else windowHeight * 0.86f)
                        .pointerInput(Unit) {
                            detectTapGestures { }
                        }
                        .clip(shape)
                        .background(backgroundColor)
                        .padding(horizontal = insideMargin.width, vertical = insideMargin.height),
                ) {
                    title?.let {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            text = it,
                            fontSize = MiuixTheme.textStyles.title4.fontSize,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = titleColor,
                        )
                    }
                    summary?.let {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            text = it,
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                            textAlign = TextAlign.Center,
                            color = summaryColor,
                        )
                    }
                    content()
                }
            }
        }
    }
}
