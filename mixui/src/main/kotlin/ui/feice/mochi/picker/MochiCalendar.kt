// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiIconButton
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.parts.MochiGlyphs
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.shape.drawG2RoundRect
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.rememberMochiHaptics

/** 日历选中的是什么 */
@Immutable
public sealed interface MochiDateSelection {
    /** 选一天；[date] = null 时没选 */
    public data class Single(val date: MochiDate?) : MochiDateSelection

    /** 选一段；先点起点（[end] = null），再点终点。点在起点之前就换成新的起点 */
    public data class Range(val start: MochiDate?, val end: MochiDate?) : MochiDateSelection
}

/** 日历的颜色 */
@Immutable
public data class MochiCalendarColors(
    /** 选中圆、今天的数字、翻页箭头 */
    val accent: Color,
    /** 选中圆里的数字 */
    val onAccent: Color,
    /** 范围中间段的底色 */
    val rangeFill: Color,
    val content: Color,
    /** 星期栏、超出范围的日子 */
    val secondaryContent: Color,
)

/** 日历的默认值 */
public object MochiCalendarDefaults {
    /** 每一格的高度 */
    public val CellHeight: Dp = 44.dp

    /** 选中圆的直径 */
    public val SelectionSize: Dp = 40.dp

    @Composable
    public fun colors(
        accent: Color = MochiTheme.colorScheme.primary,
        onAccent: Color = MochiTheme.colorScheme.onPrimary,
        rangeFill: Color = MochiTheme.colorScheme.primary.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.28f else 0.16f),
        content: Color = MochiTheme.colorScheme.onSurface,
        secondaryContent: Color = MochiTheme.colorScheme.onSurfaceVariant,
    ): MochiCalendarColors = remember(accent, onAccent, rangeFill, content, secondaryContent) {
        MochiCalendarColors(accent, onAccent, rangeFill, content, secondaryContent)
    }

    /** 每周从哪天开始：跟随系统区域（中国、欧洲是周一，美国是周日）；1 = 周一 … 7 = 周日 */
    @Composable
    public fun firstDayOfWeek(): Int {
        val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
        return remember(locale) {
            when (Calendar.getInstance(locale).firstDayOfWeek) {
                Calendar.SUNDAY -> 7
                Calendar.SATURDAY -> 6
                else -> 1
            }
        }
    }
}

/**
 * 月历（iOS UIDatePicker 的 .inline / .graphical）：
 * - 左右滑动或点右上角箭头翻月；顶上「2026年10月 ›」点一下原地换成年月滚筒（模糊替换），再点换回来
 * - 选一天时实心圆滑到新日期（弹簧），选范围时起止是实心圆、中间按周连成胶囊
 * - 今天的数字用强调色；超出 [min] / [max] 的日子变淡、点不了
 */
@Composable
public fun MochiCalendar(
    selection: MochiDateSelection,
    onSelectionChange: (MochiDateSelection) -> Unit,
    modifier: Modifier = Modifier,
    min: MochiDate = MochiDate(1900, 1, 1),
    max: MochiDate = MochiDate(2100, 12, 31),
    firstDayOfWeek: Int = MochiCalendarDefaults.firstDayOfWeek(),
    colors: MochiCalendarColors = MochiCalendarDefaults.colors(),
) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val cjk = locale.language in setOf("zh", "ja", "ko")
    val today = remember { MochiDate.today() }
    val anchor = when (selection) {
        is MochiDateSelection.Single -> selection.date
        is MochiDateSelection.Range -> selection.start
    } ?: today
    val pageCount = CalendarGeometry.monthIndex(max.year, max.month, min.year, min.month) + 1
    val initialPage = CalendarGeometry.monthIndex(anchor.year, anchor.month, min.year, min.month).coerceIn(0, pageCount - 1)
    val pager = rememberPagerState(initialPage) { pageCount }
    val scope = rememberCoroutineScope()
    var wheels by remember { mutableStateOf(false) }
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val current by rememberUpdatedState(selection)
    val change by rememberUpdatedState(onSelectionChange)
    val (shownYear, shownMonth) = CalendarGeometry.monthAt(pager.currentPage, min.year, min.month)
    val monthNames = remember(locale) { DateFormatSymbols.getInstance(locale).months }
    val title = if (cjk) "${shownYear}年${shownMonth}月" else "${monthNames[shownMonth - 1]} $shownYear"

    fun pick(date: MochiDate) {
        haptics.tick()
        val next = when (val s = current) {
            is MochiDateSelection.Single -> MochiDateSelection.Single(date)

            is MochiDateSelection.Range -> when {
                s.start == null || s.end != null -> MochiDateSelection.Range(date, null)
                date < s.start -> MochiDateSelection.Range(date, null)
                else -> MochiDateSelection.Range(s.start, date)
            }
        }
        change(next)
    }

    Column(modifier) {
        // 顶栏：年月（点了切滚筒） + 翻页箭头
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            val chevron by animateFloatAsState(if (wheels) 90f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "chevron")
            Row(
                Modifier
                    .padding(start = 4.dp)
                    .pointerInput(Unit) { detectTapGestures { wheels = !wheels } }
                    .semantics {
                        role = Role.Button
                        onClick {
                            wheels = !wheels
                            true
                        }
                    }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MochiBlurText(
                    title,
                    style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                MochiGlyphs.Chevron(colors.accent, size = 14.dp, rotation = { chevron })
            }
            Spacer(Modifier.weight(1f))
            MochiBlurCrossfade(wheels) { w ->
                if (!w) {
                    Row {
                        MochiIconButton(
                            onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                            style = MochiButtonStyle.Text,
                            size = 40.dp,
                            enabled = pager.currentPage > 0,
                        ) { MochiGlyphs.Chevron(colors.accent, size = 18.dp, rotation = { 180f }) }
                        MochiIconButton(
                            onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pageCount - 1)) } },
                            style = MochiButtonStyle.Text,
                            size = 40.dp,
                            enabled = pager.currentPage < pageCount - 1,
                        ) { MochiGlyphs.Chevron(colors.accent, size = 18.dp) }
                    }
                }
            }
        }

        val gridHeight = MochiCalendarDefaults.CellHeight * 7
        MochiBlurCrossfade(wheels, Modifier.fillMaxWidth().height(gridHeight), contentAlignment = Alignment.TopCenter) { w ->
            if (w) {
                MonthYearWheels(
                    year = shownYear,
                    month = shownMonth,
                    min = min,
                    max = max,
                    cjk = cjk,
                    monthNames = monthNames,
                    onChange = { y, m ->
                        scope.launch {
                            pager.scrollToPage(CalendarGeometry.monthIndex(y, m, min.year, min.month).coerceIn(0, pageCount - 1))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = MochiCalendarDefaults.CellHeight),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    WeekdayHeader(firstDayOfWeek, locale, colors)
                    HorizontalPager(
                        pager,
                        Modifier.fillMaxWidth().height(MochiCalendarDefaults.CellHeight * 6),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val (y, m) = CalendarGeometry.monthAt(page, min.year, min.month)
                        MonthGrid(y, m, firstDayOfWeek, selection, today, min, max, colors, ::pick)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: Int, locale: Locale, colors: MochiCalendarColors) {
    // shortWeekdays 的下标：1 = 周日 … 7 = 周六；这里按 ISO（1 = 周一）换算
    val names = remember(locale) {
        val s = DateFormatSymbols.getInstance(locale).shortWeekdays
        (0 until 7).map { i ->
            val iso = ((firstDayOfWeek - 1 + i) % 7) + 1
            val cal = if (iso == 7) 1 else iso + 1
            s[cal].uppercase(locale)
        }
    }
    Row(Modifier.fillMaxWidth().height(MochiCalendarDefaults.CellHeight * 0.75f), verticalAlignment = Alignment.CenterVertically) {
        names.forEach {
            Text(
                it,
                Modifier.weight(1f),
                style = MochiTheme.textStyles.footnote.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                color = colors.secondaryContent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    year: Int,
    month: Int,
    firstDayOfWeek: Int,
    selection: MochiDateSelection,
    today: MochiDate,
    min: MochiDate,
    max: MochiDate,
    colors: MochiCalendarColors,
    onPick: (MochiDate) -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scratch = remember { Path() }
    val dayStyle = MochiTheme.textStyles.body.copy(fontSize = MochiTheme.textStyles.body.fontSize * 1.1f, textAlign = TextAlign.Center)
    val rows = CalendarGeometry.rows(year, month, firstDayOfWeek)
    val selected: MochiDate? = when (selection) {
        is MochiDateSelection.Single -> selection.date
        is MochiDateSelection.Range -> selection.start
    }
    val end: MochiDate? = (selection as? MochiDateSelection.Range)?.end
    val inMonth = { d: MochiDate? -> d != null && d.year == year && d.month == month }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellW = with(density) { maxWidth.toPx() } / 7f
        val cellH = with(density) { MochiCalendarDefaults.CellHeight.toPx() }
        fun centerOf(d: MochiDate): Offset {
            val (r, c) = CalendarGeometry.cellOf(d, firstDayOfWeek)
            return Offset((c + 0.5f) * cellW, (r + 0.5f) * cellH)
        }

        // 选中圆：同一个月里换选时滑过去；从别的月选进来时从 0 放大
        val target = if (inMonth(selected)) centerOf(selected!!) else null
        val pos = remember { Animatable(target ?: Offset.Zero, Offset.VectorConverter) }
        val presence = remember { Animatable(if (target != null) 1f else 0f) }
        LaunchedEffect(target) {
            if (target == null) {
                presence.animateTo(0f, spring(dampingRatio = 1f, stiffness = 900f))
            } else if (presence.value < 0.05f) {
                pos.snapTo(target)
                presence.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f))
            } else {
                pos.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 420f))
            }
        }
        val endTarget = if (inMonth(end)) centerOf(end!!) else null
        val endPresence = remember { Animatable(if (endTarget != null) 1f else 0f) }
        LaunchedEffect(endTarget) {
            endPresence.animateTo(
                if (endTarget !=
                    null
                ) {
                    1f
                } else {
                    0f
                },
                spring(dampingRatio = if (endTarget != null) 0.6f else 1f, stiffness = 500f),
            )
        }
        val segments = remember(selection, year, month, firstDayOfWeek) {
            val s = selection as? MochiDateSelection.Range
            if (s?.start != null &&
                s.end != null
            ) {
                CalendarGeometry.rangeSegments(s.start, s.end, year, month, firstDayOfWeek)
            } else {
                emptyList()
            }
        }
        val rangeAlpha = remember { Animatable(0f) }
        LaunchedEffect(segments) {
            rangeAlpha.snapTo(0f)
            if (segments.isNotEmpty()) rangeAlpha.animateTo(1f, spring(dampingRatio = 1f, stiffness = 600f))
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(year, month, firstDayOfWeek, cellW, cellH) {
                    detectTapGestures { p ->
                        val r = (p.y / cellH).toInt()
                        val c = (p.x / cellW).toInt().coerceIn(0, 6)
                        val d = CalendarGeometry.dayAt(year, month, firstDayOfWeek, r, c) ?: return@detectTapGestures
                        val date = MochiDate(year, month, d)
                        if (date in min..max) onPick(date)
                    }
                },
        ) {
            val sel = MochiCalendarDefaults.SelectionSize.toPx()
            // 范围中间段：按周连成胶囊，换行处贴到格子边
            for (seg in segments) {
                val top = (seg.row + 0.5f) * cellH - sel / 2f
                val left = if (seg.roundStart) (seg.startCol + 0.5f) * cellW - sel / 2f else seg.startCol * cellW
                val right = if (seg.roundEnd) (seg.endCol + 0.5f) * cellW + sel / 2f else (seg.endCol + 1) * cellW
                // 起止端是半圆头，换行处是直边（贴到格子边，和下一行的段接上）
                val l = if (seg.roundStart) sel / 2f else 0f
                val r = if (seg.roundEnd) sel / 2f else 0f
                drawG2RoundRect(scratch, Rect(left, top, right, top + sel), l, r, r, l, colors.rangeFill, alpha = rangeAlpha.value)
            }
            val pr = presence.value
            if (pr > 0f) drawCircle(colors.accent, radius = sel / 2f * pr, center = pos.value)
            val er = endPresence.value
            if (er > 0f && endTarget != null) drawCircle(colors.accent, radius = sel / 2f * er, center = endTarget)

            for (r in 0 until rows) {
                for (c in 0 until 7) {
                    val d = CalendarGeometry.dayAt(year, month, firstDayOfWeek, r, c) ?: continue
                    val date = MochiDate(year, month, d)
                    val isSel = date == selected || date == end
                    val color = when {
                        date !in min..max -> colors.secondaryContent.copy(alpha = 0.4f)
                        isSel -> colors.onAccent
                        date == today -> colors.accent
                        else -> colors.content
                    }
                    val weight = if (isSel || date == today) FontWeight.SemiBold else FontWeight.Normal
                    val layout = measurer.measure(d.toString(), dayStyle.copy(color = color, fontWeight = weight))
                    drawText(
                        layout,
                        topLeft = Offset(
                            (c + 0.5f) * cellW - layout.size.width / 2f,
                            (r + 0.5f) * cellH - layout.size.height / 2f,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthYearWheels(
    year: Int,
    month: Int,
    min: MochiDate,
    max: MochiDate,
    cjk: Boolean,
    monthNames: Array<String>,
    onChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val months = remember(cjk) { (1..12).map { if (cjk) "${it}月" else monthNames[it - 1] }.toImmutableList() }
    val years = remember(min.year, max.year, cjk) { (min.year..max.year).map { if (cjk) "${it}年" else it.toString() }.toImmutableList() }
    val y by rememberUpdatedState(year)
    val m by rememberUpdatedState(month)
    MochiWheelPickerGroup(modifier) {
        if (cjk) {
            MochiWheelPicker(years, year - min.year, { onChange(min.year + it, m) }, Modifier.weight(1f))
            MochiWheelPicker(months, month - 1, { onChange(y, it + 1) }, Modifier.weight(1f), cyclic = true)
        } else {
            MochiWheelPicker(months, month - 1, { onChange(y, it + 1) }, Modifier.weight(1.4f), cyclic = true)
            MochiWheelPicker(years, year - min.year, { onChange(min.year + it, m) }, Modifier.weight(1f))
        }
    }
}
