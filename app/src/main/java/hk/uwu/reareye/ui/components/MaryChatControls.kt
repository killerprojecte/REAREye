package hk.uwu.reareye.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningPolicy
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.voice.MaryVoicePhase
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Unknown counters stay unknown; a zero cache count differs from an absent one. */
internal data class MaryUsageSummary(
    val usedTokens: Int?,
    val windowTokens: Int,
    val usage: ChatEvent.Usage?
) {
    val contextRatio: Double?
        get() = usedTokens?.takeIf { it >= 0 && windowTokens > 0 }
            ?.let { it.toDouble() / windowTokens }
    val cacheRatio: Double?
        get() {
            val input = usage?.inputTokens?.takeIf { it > 0 } ?: return null
            val cached = usage.cachedInputTokens?.takeIf { it in 0..input } ?: return null
            return cached.toDouble() / input
        }
}

@Composable
internal fun ContextUsageButton(summary: MaryUsageSummary) {
    val scope = rememberCoroutineScope()
    val tooltip = rememberTooltipState(isPersistent = true)
    val unknown = stringResource(R.string.mary_usage_unknown)
    val percent = summary.contextRatio?.let { "${(it * 100).roundToInt()}%" } ?: "—"
    val action = stringResource(R.string.mary_chat_context_action, percent)
    val primary = MiuixTheme.colorScheme.primary
    val track = MiuixTheme.colorScheme.outline
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        state = tooltip,
        focusable = true,
        tooltip = {
            RichTooltip(title = {
                Text(
                    stringResource(R.string.mary_chat_context_title),
                    style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold
                )
            }) {
                Column(
                    Modifier.widthIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(
                        R.string.mary_chat_context_tokens,
                        summary.usedTokens?.toString() ?: unknown,
                        summary.windowTokens.takeIf { it > 0 }?.toString() ?: unknown
                    ), style = MiuixTheme.textStyles.body2
                    )
                    Text(
                        stringResource(
                            R.string.mary_chat_context_percent,
                            summary.contextRatio?.let { "${(it * 100).roundToInt()}%" } ?: unknown),
                        style = MiuixTheme.textStyles.body2)
                    Text(
                        stringResource(
                            R.string.mary_chat_cache_rate,
                        summary.cacheRatio?.let {
                            String.format(
                                Locale.getDefault(),
                                "%.1f%%",
                                it * 100
                            )
                        } ?: unknown),
                        style = MiuixTheme.textStyles.body2)
                    Text(
                        stringResource(R.string.mary_chat_context_note),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        },
    ) {
        IconButton(
            onClick = { if (tooltip.isVisible) tooltip.dismiss() else scope.launch { tooltip.show() } },
            modifier = Modifier
                .testTag("mary-context-usage")
                .semantics(mergeDescendants = true) {
                    contentDescription = action
                    summary.contextRatio?.let {
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(it.toFloat().coerceIn(0f, 1f), 0f..1f)
                    }
                },
        ) {
            Canvas(Modifier
                .size(20.dp)
                .testTag("mary-context-ring")) {
                val stroke = 2.dp.toPx()
                drawCircle(track, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                val sweep = contextProgressSweep(summary.contextRatio)
                if (sweep != 0f) drawArc(
                    primary, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(
                        size.width - stroke,
                        size.height - stroke
                    ),
                    style = Stroke(stroke, cap = StrokeCap.Butt)
                )
            }
        }
    }
}

/** A positive sweep fills clockwise from the Canvas arc's twelve-o'clock start (-90 degrees). */
internal fun contextProgressSweep(ratio: Double?): Float =
    if (ratio == null || !ratio.isFinite()) 0f else (ratio.coerceIn(0.0, 1.0) * 360).toFloat()

/** A round 270-degree dial with medium pointing straight up. */
internal fun reasoningNeedleAngle(effort: ReasoningEffort): Float = when (effort) {
    ReasoningEffort.NONE -> 135f
    ReasoningEffort.MINIMAL -> 165f
    ReasoningEffort.LOW -> 210f
    ReasoningEffort.MEDIUM -> 270f
    ReasoningEffort.HIGH -> 315f
    ReasoningEffort.XHIGH -> 360f
    ReasoningEffort.MAX -> 405f
}

@Composable
internal fun reasoningEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.NONE -> R.string.mary_chat_reasoning_none
        ReasoningEffort.MINIMAL -> R.string.mary_chat_reasoning_minimal
        ReasoningEffort.LOW -> R.string.mary_chat_reasoning_low
        ReasoningEffort.MEDIUM -> R.string.mary_chat_reasoning_medium
        ReasoningEffort.HIGH -> R.string.mary_chat_reasoning_high
        ReasoningEffort.XHIGH -> R.string.mary_chat_reasoning_xhigh
        ReasoningEffort.MAX -> R.string.mary_chat_reasoning_max
    }
)

@Composable
private fun ReasoningGauge(effort: ReasoningEffort?, tint: Color) {
    val angle by animateFloatAsState(
        reasoningNeedleAngle(effort ?: ReasoningEffort.MEDIUM),
        tween(180),
        label = "reasoningNeedle"
    )
    val track = MiuixTheme.colorScheme.outline
    val needle = if (effort != null) MiuixTheme.colorScheme.onSurfaceContainer else track
    val background = MiuixTheme.colorScheme.surfaceContainer
    Canvas(Modifier
        .size(26.dp)
        .testTag("mary-reasoning-dial")) {
        val stroke = 2.dp.toPx()
        val radius = (size.minDimension - stroke) / 2
        val bounds = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(radius * 2, radius * 2)
        drawArc(
            track, 135f, 270f, false, topLeft = bounds, size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        if (effort != null && effort != ReasoningEffort.NONE) {
            drawArc(
                tint,
                135f,
                (angle - 135f).coerceIn(0f, 270f),
                false,
                topLeft = bounds,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        val radians = Math.toRadians(angle.toDouble())
        val tip = center + Offset(
            (cos(radians) * radius * 0.78).toFloat(),
            (sin(radians) * radius * 0.78).toFloat()
        )
        drawLine(needle, center, tip, 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(background, 3.dp.toPx(), center)
        drawCircle(needle, 2.5.dp.toPx(), center, style = Stroke(1.8.dp.toPx()))
    }
}

@Composable
internal fun ReasoningStrengthButton(
    provider: ProviderConfig,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (ReasoningEffort) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val support = ReasoningPolicy.support(provider)
    val effective = support.effectiveEffort(provider.reasoningEffort)
    val available = support.levels.size > 1
    val label = effective?.let { reasoningEffortLabel(it) } ?: "—"
    val description = if (available) stringResource(R.string.mary_chat_reasoning_action, label)
    else stringResource(R.string.mary_chat_reasoning_unavailable)
    // A save can temporarily disable actions; it must not close/recreate the user's sheet.
    var expanded by remember(
        provider.model,
        provider.protocol,
        provider.baseUrl,
        support.levels
    ) { mutableStateOf(false) }
    val shown = expanded && available
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    fun setExpanded(value: Boolean) {
        expanded = value
        // Notify before focus/IME changes can collapse an otherwise empty composer.
        currentOnExpandedChange(value && available)
    }
    SideEffect { currentOnExpandedChange(shown) }
    DisposableEffect(Unit) { onDispose { currentOnExpandedChange(false) } }
    val tint =
        if (enabled && available) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(
            alpha = 0.5f
        )
    Box {
        IconButton(
            onClick = { setExpanded(!shown); if (!shown) keyboard?.hide() },
            enabled = enabled && available,
            cornerRadius = ButtonDefaults.CornerRadius,
            modifier = Modifier
                .size(44.dp)
                .testTag("mary-reasoning-strength")
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    stateDescription = label
                    if (!enabled || !available) disabled()
                },
        ) {
            ReasoningGauge(effective, tint)
        }
        ReasoningStrengthPicker(
            show = shown,
            levels = support.levels,
            effective = effective,
            enabled = enabled,
            onDismiss = { setExpanded(false) },
            onSelected = { effort ->
                if (shown && enabled && effort in support.levels) onSelected(effort)
            },
        )
    }
}

/** A completed gesture saves once; further gestures are allowed until the user dismisses. */
@Composable
private fun ReasoningStrengthPicker(
    show: Boolean,
    levels: List<ReasoningEffort>,
    effective: ReasoningEffort?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSelected: (ReasoningEffort) -> Unit,
) {
    if (levels.size < 2) return
    var preview by remember(show, levels) {
        mutableIntStateOf(levels.indexOf(effective).coerceAtLeast(0))
    }
    var dragging by remember(show, levels) { mutableStateOf(false) }
    var dragProgress by remember(show, levels) { mutableFloatStateOf(preview.toFloat()) }
    // The animation tracks drag samples with a zero-duration spec; the canvas uses the
    // raw sample while pressed, then animates only the final fractional-to-node snap.
    val animatedProgress by animateFloatAsState(
        targetValue = if (dragging) dragProgress else preview.toFloat(),
        animationSpec = tween(if (dragging) 0 else 200), label = "reasoningSliderProgress",
    )
    val displayProgress = if (dragging) dragProgress else animatedProgress
    LaunchedEffect(effective, show, levels) {
        if (!dragging) preview = levels.indexOf(effective).coerceAtLeast(0)
    }
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentShow by rememberUpdatedState(show)
    val currentEnabled by rememberUpdatedState(enabled)
    val lastIndex = levels.lastIndex
    val previewLabel = reasoningEffortLabel(levels[preview.coerceIn(0, lastIndex)])
    val title = stringResource(R.string.mary_chat_reasoning_title)
    val primary = MiuixTheme.colorScheme.primary
    val track = MiuixTheme.colorScheme.secondaryContainer
    val dot = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f)
    val thumb = MiuixTheme.colorScheme.onPrimary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val commit: (Int) -> Unit = { index ->
        if (currentShow && currentEnabled) currentOnSelected(levels[index.coerceIn(0, lastIndex)])
    }
    val currentCommit by rememberUpdatedState(commit)
    OverlayBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
        enableNestedScroll = false,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Independent centered lines stay readable on narrow screens and large fonts.
            Text(
                title, modifier = Modifier
                    .testTag("mary-reasoning-title")
                    .semantics { heading() },
                fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold
            )
            AnimatedContent(
                targetState = previewLabel,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 }) togetherWith
                            (fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 4 })
                },
                modifier = Modifier.testTag("mary-reasoning-level"),
                label = "reasoningLevel",
            ) { label ->
                Text(
                    label, color = primary, fontSize = 20.sp, lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("mary-reasoning-slider")
                    .semantics {
                        contentDescription = title
                        stateDescription = previewLabel
                        if (!enabled) disabled()
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            preview.toFloat(),
                            0f..lastIndex.toFloat(),
                            lastIndex - 1
                        )
                        setProgress { target ->
                            if (!currentShow || !currentEnabled || !target.isFinite()) false
                            else {
                                preview = target.roundToInt().coerceIn(0, lastIndex)
                                currentCommit(preview)
                                true
                            }
                        }
                    }
                    .pointerInput(levels, show, rtl, enabled) {
                        if (!show || !enabled) return@pointerInput
                        fun progressAt(x: Float): Float = reasoningSliderProgressAt(
                            x, size.width.toFloat(), 20.dp.toPx(), lastIndex, rtl,
                        )
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val previous = preview
                            // A tap must animate from the existing position, not first snap
                            // to the touch point on DOWN. Only a genuine drag uses raw samples.
                            down.consume()
                            var released = false
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed) break
                                    if (!dragging && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                                        dragging = true
                                    }
                                    if (dragging) {
                                        dragProgress = progressAt(change.position.x)
                                        preview = dragProgress.roundToInt()
                                    }
                                    released = !change.pressed
                                    if (released && !dragging) preview =
                                        progressAt(change.position.x).roundToInt()
                                    change.consume()
                                } while (!released)
                                if (released) currentCommit(preview)
                            } finally {
                                if (!released) preview = previous
                                dragging = false
                            }
                        }
                    },
            ) {
                val inset = 20.dp.toPx()
                val width = (size.width - inset * 2).coerceAtLeast(0f)
                val y = size.height / 2
                fun x(progress: Float): Float {
                    val fraction = progress / lastIndex
                    return inset + width * if (rtl) 1f - fraction else fraction
                }

                val progress = displayProgress.coerceIn(0f, lastIndex.toFloat())
                val start = Offset(x(0f), y)
                val end = Offset(x(lastIndex.toFloat()), y)
                val selected = Offset(x(progress), y)
                drawLine(track, start, end, 36.dp.toPx(), StrokeCap.Round)
                if (progress > 0f) drawLine(primary, start, selected, 36.dp.toPx(), StrokeCap.Round)
                // Node contrast follows the actual animated fill, including lower levels.
                for (index in 0..lastIndex) {
                    drawCircle(
                        if (index <= progress) thumb else dot,
                        3.dp.toPx(), Offset(x(index.toFloat()), y)
                    )
                }
                drawCircle(primary, 18.dp.toPx(), selected)
                drawCircle(thumb, 14.dp.toPx(), selected)
            }
        }
    }
}

/** Continuous logical position, mirrored in RTL; rounding is reserved for the selected level. */
internal fun reasoningSliderProgressAt(
    x: Float,
    width: Float,
    inset: Float,
    lastIndex: Int,
    rtl: Boolean
): Float {
    val fraction = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (if (rtl) 1f - fraction else fraction) * lastIndex.coerceAtLeast(0)
}

/** What the composer's single primary action does right now. */
internal enum class MaryComposerAction { STOP, INTERRUPT, FINALIZING, VOICE, SEND }

/**
 * A live run outranks everything (only "stop" makes sense). A live dictation session outranks the
 * draft text, because its only manual escape is to finish it — and while the final transcript is
 * still in flight the action is inert so a half-finalised draft cannot be sent. Otherwise the draft
 * decides: empty shows dictation, and any text — typed or dictated — shows send.
 */
internal fun maryComposerAction(
    running: Boolean,
    voice: MaryVoicePhase,
    value: String
): MaryComposerAction = when {
    running -> MaryComposerAction.STOP
    voice == MaryVoicePhase.FINALIZING -> MaryComposerAction.FINALIZING
    voice == MaryVoicePhase.RECORDING -> MaryComposerAction.INTERRUPT
    value.isBlank() -> MaryComposerAction.VOICE
    else -> MaryComposerAction.SEND
}

/**
 * Which glyph the primary action paints. Dictation — idle and recording alike — owns the waveform,
 * only a live agent run gets the stop square, and a session that is being finalised spins at its
 * edge. Keeping them apart is what makes "stop the run" and "stop dictating" visually distinct.
 */
internal enum class MaryComposerGlyph { STOP, ARROW, WAVEFORM, SPINNER }

internal fun maryComposerGlyph(action: MaryComposerAction): MaryComposerGlyph = when (action) {
    MaryComposerAction.STOP -> MaryComposerGlyph.STOP
    MaryComposerAction.SEND -> MaryComposerGlyph.ARROW
    MaryComposerAction.FINALIZING -> MaryComposerGlyph.SPINNER
    MaryComposerAction.VOICE, MaryComposerAction.INTERRUPT -> MaryComposerGlyph.WAVEFORM
}

internal fun isMaryComposerExpanded(
    focused: Boolean,
    value: String,
    pickerOpen: Boolean = false
): Boolean =
    focused || value.isNotEmpty() || pickerOpen

/** A hidden IME alone is not a dismissal: hardware-keyboard focus must stay expanded. */
internal fun shouldReleaseMaryComposerFocus(
    imeWasVisible: Boolean,
    imeTargetBottom: Int,
    pickerOpen: Boolean,
    pickerHidingIme: Boolean = false,
): Boolean = imeWasVisible && imeTargetBottom == 0 && !pickerOpen && !pickerHidingIme

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaryComposer(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    locked: Boolean,
    configuring: Boolean,
    provider: ProviderConfig,
    onReasoningSelected: (ReasoningEffort) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    approvalMode: ApprovalMode = ApprovalMode.ASK,
    onApprovalModeSelected: (ApprovalMode) -> Unit = {},
    voicePhase: MaryVoicePhase = MaryVoicePhase.IDLE,
    onStartVoice: () -> Unit = {},
    onInterruptVoice: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var reasoningPickerOpen by remember { mutableStateOf(false) }
    var approvalPickerOpen by remember { mutableStateOf(false) }
    val pickerOpen = reasoningPickerOpen || approvalPickerOpen
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(density)
    var imeWasVisible by remember { mutableStateOf(false) }
    var pickerHidingIme by remember { mutableStateOf(false) }
    var restorePickerFocus by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    fun pickerChanged(open: Boolean, reasoning: Boolean) {
        val wasOpen = reasoningPickerOpen || approvalPickerOpen
        if (open && !wasOpen) {
            restorePickerFocus = focused
            // Consume this keyboard dismissal before hide() or overlay focus can run.
            imeWasVisible = false
            pickerHidingIme = true
        }
        if (reasoning) reasoningPickerOpen = open else approvalPickerOpen = open
    }

    val releasingFocus =
        shouldReleaseMaryComposerFocus(imeWasVisible, imeTargetBottom, pickerOpen, pickerHidingIme)
    val expanded = isMaryComposerExpanded(focused && !releasingFocus, value, pickerOpen)
    LaunchedEffect(pickerOpen) {
        if (!pickerOpen && restorePickerFocus) {
            restorePickerFocus = false
            // Restore editing, not the IME. Subsequent real focus changes remain authoritative.
            if (!locked && !focused) inputFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(imeBottom, imeTargetBottom, pickerOpen, focused, pickerHidingIme) {
        if (pickerHidingIme) {
            imeWasVisible = false
            if (imeBottom == 0 && imeTargetBottom == 0) pickerHidingIme = false
        } else if (!pickerOpen && imeBottom > 0 && imeTargetBottom > 0) {
            // Only arm on a new opening/open keyboard, not residual picker closing frames.
            imeWasVisible = true
        } else if (shouldReleaseMaryComposerFocus(imeWasVisible, imeTargetBottom, pickerOpen)) {
            imeWasVisible = false
            if (focused) focusManager.clearFocus()
        }
    }
    val canSend = value.isNotBlank() && !running && !locked && !configuring
    val send: () -> Unit = { onSend(); focusManager.clearFocus(); keyboard?.hide() }
    // With nothing typed, the primary action is dictation; a draft (typed or dictated) makes it
    // send. While a dictation session is live the action is the interrupt that freezes its text.
    val action = maryComposerAction(running, voicePhase, value)
    val actionEnabled = when (action) {
        MaryComposerAction.STOP -> true
        MaryComposerAction.INTERRUPT -> !locked
        // The session is already finishing; a second press would have nothing to do.
        MaryComposerAction.FINALIZING -> false
        MaryComposerAction.VOICE -> !locked && !configuring
        MaryComposerAction.SEND -> canSend
    }
    val startVoice: () -> Unit = { focusManager.clearFocus(); keyboard?.hide(); onStartVoice() }
    val inputDescription = stringResource(
        if (voicePhase == MaryVoicePhase.RECORDING) R.string.mary_input_listening
        else R.string.mary_input_hint
    )
    // One reversible clock drives geometry; no input, action or picker is replaced on exit.
    val expansion =
        animateFloatAsState(if (expanded) 1f else 0f, tween(220), label = "maryComposerExpansion")
    val toolsVisible by remember { derivedStateOf { expansion.value > 0f } }
    // The outer clip also contains exiting tools within the shrinking safe-area bounds.
    val shape = RoundedCornerShape(28.dp)
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val shadowTint =
        if (surface.luminance() < 0.35f) MiuixTheme.colorScheme.primary else Color.Black
    val shadowAlpha = if (surface.luminance() < 0.35f) 0.009f else 0.0085f
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mary-composer")
            .drawWithCache {
                val radius = 28.dp.toPx()
                val offsetY = 3.dp.toPx()
                val extent = 24.dp.toPx()
                // Cached translucent rounded layers approximate a broad Gaussian falloff.
                // This draw modifier precedes clipping: only the capsule, never its halo,
                // is clipped. No blur layer is applied to text or other input content.
                val layers = (24 downTo 1).map { step ->
                    val spread = extent * step / 24f
                    val falloff = 1f - step / 25f
                    Triple(
                        spread, shadowTint.copy(alpha = shadowAlpha * falloff * falloff),
                        CornerRadius(radius + spread)
                    )
                }
                onDrawBehind {
                    layers.forEach { (spread, color, corner) ->
                        drawRoundRect(
                            color, Offset(-spread, offsetY - spread),
                            Size(size.width + 2 * spread, size.height + 2 * spread), corner
                        )
                    }
                }
            }
            .clip(shape)
            .background(surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        content = {
            BasicTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier
                    .testTag("mary-composer-input")
                    .semantics { contentDescription = inputDescription }
                    .clipToBounds()
                    .heightIn(min = 44.dp)
                    .focusRequester(inputFocusRequester)
                    .onFocusChanged { focused = it.isFocused },
                enabled = !locked,
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceContainer),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                maxLines = 4,
                decorationBox = { inner ->
                    Box(
                        Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) Text(
                            inputDescription, style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        inner()
                    }
                },
            )
            // Keep both picker owners mounted even when the compact tools are not exposed.
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (toolsVisible) Modifier.testTag("mary-composer-tools")
                        else Modifier.clearAndSetSemantics {}),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(44.dp))
                ApprovalModeButton(
                    approvalMode,
                    enabled = expanded && !running && !locked && !configuring,
                    onExpandedChange = { pickerChanged(it, reasoning = false) },
                    onSelected = onApprovalModeSelected
                )
                Spacer(Modifier.weight(1f))
                // Saves are queued by the controller, so a second selection stays available
                // while the preceding setting is persisted. Execution/lock still disables it.
                ReasoningStrengthButton(
                    provider,
                    enabled = expanded && !running && !locked,
                    onExpandedChange = { pickerChanged(it, reasoning = true) },
                    onSelected = onReasoningSelected
                )
                Spacer(Modifier.width(4.dp))
                Spacer(Modifier.width(44.dp))
            }
            ComposerAttachButton(locked)
            ComposerActionButton(
                action = action,
                enabled = actionEnabled,
                onSend = send,
                onStop = onStop,
                onStartVoice = startVoice,
                onInterruptVoice = onInterruptVoice,
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val attach = measurables[2].measure(loose)
        val sendButton = measurables[3].measure(loose)
        val inset = maryComposerGeometry(width, 0, 0, attach.width, expansion.value).inputInset
        val inputWidth = (width - 2 * inset).coerceAtLeast(0)
        // Measure the real editing/semantics bounds to the animated viewport, not full width
        // behind the actions. Only an empty field collapses; its single-line placeholder cannot
        // reflow, while nonempty multiline input keeps the expanded target throughout.
        val input = measurables[0].measure(loose.copy(minWidth = inputWidth, maxWidth = inputWidth))
        val tools = measurables[1].measure(loose.copy(minWidth = width))
        val geometry =
            maryComposerGeometry(width, input.height, tools.height, attach.width, expansion.value)
        layout(width, constraints.constrainHeight(geometry.height)) {
            input.placeRelative(geometry.inputInset, 0)
            tools.placeRelativeWithLayer(0, input.height) { alpha = expansion.value }
            attach.placeRelative(0, geometry.actionY)
            sendButton.placeRelative(width - sendButton.width, geometry.actionY)
        }
    }
}

/** Pixel geometry shares one clamped progress, including when an animation reverses mid-flight. */
internal data class MaryComposerGeometry(val height: Int, val inputInset: Int, val actionY: Int)

internal fun maryComposerGeometry(
    width: Int,
    inputHeight: Int,
    toolsHeight: Int,
    actionSize: Int,
    progress: Float
): MaryComposerGeometry {
    val fraction = progress.coerceIn(0f, 1f)
    val inset = (actionSize * (1f - fraction)).roundToInt().coerceAtMost(width / 2)
    val compactY = ((inputHeight - actionSize) / 2).coerceAtLeast(0)
    return MaryComposerGeometry(
        height = inputHeight + (toolsHeight * fraction).roundToInt(),
        inputInset = inset,
        actionY = (compactY + (inputHeight - compactY) * fraction).roundToInt(),
    )
}

@Composable
private fun ComposerAttachButton(locked: Boolean) {
    IconButton(
        onClick = {},
        enabled = !locked,
        modifier = Modifier
            .size(44.dp)
            .testTag("mary-composer-attach")
    ) {
        Icon(
            Icons.Outlined.Add, contentDescription = stringResource(R.string.mary_action_attach),
            tint = MiuixTheme.colorScheme.onSurfaceContainer, modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun ComposerActionButton(
    action: MaryComposerAction,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStartVoice: () -> Unit,
    onInterruptVoice: () -> Unit,
) {
    val description = stringResource(
        when (action) {
            MaryComposerAction.STOP -> R.string.mary_action_stop
            MaryComposerAction.INTERRUPT -> R.string.mary_action_voice_stop
            MaryComposerAction.FINALIZING -> R.string.mary_action_voice_finalizing
            MaryComposerAction.VOICE -> R.string.mary_action_voice
            MaryComposerAction.SEND -> R.string.mary_action_send
        }
    )
    // Keep an accessible touch target while making the visible circle proportional to the bar.
    Box(
        Modifier
            .size(44.dp)
            .testTag("mary-composer-send")
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button) {
                when (action) {
                    MaryComposerAction.STOP -> onStop()
                    MaryComposerAction.INTERRUPT -> onInterruptVoice()
                    // Inert while the final transcript is in flight; the button is disabled anyway.
                    MaryComposerAction.FINALIZING -> Unit
                    MaryComposerAction.VOICE -> onStartVoice()
                    MaryComposerAction.SEND -> onSend()
                }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .testTag("mary-composer-send-circle")
                .background(MiuixTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val tint = MiuixTheme.colorScheme.onPrimary
            when (val glyph = maryComposerGlyph(action)) {
                MaryComposerGlyph.WAVEFORM -> VoiceWaveIcon(
                    // Listening is the live session; the idle dictation glyph holds still.
                    animated = action == MaryComposerAction.INTERRUPT,
                    tint = tint,
                    modifier = Modifier
                        .size(20.dp)
                        .testTag("mary-composer-voice-icon")
                )

                MaryComposerGlyph.SPINNER -> ComposerEdgeSpinner(
                    tint = tint,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("mary-composer-voice-spinner")
                )

                MaryComposerGlyph.ARROW, MaryComposerGlyph.STOP -> Icon(
                    imageVector =
                        if (glyph == MaryComposerGlyph.ARROW) Icons.Outlined.ArrowUpward
                        else Icons.Filled.Stop,
                    contentDescription = null, tint = tint,
                    modifier = Modifier
                        .size(20.dp)
                        .testTag("mary-composer-send-icon")
                )
            }
        }
    }
}

/**
 * An equalizer-style audio glyph for dictation — deliberately not a microphone. Five thick
 * round-capped bars span the whole box, which reads wider and heavier than the sparse `GraphicEq`
 * outline it replaces. While [animated] (a live session) each bar bobs on its own staggered phase
 * so the composer reads as "recording", not as the stop square that ends the conversation.
 */
@Composable
private fun VoiceWaveIcon(animated: Boolean, tint: Color, modifier: Modifier = Modifier) {
    // Reading the phase only inside the draw scope repaints the bars without recomposing.
    val phase = if (animated) rememberVoiceWavePhase() else null
    Canvas(modifier) {
        val barWidth = size.width / (VoiceWaveWeights.size * 2 - 1)
        val usableHeight = size.height - barWidth
        val step = size.width / VoiceWaveWeights.size
        val centerY = size.height / 2f
        VoiceWaveWeights.forEachIndexed { index, weight ->
            val scale = phase?.let { voiceWaveScale(weight, index, it.value) } ?: weight
            val half = usableHeight * scale / 2f
            val x = step * index + step / 2f
            drawLine(
                color = tint,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** One bar's height while listening: the resting shape plus a staggered sine bob, kept in-box. */
internal fun voiceWaveScale(weight: Float, index: Int, phase: Float): Float {
    val bob = 0.5f + 0.5f * sin(phase + index * VoiceWavePhaseStep)
    return (weight * VoiceWaveRestWeight + bob * VoiceWaveBobWeight).coerceIn(0.2f, 1f)
}

@Composable
private fun rememberVoiceWavePhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "maryVoiceWave")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "maryVoiceWavePhase",
    )
}

/** Symmetric bar heights, tallest in the middle; hoisted so drawing allocates nothing. */
private val VoiceWaveWeights = listOf(0.42f, 0.78f, 1f, 0.78f, 0.42f)
private const val VoiceWavePhaseStep = 0.9f
private const val VoiceWaveRestWeight = 0.62f
private const val VoiceWaveBobWeight = 0.38f

/**
 * An indeterminate arc sweeping along the action circle's edge, shown while a dictation session is
 * being finalised. It replaces the bobbing waveform so "recording" and "finishing" are distinct.
 */
@Composable
private fun ComposerEdgeSpinner(tint: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "maryComposerSpinner")
    // Read inside the draw scope so the arc animates without recomposing the button.
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "maryComposerSpinnerAngle",
    )
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2f
        drawArc(
            color = tint,
            startAngle = angle.value,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(
                (size.width - stroke).coerceAtLeast(0f),
                (size.height - stroke).coerceAtLeast(0f)
            ),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
