package hk.uwu.reareye.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal class FeatureGuideAnchors {
    val coordinates = mutableStateMapOf<String, LayoutCoordinates>()
}

internal val LocalFeatureGuideAnchors = staticCompositionLocalOf<FeatureGuideAnchors?> { null }

/** Register the actual laid-out control, never an estimated screen rectangle. */
internal fun Modifier.featureGuideAnchor(key: String): Modifier = composed {
    val anchors = LocalFeatureGuideAnchors.current
    if (anchors == null) return@composed this
    var ownedCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(anchors, key, ownedCoordinates) {
        val coordinates = ownedCoordinates
        if (coordinates != null) anchors.coordinates[key] = coordinates
        onDispose {
            if (anchors.coordinates[key] === coordinates) anchors.coordinates.remove(key)
        }
    }
    onGloballyPositioned { ownedCoordinates = it }
}

internal data class FeatureGuideStep(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val anchorKey: String,
    val dashboardTab: Int? = null,
    val action: FeatureGuideAction? = null,
)

internal val featureGuideSteps = listOf(
    FeatureGuideStep(R.string.guide_home_title, R.string.guide_home_summary, "home_status"),
    FeatureGuideStep(R.string.guide_nav_title, R.string.guide_nav_summary, "bottom_navigation"),
    FeatureGuideStep(R.string.guide_config_title, R.string.guide_config_summary, "config_tabs", 0),
    FeatureGuideStep(
        R.string.guide_cards_title,
        R.string.guide_demo_open_card,
        "demo_card",
        0,
        FeatureGuideAction.OPEN_CARD
    ),
    FeatureGuideStep(
        R.string.rear_widget_edit_card,
        R.string.guide_demo_edit_card,
        "demo_card_editor",
        0,
        FeatureGuideAction.SAVE_CARD
    ),
    FeatureGuideStep(
        R.string.guide_demo_toggle_title,
        R.string.guide_demo_toggle_card,
        "demo_card_toggle",
        0,
        FeatureGuideAction.TOGGLE_CARD
    ),
    FeatureGuideStep(
        R.string.guide_components_title,
        R.string.guide_demo_open_component,
        "demo_component",
        1,
        FeatureGuideAction.OPEN_COMPONENT
    ),
    FeatureGuideStep(
        R.string.rear_widget_edit_business,
        R.string.guide_demo_edit_component,
        "demo_component_editor",
        1,
        FeatureGuideAction.SAVE_COMPONENT
    ),
    FeatureGuideStep(
        R.string.guide_demo_component_result_title,
        R.string.guide_demo_component_result,
        "demo_component_preview",
        1
    ),
    FeatureGuideStep(
        R.string.guide_wallpaper_title,
        R.string.guide_demo_apply_wallpaper,
        "demo_wallpaper_apply",
        2,
        FeatureGuideAction.APPLY_WALLPAPER
    ),
    FeatureGuideStep(
        R.string.guide_demo_wallpaper_result_title,
        R.string.guide_demo_wallpaper_result,
        "demo_wallpaper_preview",
        2
    ),
    FeatureGuideStep(R.string.guide_more_title, R.string.guide_more_summary, "config_header_3", 3),
)

@Composable
internal fun FeatureGuideOverlay(
    stepIndex: Int,
    anchors: FeatureGuideAnchors,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val step = featureGuideSteps[stepIndex.coerceIn(featureGuideSteps.indices)]
    val density = LocalDensity.current
    var focus by remember(stepIndex) { mutableStateOf<Rect?>(null) }
    var overlayCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Let page/tab entry animations finish, then follow actual bounds (including transforms).
    // Keeping the overlay mounted also blocks taps while the destination is being laid out.
    LaunchedEffect(stepIndex, anchors) {
        delay(450)
        while (true) {
            withFrameNanos {
                val target = anchors.coordinates[step.anchorKey]
                val overlay = overlayCoordinates
                focus = if (target?.isAttached == true && overlay?.isAttached == true) {
                    target.boundsInRoot().translate(-overlay.positionInRoot())
                        .intersect(
                            Rect(
                                Offset.Zero, androidx.compose.ui.geometry.Size(
                                    overlay.size.width.toFloat(), overlay.size.height.toFloat(),
                                )
                            )
                        ).takeIf { it.width > 0f && it.height > 0f }
                } else null
            }
        }
    }
    BackHandler { if (stepIndex > 0) onPrevious() else onSkip() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayCoordinates = it
            }) {
        val availableAbove = focus?.top ?: 0f
        val availableBelow = constraints.maxHeight - (focus?.bottom ?: 0f)
        val anchorNearBottom = availableAbove > availableBelow
        val panelMaxHeight = with(density) {
            (if (anchorNearBottom) availableAbove else availableBelow).coerceAtLeast(0f).toDp()
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                focus?.let { addRoundRect(RoundRect(it, 16.dp.toPx(), 16.dp.toPx())) }
            }
            drawPath(mask, Color.Black.copy(alpha = 0.6f))
        }

        // Only the highlighted practice control receives input. Other pages and real
        // configuration stay blocked; informational steps block the whole surface.
        val interactiveFocus = focus.takeIf { step.action != null }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val blockedRegions = interactiveFocus?.let {
            listOf(
                Rect(0f, 0f, width, it.top),
                Rect(0f, it.bottom, width, height),
                Rect(0f, it.top, it.left, it.bottom),
                Rect(it.right, it.top, width, it.bottom),
            )
        } ?: listOf(Rect(0f, 0f, width, height))
        blockedRegions.filter { it.width > 0f && it.height > 0f }.forEach { region ->
            Box(
                Modifier
                    .offset { IntOffset(region.left.roundToInt(), region.top.roundToInt()) }
                    .size(
                        with(density) { region.width.toDp() },
                        with(density) { region.height.toDp() })
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    })
        }

        Column(
            modifier = Modifier
                .align(if (anchorNearBottom) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth(0.92f)
                .heightIn(max = panelMaxHeight)
                .safeDrawingPadding()
                .padding(
                    top = if (anchorNearBottom) 24.dp else 0.dp,
                    bottom = if (anchorNearBottom) 0.dp else 24.dp,
                )
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.guide_progress,
                    stepIndex + 1,
                    featureGuideSteps.size
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = stringResource(step.titleRes),
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (step.dashboardTab != null) {
                Text(
                    stringResource(R.string.guide_demo_notice),
                    style = MiuixTheme.textStyles.body2
                )
            }
            Text(
                text = stringResource(step.summaryRes),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (stepIndex > 0) {
                Button(onClick = onPrevious, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.oobe_previous))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(stringResource(R.string.guide_skip))
                }
                Button(
                    onClick = onNext,
                    enabled = focus != null && step.action == null,
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            when {
                                step.action != null -> R.string.guide_demo_try_action
                                stepIndex == featureGuideSteps.lastIndex -> R.string.guide_done
                                else -> R.string.guide_next
                            }
                        )
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
