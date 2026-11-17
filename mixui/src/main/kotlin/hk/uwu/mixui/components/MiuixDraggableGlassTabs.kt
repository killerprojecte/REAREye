// Copyright 2026, compose-miuix-ui contributors
// Copyright 2026, REAREye contributors
// SPDX-License-Identifier: Apache-2.0
//
// Rendering adapted from GlassSegmentedTabRow at compose-miuix-ui/miuix 3a980f7c:
// https://github.com/compose-miuix-ui/miuix/blob/3a980f7c/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassTabRow.kt
// The upstream Apache-2.0 license applies to the reused rendering implementation.
// Local additions only preview pointer movement and commit selection on release.

package hk.uwu.mixui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRowDefaults
import top.yukonga.miuix.kmp.glass.glass
import top.yukonga.miuix.kmp.glass.glassShadow
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private data class MiuixTabDragInputs(
    val tabs: List<String>,
    val index: Int,
    val geometry: MiuixGlassTabGeometry,
    val rtl: Boolean,
)

/**
 * Minimal fixed-version MIUIX joined-track adapter. All optics and motion recipes come from
 * MIUIX, not Mochi. The public host backdrop records the body only, so glass() performs one
 * native blur; it does not sample or blur the already-composited TopBar underlay a second time.
 */
@Composable
internal fun MiuixDraggableGlassTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    surfaceAlpha: Float = 1f,
) {
    if (tabs.isEmpty()) return
    val index = selectedIndex.coerceIn(tabs.indices)
    val runtimeShaderSupported = isRuntimeShaderSupported()
    val ramp = if (runtimeShaderSupported) surfaceAlpha.coerceIn(0f, 1f) else 0f
    val height = GlassSegmentedTabRowDefaults.Height
    val trackShape = GlassSegmentedTabRowDefaults.shape(height)
    val innerHeight = height - GlassSegmentedTabRowDefaults.TrackPadding * 2
    val indicatorShape = GlassSegmentedTabRowDefaults.shape(innerHeight)
    val trackColor = GlassSegmentedTabRowDefaults.restingTrackColor()
    val indicatorColor = GlassSegmentedTabRowDefaults.indicatorColor()
    val material = GlassSegmentedTabRowDefaults.material()
    val stroke = GlassSegmentedTabRowDefaults.stroke()
    val contentColor = MiuixTheme.colorScheme.onSurface
    var trackWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gap = with(density) { GlassSegmentedTabRowDefaults.TabGap.toPx() }
    val geometry = MiuixGlassTabGeometry(tabs.size, trackWidth.toFloat(), gap)
    val targetLeft = geometry.offsetFor(index)
    val offset = remember { Animatable(0f) }
    val span = remember { Animatable(0f) }
    var preview by remember { mutableStateOf<Float?>(null) }
    var settleFrom by remember { mutableStateOf<Float?>(null) }
    var settleVersion by remember { mutableIntStateOf(0) }
    val onSelectNow by rememberUpdatedState(onSelect)
    val dragInputs = MiuixTabDragInputs(tabs, index, geometry, rtl)
    val dragInputsNow by rememberUpdatedState(dragInputs)

    LaunchedEffect(dragInputs) {
        // Do not restart the pointer recognizer during a held gesture: Compose may hand the
        // restarted recognizer that still-held pointer again. Invalidate its snapshot instead,
        // immediately restore the indicator, and let it consume its own eventual release.
        if (preview != null) {
            settleFrom = preview
            preview = null
            settleVersion++
        }
    }

    LaunchedEffect(targetLeft, geometry.slotWidth, preview != null, settleVersion) {
        if (geometry.slotWidth <= 0f) return@LaunchedEffect
        if (preview != null) {
            offset.stop()
            span.stop()
            return@LaunchedEffect
        }
        val releasedOffset = settleFrom
        if (releasedOffset != null) {
            // Keep the released position drawable across the pointer -> effect handoff.
            // snapTo suspends; clearing first exposes the previous selected slot for a frame.
            offset.snapTo(releasedOffset.coerceIn(0f, geometry.maxOffset))
            if (settleFrom == releasedOffset) settleFrom = null
        }
        if (span.value <= 0f) {
            offset.snapTo(targetLeft)
            span.snapTo(geometry.slotWidth)
            return@LaunchedEffect
        }
        launch { offset.animateTo(targetLeft, GlassMotion.default()) }
        launch { span.animateTo(geometry.slotWidth, GlassMotion.default()) }
    }

    val drag = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val inputs = dragInputsNow
            if (!inputs.geometry.canDrag) return@awaitEachGesture
            val session = MiuixGlassTabDrag(
                geometry = inputs.geometry,
                selectedIndex = inputs.index,
                initialOffset = preview ?: settleFrom ?: offset.value,
                touchSlop = viewConfiguration.touchSlop,
                rtl = inputs.rtl,
            )
            var owned = false
            var invalidated = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (dragInputsNow != inputs) invalidated = true
                    if (invalidated) {
                        session.cancel()
                        // A held drag cannot turn into a click/new selection when external
                        // state changes. Keep ownership through UP, without submitting it.
                        if (owned) change.consume()
                        if (!change.pressed || !owned) break
                        continue
                    }
                    // Another recognizer, a second finger, or system cancellation cannot commit.
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    if (!change.pressed) {
                        if (session.isDragging) {
                            change.consume()
                            session.finish()?.let { onSelectNow(it) }
                        }
                        break
                    }
                    val delta = change.positionChange()
                    session.move(delta.x, delta.y)
                    if (session.isRejected) break
                    if (session.isDragging) {
                        owned = true
                        preview = session.previewOffset
                        // Only a horizontally dominant move beyond touchSlop owns this gesture.
                        // Consuming here also cancels the descendant selectable's pending click.
                        change.consume()
                    }
                }
            } finally {
                // System cancellation and detach also restore without touching business state.
                if (preview != null) {
                    settleFrom = preview
                    preview = null
                    settleVersion++
                }
            }
        }
    }

    val materialSurface = if (!runtimeShaderSupported) Modifier else Modifier.glass(
        backdrop = backdrop,
        shape = trackShape,
        style = GlassDefaults.Style,
        alpha = ramp,
        material = material,
        stroke = stroke,
        enabled = ramp > 0f,
        shading = false,
    )
    Box(
        modifier = modifier
            .height(height)
            // Match upstream order: shadow lives outside the track clip and follows its ramp.
            .glassShadow(trackShape, GlassShadows.Float, if (runtimeShaderSupported) ramp else 1f)
            .clip(trackShape)
            .then(materialSurface)
            .background(trackColor.copy(alpha = trackColor.alpha * (1f - ramp)))
            .padding(GlassSegmentedTabRowDefaults.TrackPadding)
            .onSizeChanged { trackWidth = it.width }
            .then(drag),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val width = span.value.roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative(IntOffset((preview ?: settleFrom ?: offset.value).roundToInt(), 0))
                    }
                }
                .clip(indicatorShape)
                .background(indicatorColor)
                .testTag("glass-tabs-miuix-indicator"),
        )
        Row(
            modifier = Modifier.fillMaxHeight().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(GlassSegmentedTabRowDefaults.TabGap),
        ) {
            tabs.forEachIndexed { position, label ->
                key(position, label) {
                    val selected = position == index
                    val content by animateColorAsState(
                        targetValue = contentColor,
                        animationSpec = GlassMotion.navContent(),
                        label = "glassSegmentedTabContent",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(indicatorShape)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = null,
                                indication = null,
                            ) { onSelectNow(position) }
                            .padding(horizontal = GlassSegmentedTabRowDefaults.TabPaddingHorizontal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = if (selected) MiuixTheme.textStyles.subtitle else MiuixTheme.textStyles.body2,
                            color = content,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
