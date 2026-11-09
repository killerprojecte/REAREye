package hk.uwu.mixui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import ui.feice.mochi.anim.MochiMotions
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.dismiss
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import kotlinx.coroutines.CancellationException
import ui.feice.mochi.effect.glass.*
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import kotlin.math.roundToInt

/** Large preserves the existing viewport sheet; Content wraps short controls. */
enum class GlassBottomSheetSize { Large, Content }

/**
 * Opt-in glass sheet host with separate page/scrim and sheet-material sources.
 * Change [sessionKey] when an owner closes and reopens without an observable
 * show=false composition; it identifies the new modal interaction session.
 * Content-sized Mochi sheets require GlassBottomSheetScene; they never silently
 * expand into the legacy Large-only fallback.
 */
@Composable
fun GlassBottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
    allowDismiss: Boolean = true,
    isContentScrolled: Boolean = false,
    onDismissFinished: () -> Unit = {},
    horizontalPadding: Dp = 24.dp,
    sessionKey: Any? = Unit,
    size: GlassBottomSheetSize = GlassBottomSheetSize.Large,
    enableNestedScroll: Boolean = true,
    content: @Composable () -> Unit,
) {
    key(sessionKey) {
        GlassBottomSheetSession(show, onDismissRequest, title, startAction, endAction,
            allowDismiss, isContentScrolled, onDismissFinished, horizontalPadding, size, enableNestedScroll, content)
    }
}

@Composable
private fun GlassBottomSheetSession(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String?,
    startAction: (@Composable () -> Unit)?,
    endAction: (@Composable () -> Unit)?,
    allowDismiss: Boolean,
    isContentScrolled: Boolean,
    onDismissFinished: () -> Unit,
    horizontalPadding: Dp,
    size: GlassBottomSheetSize,
    enableNestedScroll: Boolean,
    content: @Composable () -> Unit,
) {
    val scene = LocalGlassSheetScene.current
    if (LocalMixUiBackend.current == MixUiBackend.Mochi && scene == null && size == GlassBottomSheetSize.Content) {
        check(!show) { "Content-sized Mochi GlassBottomSheet requires GlassBottomSheetScene" }
        return
    }
    if (LocalMixUiBackend.current != MixUiBackend.Mochi || scene == null) {
        BottomSheet(show, onDismissRequest, title, startAction, endAction, allowDismiss,
            isContentScrolled, onDismissFinished, horizontalPadding, enableNestedScroll, content)
        return
    }
    var retained by remember { mutableStateOf(show) }
    LaunchedEffect(show) { if (show) retained = true }
    if (!retained) return
    val token = remember { Any() }
    val locals = currentCompositionLocalContext
    val currentShow by rememberUpdatedState(show)
    val currentFinished by rememberUpdatedState(onDismissFinished)
    val entry by rememberUpdatedState<@Composable () -> Unit>({
        CompositionLocalProvider(locals) {
            MochiGlassSheetHost(scene, token, show, allowDismiss, onDismissRequest, {
                if (!currentShow) {
                    retained = false
                    currentFinished()
                }
            }, title, startAction, endAction, horizontalPadding, size, enableNestedScroll, content)
        }
    })
    DisposableEffect(scene, token) {
        scene.entries[token] = { entry() }
        onDispose {
            scene.entries.remove(token)
            scene.glassSheetScrims.remove(token)
        }
    }
}

/** Deduplicates a gesture/back/scrim request until the owner closes or reopens. */
internal class GlassSheetDismissGate {
    private var requested = false
    fun reset() { requested = false }
    fun request(show: Boolean, allowed: Boolean): Boolean {
        if (!show || !allowed || requested) return false
        requested = true
        return true
    }
}

@Composable
private fun MochiGlassSheetHost(
    scene: GlassSheetScene,
    token: Any,
    show: Boolean,
    allowDismiss: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    title: String?,
    startAction: (@Composable () -> Unit)?,
    endAction: (@Composable () -> Unit)?,
    horizontalPadding: Dp,
    size: GlassBottomSheetSize,
    enableNestedScroll: Boolean,
    content: @Composable () -> Unit,
) {
    val currentShow by rememberUpdatedState(show)
    val allowed by rememberUpdatedState(allowDismiss)
    val requestCallback by rememberUpdatedState(onDismissRequest)
    val finishedCallback by rememberUpdatedState(onDismissFinished)
    val gate = remember { GlassSheetDismissGate() }
    val request = remember {
        { if (gate.request(currentShow, allowed)) requestCallback() }
    }
    BoxWithConstraints(Modifier.fillMaxSize().recalculateWindowInsets()
        .testTag("mochi-glass-sheet-host")) {
        val density = LocalDensity.current
        val viewportHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        var measuredHeight by remember { mutableIntStateOf(0) }
        val compact = size == GlassBottomSheetSize.Content
        val hidden = if (compact && measuredHeight > 0) measuredHeight.toFloat() else viewportHeight
        var offset by remember { mutableFloatStateOf(hidden) }
        var dragging by remember { mutableStateOf(false) }
        var settle by remember { mutableIntStateOf(0) }
        var finished by remember { mutableStateOf(false) }
        val travel by rememberUpdatedState(hidden)
        LaunchedEffect(show) { if (show) { gate.reset(); finished = false } }
        LaunchedEffect(show, dragging, settle, hidden) {
            if (!dragging) {
                animate(offset, if (show) 0f else hidden, animationSpec = MochiMotions.responseSpring(if (show) 0.42f else 0.34f, if (show) 0.88f else 1f)) { value, _ -> offset = value }
                if (!show && !finished) { finished = true; finishedCallback() }
            }
        }
        // B is recorded in the page sibling, not in this overlay. It has the same
        // full-window coordinates and follows drag, close and predictive progress.
        val presence = (1f - offset / hidden).coerceIn(0f, 1f)
        val scrimAlpha = MochiDefaults.scrim().alpha
        SideEffect { scene.glassSheetScrims[token] = scrimAlpha * presence }
        fun drag(delta: Float): Float {
            if (!allowed || !currentShow) return 0f
            dragging = true
            val previous = offset
            offset = (offset + delta).coerceIn(0f, travel)
            return offset - previous
        }
        fun release(velocity: Float) {
            if (allowed && (offset > travel * 0.22f || velocity > 1200f)) request()
            dragging = false
            settle++
        }
        val nested = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y < 0f && offset > 0f)
                        Offset(0f, drag(available.y)) else Offset.Zero
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0f)
                        Offset(0f, drag(available.y)) else Offset.Zero
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!dragging) return Velocity.Zero
                    release(available.y)
                    return Velocity(0f, available.y)
                }
            }
        }
        // Consume Back even when saving; allowDismiss=false is a veto, not a
        // reverse animation after the underlying page has already received Back.
        PredictiveBackHandler(enabled = true) { events ->
            val startingOffset = offset
            try {
                events.collect { event ->
                    if (currentShow && allowed) { dragging = true; offset = travel * event.progress * 0.35f }
                }
                if (currentShow && allowed) request()
            } catch (_: CancellationException) {
                offset = startingOffset
            } finally {
                dragging = false
                settle++
            }
        }
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = request,
        ))
        val surface = rememberMochiBackdrop(MochiDefaults.sheetBackground())
        val sheetFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { sheetFocus.requestFocus() }
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = 12.dp)) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (compact) Modifier.wrapContentHeight() else Modifier.fillMaxHeight())
                .onSizeChanged { measuredHeight = it.height }
                .offset { IntOffset(0, offset.roundToInt()) }.clip(shape)
                .testTag("mochi-glass-sheet")
                .semantics {
                    paneTitle = title.orEmpty()
                    if (allowDismiss) dismiss { request(); true }
                }
                .focusRequester(sheetFocus)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusable()
                .pointerInput(Unit) { detectTapGestures { } }) {
                // S records only the final native sheet material. Foreground controls
                // are a sibling: no self-sampling, original-page shortcut or text echo.
                Box(Modifier.matchParentSize().mochiBackdropSource(surface)) {
                    if (MochiTheme.materials.sheetGlass != null) {
                        MochiGlassSurface(Modifier.fillMaxSize(), material = MochiTheme.materials.sheetGlass,
                            backdrop = scene.mochiBackdrop, cornerRadius = 28.dp) {}
                    } else Box(Modifier.fillMaxSize().background(MochiDefaults.sheetBackground()))
                }
                CompositionLocalProvider(
                    LocalMochiBackdrop provides surface,
                    LocalGlassTabsMochiBackdrop provides surface,
                    LocalGlassTabsSurfaceAlpha provides 1f,
                    // A nested app sheet must establish its own source boundary;
                    // do not let it sample the original page through this scene.
                    LocalGlassSheetScene provides null,
                ) {
                    Column(Modifier.fillMaxWidth()
                        .then(if (compact) Modifier else Modifier.fillMaxHeight())
                        .then(if (enableNestedScroll) Modifier.nestedScroll(nested) else Modifier).imePadding()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = horizontalPadding)) {
                        Column(Modifier.fillMaxWidth().draggable(
                            state = rememberDraggableState { drag(it) }, orientation = Orientation.Vertical,
                            enabled = show && allowDismiss, onDragStarted = { dragging = true },
                            onDragStopped = { release(it) },
                        )) {
                            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 8.dp)
                                .size(36.dp, 4.dp).background(MixTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(2.dp)))
                            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Box(Modifier.align(Alignment.CenterStart)) { startAction?.invoke() }
                                title?.let { Text(it, Modifier.align(Alignment.Center), style = MixTheme.textStyles.title3) }
                                Box(Modifier.align(Alignment.CenterEnd)) { endAction?.invoke() }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        Box(if (compact) Modifier else Modifier.weight(1f)) { content() }
                    }
                }
            }
        }
    }
}
