// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ui.feice.mochi.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.content.mochiAnimateHeight
import ui.feice.mochi.shape.ConcentricBottom
import ui.feice.mochi.shape.ConcentricTop
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.LocalMochiControlPalette
import ui.feice.mochi.theme.LocalMochiSymbolWeight
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/** 列表的配色 */
@Immutable
public class MochiListColors(
    public val container: Color,
    public val content: Color,
    public val secondary: Color,
    public val divider: Color,
    public val highlight: Color,
    public val accent: Color,
    public val destructive: Color,
    /** 行尾 › 的颜色（iOS 用三级文字色，比次要文字更淡） */
    public val chevron: Color = secondary.copy(alpha = 0.7f),
)

/**
 * 行首图标的两种样子（iOS 列表里的两种）：
 * - [Tile]：「设置」App 那种彩色图标块，28×28，到文字 15（文字从 59 开始）
 * - [Symbol]：没有底块的符号图标，占一块 24 宽、正文行高那么高的位置，图标在里面居中，到文字 16（文字从 56 开始）；
 *   图标默认用强调色，描边粗细跟标题字重（[ui.feice.mochi.theme.LocalMochiSymbolWeight]）
 */
public enum class MochiListLeadingStyle { Tile, Symbol }

/**
 * 列表的尺寸，照 iOS 26 的 UIKit 列表单元格（UIListContentConfiguration、UITableConstants 的 26.0 数值）：
 * 行最小高度 = 正文行高 22 + 上下各 15；左右边距 16；分组卡片圆角 26。
 */
public object MochiListDefaults {
    /** 行的最小高度（默认字号下：正文行高 22 + 上下各 [RowVerticalPadding]） */
    public val RowMinHeight: Dp = 52.dp

    /** 行左右的边距：行首图标、纯文字行的文字、行尾的值和控件都离卡片边这么远 */
    public val RowPadding: Dp = 16.dp

    /** 纯文字行的文字起点，分组小标题和说明也从这里开始（iOS 里和 [RowPadding] 相同） */
    public val TextRowPadding: Dp = 16.dp

    /** 图标块（[MochiListLeadingStyle.Tile]）的边长：iOS 26「设置」的图标 28×28 */
    public val LeadingSize: Dp = 28.dp

    /** 图标块的圆角：iOS 图标的连续圆角，边长的 22.37% */
    public val LeadingCorner: Dp = 6.25.dp

    /** 图标块到文字 */
    public val LeadingGap: Dp = 15.dp

    /** 符号图标（[MochiListLeadingStyle.Symbol]）占的宽度，跟着字号缩放（iOS 用 UIFontMetrics 缩放 24） */
    public val SymbolWidth: Dp = 24.dp

    /** 符号图标到文字 */
    public val SymbolGap: Dp = 16.dp

    /** 标题和副标题之间 */
    public val SupportingTextGap: Dp = 4.dp

    /** 行内内容上下的留白 */
    public val RowVerticalPadding: Dp = 15.dp

    /** 分组卡片的圆角（iOS 26 inset grouped：26） */
    public val SectionCorner: Dp = 26.dp

    /** 分组和分组之间的距离（页面级的节奏，大标题页面、演示页、面板内容都用它） */
    public val SectionSpacing: Dp = 28.dp

    /** 小标题到卡片 */
    public val HeaderGap: Dp = 10.dp

    /** 卡片到说明 */
    public val FooterGap: Dp = 8.dp

    @Composable
    public fun colors(
        container: Color = MochiDefaults.groupedContainer(),
        content: Color = MochiTheme.colorScheme.onSurface,
        secondary: Color = MochiTheme.colorScheme.onSurfaceVariant,
        divider: Color = LocalMochiControlPalette.current?.listDivider
            ?.takeUnless { it == Color.Unspecified }
            ?: MochiTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        highlight: Color = MochiTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        accent: Color = MochiTheme.colorScheme.primary,
        destructive: Color = MochiTheme.colorScheme.error,
        chevron: Color = LocalMochiControlPalette.current?.listChevron
            ?.takeUnless { it == Color.Unspecified }
            ?: MochiTheme.colorScheme.onSurface.copy(alpha = 0.3f),
    ): MochiListColors = MochiListColors(container, content, secondary, divider, highlight, accent, destructive, chevron)
}

/**
 * 分组卡片要不要按圆角裁切内容。G2 曲线这种任意路径的抗锯齿裁切，Skia 要在 CPU 上光栅化遮罩再上传到 GPU，
 * 卡片一移动（滚动）就重来一遍，很费电。所以平时不裁（行的内容本来就在卡片里面），
 * 只有行在按下高亮、左滑行露出操作时才裁（那时卡片不动，遮罩会被缓存）
 */
@Stable
internal class ListCardClip {
    var infos: List<ListItemInfo?> = emptyList()

    /** 子行已有逐帧高度动画时，当前卡片直接跟随；其他卡片不受影响。 */
    var heightAnimations by mutableIntStateOf(0)

    fun needed(): Boolean = SwipeRowGroup.openCount > 0 || infos.any { (it?.press?.value ?: 0f) > 0f }
}

// 卡片内部的布局协作：子行删除时只通知最近的卡片，避免全局关闭其他卡片的高度动画。
internal val LocalListCardClip = staticCompositionLocalOf<ListCardClip?> { null }

// Swipe wrappers and inner rows share grouping/highlight metadata and publish measured insets.
internal val LocalListRowGeometry = staticCompositionLocalOf<ListItemInfo?> { null }

/**
 * 每一项告诉分组：分隔线从哪开始画、自己现在按下高亮到什么程度（按着时上下两条线跟着高亮一起淡出、淡回来，
 * 和 iOS 一样；不是一按就消失、松手淡完才一下出现）
 */
@Stable
internal class ListItemInfo(val inset: Dp, val leading: Boolean = false, var end: Dp = 0.dp, val heightScale: (() -> Float)? = null) {
    /** 自带收起动画的行报告未折叠高度，避免分组把收起中的行误当成矮行并补出留白。 */
    var fullHeight: Int = 0

    /** Standard rows include corner safety in every row's own height, not only the shell edges. */
    var ownsVerticalInsets: Boolean = false

    /** 按下高亮的强度 0..1 */
    val press = Animatable(0f)

    /** 卡片在这一项上方 / 下方额外留的白（首末行离圆角远一点），按下高亮要一起盖住（px） */
    var extraTop by mutableFloatStateOf(0f)
    var extraBottom by mutableFloatStateOf(0f)

    // The visible option includes shell whitespace. Move its entire content together,
    // never an individual control; read during placement after the card resolves its edges.
    fun contentCenterOffset(): Float = (extraBottom - extraTop) / 2f

    // Natural measured control bounds. The card resolves one horizontal gutter for the group;
    // it never changes any row's vertical control placement.
    var controlBaseEnd: Int = -1
    var controlBudget: Int = 0
    var controlTop: Int = 0
    var controlBottom: Int = 0
    var resolvedControlEnd by mutableIntStateOf(-1)
}

/**
 * 左滑行一次只开一行（iOS）：开始拖一行时，让之前打开的那行收回。
 * 同一时间只有一个页面在交互，所以全局一个就够了。
 */
internal object SwipeRowGroup {
    private var openClose: (() -> Unit)? = null

    /** 现在有几行左滑行是打开的（分组卡片据此决定要不要按圆角裁切，见 ListCardClip） */
    var openCount by mutableIntStateOf(0)

    fun opened(close: () -> Unit) {
        val prev = openClose
        openClose = close
        if (prev != null && prev !== close) prev()
    }

    fun closed(close: () -> Unit) {
        if (openClose === close) openClose = null
    }
}

/**
 * 分组卡片里放自定义内容时，用它说明分隔线从哪开始（默认 16dp）。
 * MochiListRow 自己会设，不用再加。
 */
public fun Modifier.mochiListDivider(inset: Dp): Modifier = layoutId(ListItemInfo(inset))

/**
 * iOS 设置那种分组卡片（inset grouped）：一张圆角卡片里一行一行排下来，行与行之间画细分隔线。
 * - 分隔线左端和文字对齐（有图标的行从文字处开始），右端留同样的边
 * - 按住某一行时，它上下的分隔线隐藏
 * - 卡片接环境光：里面有东西被按下时，卡片边缘也被照亮一点
 * - 首行 / 末行依据文字、图标和控件的测量锚点分别留白；单行也遵守相同规则，不用整行高度的一半代替多行文字边界
 * - 里面可以放任意内容（信息卡、进度条），用 [mochiListDivider] 指定分隔线的起点
 *
 * @param header 卡片上方的小标题
 * @param footer 卡片下方的说明
 * @param animateHeight 内容高度默认平滑变化；已有逐帧高度动画时可关闭
 */
@Composable
public fun MochiListSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    colors: MochiListColors = MochiListDefaults.colors(),
    animateHeight: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) MochiListSectionHeader(header, color = colors.secondary)
        MochiListCard(colors = colors, animateHeight = animateHeight, content = content)
        if (footer != null) MochiListSectionFooter(footer, color = colors.secondary)
    }
}

/**
 * 分组卡片上方的小标题：iOS 26 是和正文一样大（17）、半粗、次要色，左边和行里的文字对齐（16dp），下面留 [MochiListDefaults.HeaderGap]。
 * 自己拼分组（卡片里放的不是 MochiListRow）时用它，样式和间距才和列表一致。
 */
@Composable
public fun MochiListSectionHeader(text: String, modifier: Modifier = Modifier, color: Color = MochiTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        // iOS 26 的分组小标题：和行文字一样大（17）、半粗、次要色
        style = MochiTheme.textStyles.navTitle,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(horizontal = MochiListDefaults.TextRowPadding)
            .padding(bottom = MochiListDefaults.HeaderGap),
    )
}

/** 分组卡片下方的说明：footnote、次要色，缩进和纯文字行一样（16dp），上面留 [MochiListDefaults.FooterGap] */
@Composable
public fun MochiListSectionFooter(text: String, modifier: Modifier = Modifier, color: Color = MochiTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MochiTheme.textStyles.footnote,
        color = color,
        modifier = modifier
            .padding(horizontal = MochiListDefaults.TextRowPadding)
            .padding(top = MochiListDefaults.FooterGap),
    )
}

/** 只有卡片、没有小标题和说明的 [MochiListSection]。内容高度默认平滑变化，已有高度动画时可关闭 [animateHeight]。 */
@Composable
public fun MochiListCard(
    modifier: Modifier = Modifier,
    colors: MochiListColors = MochiListDefaults.colors(),
    showDividers: Boolean = true,
    animateHeight: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = remember { MochiRoundedRectangle(MochiListDefaults.SectionCorner) }
    ListCardImpl(colors, showDividers, animateHeight, shape, modifier, content = content)
}

/**
 * 卡片的实现。懒加载分组（[mochiListSection]）里每一行是卡片的一段：[topEdge] / [bottomEdge] 只在首段 / 末段留首末白，
 * [link] 非空时在这一段底下画和下一段之间的分隔线（下一段那行按下时一起淡出）。
 */
@Composable
internal fun ListCardImpl(
    colors: MochiListColors,
    showDividers: Boolean,
    animateHeight: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    topEdge: Boolean = true,
    bottomEdge: Boolean = true,
    link: SectionSegmentLink? = null,
    content: @Composable () -> Unit,
) {
    // 拖动排序时分隔线淡出，不横在浮起的行上
    val dividerAlpha by animateFloatAsState(if (showDividers) 1f else 0f, tween(160), label = "dividers")
    val lines = remember { DividerLines() }
    val divider = colors.divider
    val cornerPx = with(LocalDensity.current) { MochiListDefaults.SectionCorner.toPx() }
    val maxEdgePx = with(LocalDensity.current) { MaxEdgeInset.toPx() }
    val padPx = with(LocalDensity.current) { MochiListDefaults.RowPadding.toPx() }
    val leadPx = with(LocalDensity.current) { MochiListDefaults.LeadingSize.toPx() }
    val clip = remember { ListCardClip() }
    Layout(
        content = { CompositionLocalProvider(LocalListCardClip provides clip, LocalListRowGeometry provides null, content = content) },
        modifier = modifier
            .fillMaxWidth()
            // 只在需要时裁切（见 ListCardClip）：滚动时不裁，不用每帧重做遮罩
            .graphicsLayer {
                this.shape = shape
                this.clip = clip.needed()
            }
            .background(colors.container, shape)
            .mochiAnimateHeight(shape, enabled = animateHeight && clip.heightAnimations == 0)
            // 分隔线画在内容上面：左滑行之类自带不透明底色的行不会把线盖住
            .drawWithContent {
                drawContent()
                val w = 0.5.dp.toPx()
                lines.items.forEach { l ->
                    // 分隔线右端到卡片边（iOS）
                    val end = size.width - (l.above?.end ?: 0.dp).toPx()
                    val hidden = max(l.above?.press?.value ?: 0f, l.below?.press?.value ?: 0f)
                    if (hidden >= 0.999f) return@forEach
                    val x = l.inset.toPx()
                    if (end > x &&
                        dividerAlpha > 0f
                    ) {
                        drawRect(divider, Offset(x, l.y - w / 2f), Size(end - x, w), alpha = (1f - hidden) * dividerAlpha)
                    }
                }
                // 懒加载分组：和下一段之间的线画在这一段底边，起点跟这一段最后一行的文字
                if (link != null && dividerAlpha > 0f) {
                    val above = lines.last
                    val hidden = max(above?.press?.value ?: 0f, link.next()?.press?.value ?: 0f)
                    val x = (above?.inset ?: MochiListDefaults.RowPadding).toPx()
                    val end = size.width - (above?.end ?: 0.dp).toPx()
                    if (hidden < 0.999f && end > x) {
                        drawRect(divider, Offset(x, size.height - w), Size(end - x, w), alpha = (1f - hidden) * dividerAlpha)
                    }
                }
            },
    ) { measurables, constraints ->
        val c = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(c) }
        val infos = measurables.map { it.layoutId as? ListItemInfo }
        clip.infos = infos
        val width = constraints.maxWidth

        // A multiline text block has two optical anchors; a centered switch and a leading tile
        // have different corner centers. Read actual child guides, including through swipe wrappers.
        fun edge(i: Int, bottom: Boolean): Int {
            if (infos[i]?.ownsVerticalInsets == true) return 0
            val p = placeables[i]
            val h = if (infos[i]?.heightScale != null) infos[i]!!.fullHeight else p.height
            val anchor = p[if (bottom) ConcentricBottom else ConcentricTop]
            if (anchor != AlignmentLine.Unspecified) {
                val distance = if (bottom) h - anchor else anchor
                return ceil((cornerPx - distance).coerceAtLeast(0f)).toInt()
            }
            // Arbitrary custom content keeps the legacy fallback; standard rows publish guides.
            val fallback = if (infos[i]?.leading == true) padPx - (h - leadPx) / 2f else cornerPx - h / 2f
            return fallback.coerceIn(0f, maxEdgePx).roundToInt()
        }
        val first = placeables.indexOfFirst { it.height > 0 }
        val last = placeables.indexOfLast { it.height > 0 }
        // 首末留白也跟随行的收起比例：两行变一行、最后一行变零时不会残留 8/16dp 再瞬间消失。
        val scales = placeables.indices.map { i ->
            infos[i]?.heightScale?.invoke()?.coerceIn(0f, 1f) ?: if (placeables[i].height > 0) 1f else 0f
        }
        val topEdges = placeables.indices.map { edge(it, false) }
        val bottomEdges = placeables.indices.map { edge(it, true) }
        val (naturalTop, naturalBottom) = listCardEdgeInsets(topEdges, scales, bottomEdges)
        val controls = infos.filterNotNull().filter { it.controlBaseEnd >= 0 }
        val topControlGaps = infos.map { info ->
            if (info != null && info.controlBaseEnd >= 0) naturalTop + info.controlTop else 0
        }
        val bottomControlGaps = infos.mapIndexed { i, info ->
            if (info != null && info.controlBaseEnd >= 0) {
                val h = if (info.heightScale != null) info.fullHeight else placeables[i].height
                naturalBottom + h - info.controlBottom
            } else {
                0
            }
        }
        val (topGap, bottomGap) = listCardEdgeInsets(topControlGaps, scales, bottomControlGaps)
        var absentBefore = 1f
        var topControlWeight = 0f
        infos.forEachIndexed { i, info ->
            if (info != null && info.controlBaseEnd >= 0) topControlWeight += scales[i] * absentBefore
            absentBefore *= 1f - scales[i]
        }
        var absentAfter = 1f
        var bottomControlWeight = 0f
        for (i in infos.indices.reversed()) {
            if (infos[i]?.controlBaseEnd?.let { it >= 0 } == true) bottomControlWeight += scales[i] * absentAfter
            absentAfter *= 1f - scales[i]
        }
        val controlEnd = if (controls.isEmpty()) {
            -1
        } else {
            max(max(topGap, bottomGap), controls.maxOf { it.controlBaseEnd })
                .coerceAtMost(controls.minOf { it.controlBudget })
        }
        // Keep all rows on their own centerline. Match boundary whitespace by extending the
        // shell and resolving one shared end gutter, instead of moving first/last controls.
        val top = if (!topEdge || infos.getOrNull(first)?.ownsVerticalInsets ==
            true
        ) {
            0
        } else {
            naturalTop + (controlEnd * topControlWeight - topGap).coerceAtLeast(0f).roundToInt()
        }
        val bottom = if (!bottomEdge || infos.getOrNull(last)?.ownsVerticalInsets ==
            true
        ) {
            0
        } else {
            naturalBottom + (controlEnd * bottomControlWeight - bottomGap).coerceAtLeast(0f).roundToInt()
        }
        infos.forEachIndexed { i, info ->
            info ?: return@forEachIndexed
            info.extraTop = if (i == first) top.toFloat() else 0f
            info.extraBottom = if (i == last) bottom.toFloat() else 0f
            info.resolvedControlEnd = if (info.controlBaseEnd >= 0) controlEnd else -1
        }
        val height = (placeables.sumOf { it.height } + top + bottom).coerceIn(constraints.minHeight, constraints.maxHeight)
        val list = ArrayList<DividerLine>()
        var y = top
        var prev = -1
        placeables.forEachIndexed { i, p ->
            if (p.height > 0) {
                if (prev >= 0) {
                    val above = infos[prev]
                    list += DividerLine(y.toFloat(), above?.inset ?: MochiListDefaults.RowPadding, above, infos[i])
                }
                prev = i
            }
            y += p.height
        }
        if (list != lines.items) lines.items = list
        lines.last = infos.getOrNull(last)
        link?.publish(infos.getOrNull(first))
        layout(width, height) {
            var at = top
            placeables.forEach {
                it.place(0, at)
                at += it.height
            }
        }
    }
}

private data class DividerLine(val y: Float, val inset: Dp, val above: ListItemInfo?, val below: ListItemInfo?)

@Stable
private class DividerLines {
    var items by mutableStateOf(emptyList<DividerLine>())

    /** 最后一行（懒加载分组画段间分隔线用） */
    var last: ListItemInfo? = null
}

/** 文字到行尾（iOS 内容和附件之间 16） */
internal val MainToTailGap = 16.dp

/** 卡片给首末行多留的白最多多少 */
private val MaxEdgeInset = 8.dp
