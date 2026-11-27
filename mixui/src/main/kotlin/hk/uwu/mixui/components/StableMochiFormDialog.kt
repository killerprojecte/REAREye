package hk.uwu.mixui.components

import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout
import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.MochiDialogDefaults
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.shape.MochiConcentricContainer
import ui.feice.mochi.effect.content.mochiAnimateHeight
import ui.feice.mochi.effect.shadow.drawMochiShadow

/**
 * mixui's editable-form compatibility host, not MochiDialog. Only public Mochi
 * rendering APIs are used. The surface deliberately has no pointer-driven card
 * transform: native text selection, field gestures and cursor blinking are untouched.
 * The supplied scene is recorded separately; this overlay never records itself.
 */
@Composable
internal fun StableMochiFormDialog(
    show: Boolean,
    title: String,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    var retained by remember { mutableStateOf(show) }
    val presence = remember { Animatable(0f) }
    val hostFocus = LocalFocusManager.current
    LaunchedEffect(show) {
        if (show) {
            hostFocus.clearFocus(force = true)
            retained = true
            presence.animateTo(1f, MochiMotions.responseSpring(0.36f, 0.78f))
        } else if (retained) {
            presence.animateTo(0f, MochiMotions.responseSpring(0.22f, 1f))
            retained = false
            currentOnDismissFinished?.invoke()
        }
    }
    if (!retained) return
    // Only the public source participates. GlassFormDialog explicitly provides
    // its scene here; plain FormDialog must not acquire glass from mixui locals.
    val backdrop = LocalMochiBackdrop.current
    val currentShow by rememberUpdatedState(show)
    StableFormWindowLayer(exiting = { !currentShow }) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val dismiss by rememberUpdatedState(onDismissRequest)
        val back = remember { Animatable(0f) }
        var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(show) {
            if (show) back.snapTo(0f)
            if (!show) {
                focus.clearFocus(force = true)
                keyboard?.hide()
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                focus.clearFocus(force = true)
                keyboard?.hide()
            }
        }
        PredictiveBackHandler(enabled = show) { events ->
            try {
                events.collect {
                    edge = it.swipeEdge
                    back.snapTo(it.progress)
                }
                if (dismiss != null) dismiss?.invoke()
                else back.animateTo(0f, MochiMotions.responseSpring(0.3f, 0.8f))
            } catch (cancelled: CancellationException) {
                scope.launch { back.animateTo(0f, MochiMotions.responseSpring(0.3f, 0.8f)) }
                throw cancelled
            }
        }
        val colors = MochiDialogDefaults.colors()
        Box(Modifier.fillMaxSize().semantics { dialog() }) {
            // A sibling scrim receives only outside taps. Child native controls
            // are not observed or intercepted by a card-wide gesture recognizer.
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = presence.value.coerceIn(0f, 1f) * (1f - 0.4f * back.value)
            }.background(colors.scrim).pointerInput(show) {
                detectTapGestures { if (show) dismiss?.invoke() }
            })
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                val corner = MochiDialogDefaults.ButtonHeight / 2 + MochiDialogDefaults.ButtonInset
                val shape = remember(corner) { MochiRoundedRectangle(corner) }
                val dark = MochiTheme.colorScheme.isDark
                val shadow = MochiTheme.shadows.floating
                val shadowColor = MochiTheme.colorScheme.shadow
                val cardModifier = modifier.padding(horizontal = 36.dp)
                        .widthIn(max = MochiDialogDefaults.MaxWidth).fillMaxWidth()
                        .graphicsLayer {
                            val p = presence.value
                            val entry = if (show) 1.12f - 0.12f * p else 0.94f + 0.06f * p
                            scaleX = entry * (1f - 0.1f * back.value)
                            scaleY = scaleX
                            alpha = p.coerceIn(0f, 1f)
                            translationX = (if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1) * 12.dp.toPx() * back.value
                        }
                        // Consume blank card taps without consuming field input.
                        .pointerInput(Unit) { detectTapGestures {} }
                val cardContent: @Composable () -> Unit = {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 34.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (title.isNotEmpty()) ui.feice.mochi.basic.Text(
                            title, style = MochiTheme.textStyles.navTitle, color = colors.title,
                        )
                        // Validation and submit/cancel belong entirely to the caller.
                        content()
                    }
                }
                val material = MochiTheme.materials.alertGlass
                MochiConcentricContainer(corner, MochiDialogDefaults.ButtonInset) {
                    if (material != null && backdrop != null) {
                        // The public surface draws its glass to the animated size.
                        MochiGlassSurface(cardModifier.mochiAnimateHeight(shape),
                            material = material, backdrop = backdrop, cornerRadius = corner,
                            contentAlignment = Alignment.TopStart) {
                            cardContent()
                        }
                    } else {
                        Box(cardModifier.drawBehind {
                            drawMochiShadow(size.toRect(), corner.toPx(),
                                shadow.resolve(this, dark, presence.value.coerceIn(0f, 1f)), shadowColor)
                        }.background(colors.container, shape).mochiAnimateHeight(shape)) {
                            cardContent()
                        }
                    }
                }
            }
        }
    }
}

/** A retained child of the existing window, independent of a callsite's constraints. */
@Composable
private fun StableFormWindowLayer(exiting: () -> Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    val parentComposition = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentExiting by rememberUpdatedState(exiting)
    val layer = remember(view, parentComposition) {
        ComposeView(view.context).apply {
            setParentCompositionContext(parentComposition)
            setContent { currentContent() }
        }
    }
    val touchGate = remember(layer) {
        FormTouchGate(view.context) { currentExiting() }.apply {
            addView(layer, ViewGroup.LayoutParams(-1, -1))
        }
    }
    DisposableEffect(touchGate) {
        val root = view.rootView as? ViewGroup
            ?: error("Editable forms require an attached Android window")
        root.addView(touchGate, ViewGroup.LayoutParams(-1, -1))
        onDispose {
            // Keep descendants blocked until the posted focus-transfer work has
            // drained. Shared bookkeeping avoids two closing forms restoring a
            // previously blocked state permanently.
            FormFocusRemovalGuard.remove(root) {
                layer.clearFocus()
                layer.disposeComposition()
                root.removeView(touchGate)
            }
        }
    }
}


private object FormFocusRemovalGuard {
    private data class Pending(val original: Int, var count: Int)
    private val pending = java.util.WeakHashMap<ViewGroup, Pending>()

    fun remove(root: ViewGroup, removeView: () -> Unit) {
        val state = pending.getOrPut(root) { Pending(root.descendantFocusability, 0) }
        state.count++
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        try {
            removeView()
        } finally {
            root.post {
                if (--state.count == 0) {
                    pending.remove(root)
                    // Do not override an explicit intervening focus-policy change.
                    if (root.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
                        root.descendantFocusability = state.original
                    }
                }
            }
        }
    }
}


/** Decide once per new gesture. Never leak an exiting form's taps to its
 * controls or to the page; an already active gesture is allowed to finish. */
private class FormTouchGate(context: Context, private val exiting: () -> Boolean) : FrameLayout(context) {
    private var swallowGesture = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) swallowGesture = exiting()
        return if (swallowGesture) true else super.dispatchTouchEvent(event)
    }
}
