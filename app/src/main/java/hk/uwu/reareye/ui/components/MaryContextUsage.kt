package hk.uwu.reareye.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.context.ContextCategory
import hk.uwu.reareye.agent.context.ContextSegment
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.NumberFormat
import java.util.Locale

/** Maps a real proportional strip, never artificially inflating small categories. */
internal fun contextSegmentIndexAt(segments: List<ContextSegment>, fraction: Float): Int? {
    if (!fraction.isFinite()) return null
    val total = segments.sumOf { it.tokens.coerceAtLeast(0).toLong() }
    if (total == 0L) return null
    val target = fraction.coerceIn(0f, 1f).toDouble() * total
    var end = 0L
    var last: Int? = null
    segments.forEachIndexed { index, segment ->
        if (segment.tokens > 0) {
            last = index
            end += segment.tokens
            if (target < end) return index
        }
    }
    return last
}

internal fun contextCategoryLabel(category: ContextCategory): Int = when (category) {
    ContextCategory.SYSTEM -> R.string.mary_context_system
    ContextCategory.TOOL_DEFINITIONS -> R.string.mary_context_definitions
    ContextCategory.USER -> R.string.mary_context_user
    ContextCategory.ASSISTANT -> R.string.mary_context_assistant
    ContextCategory.TOOL_CALLS -> R.string.mary_context_calls
    ContextCategory.TOOL_RESULTS -> R.string.mary_context_results
    ContextCategory.NATIVE_PROTOCOL -> R.string.mary_context_native
    ContextCategory.FORMATTING -> R.string.mary_context_formatting
}

private fun categoryColor(category: ContextCategory): Color = when (category) {
    ContextCategory.SYSTEM -> Color(0xFF6478E8)
    ContextCategory.TOOL_DEFINITIONS -> Color(0xFF9470D8)
    ContextCategory.USER -> Color(0xFF3D8EBD)
    ContextCategory.ASSISTANT -> Color(0xFF31987D)
    ContextCategory.TOOL_CALLS -> Color(0xFFD67A38)
    ContextCategory.TOOL_RESULTS -> Color(0xFFD3A832)
    ContextCategory.NATIVE_PROTOCOL -> Color(0xFF8B829B)
    ContextCategory.FORMATTING -> Color(0xFF939AA6)
}

private fun compactTokens(value: Int?): String {
    if (value == null || value < 0) return "—"
    val unit = when { value >= 1_000_000_000 -> 1_000_000_000 to "B"; value >= 1_000_000 -> 1_000_000 to "M"; value >= 1000 -> 1000 to "K"; else -> 1 to "" }
    return if (unit.first == 1) value.toString() else String.format(Locale.getDefault(), "%.1f%s", value.toDouble() / unit.first, unit.second)
}
private fun percent(value: Double?): String = value?.takeIf { it.isFinite() }?.let {
    String.format(Locale.getDefault(), "%.1f%%", it * 100)
} ?: "—"

/** Independent native implementation. Composition estimates and provider usage are separate facts. */
@Composable
internal fun ContextUsageButton(summary: MaryUsageSummary) {
    val colors = MiuixTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var expanded by remember { mutableStateOf(false) }
    var sequence by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<ContextCategory?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableStateOf(false) }
    LaunchedEffect(summary.sourceKey) {
        expanded = false; sequence = false; selectedCategory = null; selectedIndex = 0; remaining = false
    }
    val categories = remember(summary.composition, summary.loading) {
        if (summary.loading || summary.composition?.saturated == true) emptyList() else summary.composition?.categories?.map { ContextSegment(it.category, it.tokens) }.orEmpty()
    }
    val ordered = if (summary.loading || summary.composition?.saturated == true) emptyList() else summary.composition?.segments.orEmpty()
    val displayed = if (sequence) ordered else categories
    val effectiveIndex = selectedIndex.coerceIn(0, ordered.lastIndex.coerceAtLeast(0))
    LaunchedEffect(sequence, ordered.size) {
        if (sequence && selectedIndex != effectiveIndex) selectedIndex = effectiveIndex
    }
    val used = summary.estimatedTokens
    val ratio = summary.contextRatio
    val approximation = if (summary.composition?.saturated == true) "≥" else "≈"
    val description = stringResource(R.string.mary_chat_context_action,
        ratio?.let { approximation + percent(it) } ?: "—")
    fun choose(index: Int) {
        displayed.getOrNull(index)?.let {
            selectedIndex = index; selectedCategory = it.category; remaining = false
        }
    }
    val selected = if (remaining) null else if (sequence) displayed.getOrNull(effectiveIndex) else {
        categories.firstOrNull { it.category == selectedCategory } ?: categories.maxByOrNull { it.tokens }
    }
    val screen = LocalConfiguration.current
    val popupWidth = (screen.screenWidthDp - 24).coerceIn(160, 320).dp
    val popupHeight = (screen.screenHeightDp - 88).coerceIn(144, 360).dp
    Box {
        // Preserve the existing compact topbar ring; detailed composition lives only in the popup.
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("mary-context-usage").semantics(mergeDescendants = true) {
                contentDescription = description
                ratio?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat().coerceIn(0f, 1f), 0f..1f) }
            },
        ) {
            Canvas(Modifier.size(20.dp).testTag("mary-context-ring")) {
                val stroke = 2.dp.toPx()
                drawCircle(colors.outline, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                val sweep = contextProgressSweep(ratio)
                if (sweep != 0f) drawArc(
                    colors.primary, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Butt),
                )
            }
        }
        DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.width(popupWidth).heightIn(max = popupHeight).testTag("mary-context-popup"),
            offset = DpOffset(0.dp, 2.dp), properties = PopupProperties(focusable = true),
            shape = RoundedCornerShape(18.dp), containerColor = colors.surface,
            tonalElevation = 0.dp, shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mary_context_short_title), style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    ModeButton(stringResource(R.string.mary_context_category_mode), !sequence, "mary-context-mode-category") {
                        sequence = false; remaining = false
                    }
                    ModeButton(stringResource(R.string.mary_context_sequence_mode), sequence, "mary-context-mode-sequence") {
                        sequence = true; remaining = false; selectedIndex = 0
                    }
                    IconButton(onClick = { expanded = false }, modifier = Modifier.size(48.dp).testTag("mary-context-close")) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.mary_sidebar_close), modifier = Modifier.size(18.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("mary-context-capacity"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (summary.loading) stringResource(R.string.mary_context_loading) else
                            "${if (used != null) approximation else ""}${compactTokens(used)} / ${compactTokens(summary.windowTokens.takeIf { it > 0 })}",
                            style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.mary_context_capacity_label), style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary)
                    }
                    Text(if (ratio == null) "—" else approximation + percent(ratio), style = MiuixTheme.textStyles.body1)
                }
                Box(Modifier.fillMaxWidth().height(48.dp).testTag("mary-context-breakdown-bar")
                    .pointerInput(displayed, rtl) {
                        detectTapGestures { point ->
                            var fraction = point.x / size.width.coerceAtLeast(1)
                            if (rtl) fraction = 1f - fraction
                            contextSegmentIndexAt(displayed, fraction)?.let(::choose)
                        }
                    }, contentAlignment = Alignment.Center) {
                    ContextStrip(displayed, if (displayed.isEmpty()) 0f else 1f,
                        if (sequence) effectiveIndex else categories.indexOfFirst { it.category == selected?.category },
                        Modifier.fillMaxWidth().height(10.dp))
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(colors.primary.copy(alpha = 0.065f)).padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("mary-context-detail")) {
                    val free = if (used != null && summary.windowTokens > 0) (summary.windowTokens.toLong() - used).coerceAtLeast(0L) else null
                    val name = when {
                        remaining -> stringResource(R.string.mary_context_remaining)
                        selected != null -> stringResource(contextCategoryLabel(selected.category)) +
                            if (sequence) " · ${effectiveIndex + 1}" else ""
                        else -> stringResource(when {
                            summary.loading -> R.string.mary_context_loading
                            summary.composition?.saturated == true -> R.string.mary_context_saturated
                            summary.composition?.totalTokens == 0 -> R.string.mary_context_empty
                            else -> R.string.mary_context_no_breakdown
                        })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val amount = if (remaining) free else selected?.tokens?.toLong()
                        if (amount != null) Text("≈" + NumberFormat.getIntegerInstance().format(amount), style = MiuixTheme.textStyles.footnote1)
                    }
                    if (selected != null && used != null && used > 0) {
                        Text(stringResource(R.string.mary_context_shares,
                            percent(selected.tokens.toDouble() / used),
                            percent(summary.windowTokens.takeIf { it > 0 }?.let { selected.tokens.toDouble() / it })),
                            style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                    } else if (remaining && free != null) {
                        Text(stringResource(R.string.mary_context_free_share, percent(free.toDouble() / summary.windowTokens)),
                            style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                    }
                }
                Box(Modifier.width(popupWidth - 24.dp).height(48.dp)) {
                    LazyRow(Modifier.fillMaxSize().testTag("mary-context-legend-list"), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        itemsIndexed(displayed) { index, segment ->
                            val active = !remaining && (if (sequence) effectiveIndex == index else selected?.category == segment.category)
                            Row(Modifier.height(48.dp).widthIn(min = 72.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { choose(index) }
                                .semantics { this.selected = active }
                                .testTag(if (sequence) "mary-context-sequence-$index" else "mary-context-legend-${segment.category.name}")
                                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(categoryColor(segment.category), RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(5.dp))
                                Text(if (sequence) "${index + 1} · " + stringResource(contextCategoryLabel(segment.category))
                                    else stringResource(contextCategoryLabel(segment.category)),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = if (active) colors.primary else colors.onSurface)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().testTag("mary-context-provider")) {
                    Text(stringResource(R.string.mary_context_provider_input, compactTokens(summary.usage?.inputTokens.takeUnless { summary.loading }), percent(summary.cacheRatio.takeUnless { summary.loading })),
                        style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                    if (summary.budgetCorrectionTokens > 0) Text(
                        stringResource(R.string.mary_context_correction, compactTokens(summary.budgetCorrectionTokens)),
                        style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                    Text(stringResource(if (sequence) R.string.mary_context_sequence_note else R.string.mary_context_estimate_note),
                        style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 48.dp, height = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { selected = active }.testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, style = MiuixTheme.textStyles.footnote2,
            color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ContextStrip(segments: List<ContextSegment>, occupied: Float, selected: Int?, modifier: Modifier) {
    val track = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
    val outline = MiuixTheme.colorScheme.onSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        drawRect(track)
        val total = segments.sumOf { it.tokens.toLong() }.coerceAtLeast(0L)
        if (total == 0L) return@Canvas
        var start = 0f
        segments.forEachIndexed { index, segment ->
            val width = size.width * occupied.coerceIn(0f, 1f) * (segment.tokens.toDouble() / total).toFloat()
            val left = if (rtl) size.width - start - width else start
            drawRect(categoryColor(segment.category), Offset(left, 0f), Size(width, size.height))
            if (selected == index && width > 1f) drawRect(outline, Offset(left, 0f), Size(width, size.height), style = Stroke(1.dp.toPx()))
            start += width
        }
    }
}

/** Preserve the existing clockwise topbar ring geometry. */
internal fun contextProgressSweep(ratio: Double?): Float =
    if (ratio == null || !ratio.isFinite()) 0f else (ratio.coerceIn(0.0, 1.0) * 360).toFloat()
