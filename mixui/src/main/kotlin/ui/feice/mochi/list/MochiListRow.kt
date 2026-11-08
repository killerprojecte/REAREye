// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.parts.MochiGlyphs
import ui.feice.mochi.shape.ConcentricTop
import ui.feice.mochi.shape.mochiConcentricPoint
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.LocalMochiSymbolWeight
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiIcon
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.rememberCapBand

/**
 * 一行（iOS 26 的列表单元格）：
 * - 行首图标（可选，[leadingStyle] 选「设置」式图标块还是无底块的符号图标）、标题 + 副标题；保留主题字号，长标题单行省略，副标题最多两行
 * - 行尾：灰色的值、转圈加载、自定义控件（开关等）、蓝色对勾、「›」
 * - 按下：整行灰底高亮（滚动时不闪：稍等一下才亮），松手后淡出
 *
 * 尺寸照 iOS 26：最小高度 = 正文行高 + 上下各 15，左右边距 16，图标块 28、到文字 15，符号图标占 24 宽、到文字 16，
 * 标题到副标题 4；图标、文字块、行尾都在整行里垂直居中，值和标题共用基线。每一行（包括首末行）高度规则相同，
 * 卡片不给首末行另外加白。
 *
 * @param value 行尾灰色的值（「WEP」「关闭」）
 * @param chevron 行尾的「›」，表示点进去是下一页
 * @param checked 行尾的对勾（单选列表）
 * @param loading 行尾转圈，表示这一行在加载
 * @param trailing 行尾自定义内容，比如开关
 * @param destructive 标题用警示色（「退出登录」）
 * @param titleTrailing 标题右侧的紧凑状态标记，与标题垂直居中；日期等行尾值仍靠右
 * @param status 右下角的具名状态 chip，与副标题底部对齐；无副标题时独占标题下方一行
 * @param leadingStyle [leading] 是图标块还是符号图标，决定它占的位置和到文字的距离
 */
@Composable
public fun MochiListRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    checked: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    colors: MochiListColors = MochiListDefaults.colors(),
    titleTrailing: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    leadingStyle: MochiListLeadingStyle = MochiListLeadingStyle.Tile,
) {
    ListRowImpl(
        title, modifier, onClick, subtitle, value, leading, trailing, chevron, checked, loading, enabled, destructive, colors,
        titleTrailing, status, leadingStyle,
    )
}

/**
 * [MochiListRow] 的实现。[prefix] 是行首最前面的一段（编辑模式的勾选圈，[MochiSelectableRow] 用）：
 * 它属于这一行，按下高亮、点击区域、分隔线起点都把它算进去；[prefixWidth] 是它展开后的宽度（算分隔线起点用）。
 */
@Composable
internal fun ListRowImpl(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    checked: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    colors: MochiListColors = MochiListDefaults.colors(),
    titleTrailing: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    leadingStyle: MochiListLeadingStyle = MochiListLeadingStyle.Tile,
    prefixWidth: Dp = 0.dp,
    prefix: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val body = MochiTheme.textStyles.body
    // 符号图标的位置跟着字号缩放（iOS 用 UIFontMetrics 缩放 24 宽，高取正文行高）
    val symbol = leading != null && leadingStyle == MochiListLeadingStyle.Symbol
    val symbolWidth = with(density) { MochiListDefaults.SymbolWidth.value.sp.toDp() }
    val symbolHeight = with(density) { body.lineHeight.takeIf { it.isSp }?.toDp() ?: MochiListDefaults.SymbolWidth }
    val leadWidth = when {
        leading == null -> 0.dp
        symbol -> symbolWidth + MochiListDefaults.SymbolGap
        else -> MochiListDefaults.LeadingSize + MochiListDefaults.LeadingGap
    }
    // 分隔线从文字起点开始，右端到卡片边
    val inset = prefixWidth + MochiListDefaults.RowPadding + leadWidth
    val ownInfo = remember(inset, leading != null) { ListItemInfo(inset, leading != null) }
    val info = LocalListRowGeometry.current ?: ownInfo
    val highlight = info.press
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val clickable = onClick != null && enabled
    val alpha = if (enabled) 1f else MochiDefaults.DisabledAlpha
    val band = rememberCapBand(body)
    val hasSupporting = subtitle != null || status != null
    val titleStyle = if (hasSupporting) body.listSupportingStyle() else body
    val supportingStyle = MochiTheme.textStyles.subhead.listSupportingStyle()
    val inlineValue = value != null && hasSupporting
    val glyphModifier = Modifier.mochiConcentricPoint()
    // 最小高度 = 正文行高 + 上下留白（字号变大时一起变高）
    val minHeight = maxOf(MochiListDefaults.RowMinHeight, symbolHeight + MochiListDefaults.RowVerticalPadding * 2)
    Layout(
        modifier = modifier
            .layoutId(info)
            .fillMaxWidth()
            .drawBehind {
                if (highlight.value > 0f) {
                    drawRect(
                        colors.highlight,
                        Offset(0f, -info.extraTop),
                        Size(size.width, size.height + info.extraTop + info.extraBottom),
                        alpha = highlight.value,
                    )
                }
            }
            .then(
                if (clickable) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    // 稍等一下再亮：手指是来滚动的话，还没亮就被滚动接走了，不会闪一下
                                    var lit: Job? = scope.launch {
                                        delay(HighlightDelayMillis)
                                        highlight.snapTo(1f)
                                    }
                                    val released = tryAwaitRelease()
                                    lit?.cancel()
                                    lit = null
                                    if (released) {
                                        highlight.snapTo(1f)
                                    }
                                    scope.launch {
                                        highlight.animateTo(0f, tween(if (released) 350 else 120))
                                    }
                                },
                                onTap = { currentOnClick?.invoke() },
                            )
                        }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            if (checked) selected = true
                            onClick {
                                currentOnClick?.invoke()
                                true
                            }
                        }
                } else {
                    Modifier.semantics(mergeDescendants = true) {
                        if (!enabled) disabled()
                    }
                },
            ),
        content = {
            Row(Modifier.layoutId(ListRowSlot.Main), verticalAlignment = Alignment.CenterVertically) {
                prefix?.invoke()
                if (leading != null) {
                    if (symbol) {
                        // 符号图标：强调色，描边粗细跟标题字重（iOS 的符号按旁边文字的字体取字重）
                        Box(
                            Modifier
                                .size(symbolWidth, symbolHeight)
                                .graphicsLayer { this.alpha = alpha },
                            contentAlignment = Alignment.Center,
                        ) {
                            CompositionLocalProvider(
                                LocalContentColor provides colors.accent,
                                LocalMochiSymbolWeight provides (titleStyle.fontWeight ?: FontWeight.Normal),
                            ) { leading() }
                        }
                        Spacer(Modifier.width(MochiListDefaults.SymbolGap))
                    } else {
                        // 禁用时图标块和文字一起变淡（iOS）
                        Box(
                            Modifier
                                .size(MochiListDefaults.LeadingSize)
                                .graphicsLayer { this.alpha = alpha },
                            contentAlignment = Alignment.Center,
                        ) { leading() }
                        Spacer(Modifier.width(MochiListDefaults.LeadingGap))
                    }
                }
                // 右侧文字和标题共用基线；有副标题或状态时，值缩小并放进标题行
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MochiListDefaults.SupportingTextGap)) {
                    Row {
                        Row(
                            Modifier.weight(1f).alignByBaseline(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ListText(
                                title,
                                style = titleStyle,
                                color = (if (destructive) colors.destructive else colors.content).copy(alpha = alpha),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (titleTrailing != null) {
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.graphicsLayer { this.alpha = alpha }) {
                                    CompositionLocalProvider(LocalContentColor provides colors.content) { titleTrailing() }
                                }
                            }
                        }
                        if (inlineValue) {
                            // 值换掉时模糊替换（全局规范：内容变化不瞬切）
                            ListBlurText(
                                value!!,
                                style = supportingStyle,
                                color = colors.secondary.copy(alpha = alpha),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .alignByBaseline(),
                            )
                        }
                    }
                    if (subtitle != null || status != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            if (subtitle != null) {
                                ListText(
                                    subtitle,
                                    modifier = Modifier.weight(1f),
                                    style = supportingStyle,
                                    color = colors.secondary.copy(alpha = alpha),
                                    maxLines = 2,
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (status != null) {
                                Box(Modifier.padding(start = 8.dp).graphicsLayer { this.alpha = alpha }) {
                                    CompositionLocalProvider(LocalContentColor provides colors.content) { status() }
                                }
                            }
                        }
                    }
                }
            }
            if (value != null && !inlineValue) {
                ListBlurText(
                    value,
                    style = body,
                    color = colors.secondary.copy(alpha = alpha),
                    modifier = Modifier.layoutId(ListRowSlot.Value).widthIn(max = 168.dp),
                )
            }
            if (loading) {
                MochiActivityIndicator(glyphModifier.layoutId(ListRowSlot.Indicator), color = colors.secondary)
            }
            if (trailing != null) {
                Box(Modifier.layoutId(ListRowSlot.Control).graphicsLayer { this.alpha = alpha }) {
                    CompositionLocalProvider(LocalContentColor provides colors.content) { trailing() }
                }
            }
            if (checked) {
                MochiGlyphs.Check(colors.accent.copy(alpha = alpha), glyphModifier.layoutId(ListRowSlot.Check))
            }
            if (chevron) {
                ChevronGlyph(
                    colors.chevron.copy(alpha = colors.chevron.alpha * alpha),
                    band.capHeight,
                    glyphModifier.layoutId(ListRowSlot.Chevron),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val tail = measurables.drop(1).map { it.layoutId as ListRowSlot to it.measure(loose) }
        // 每一行都自己带上下留白，卡片不给首末行另外加；分隔线跟文字起点，右端到卡片边
        info.ownsVerticalInsets = true
        info.controlBaseEnd = -1
        info.end = 0.dp
        val start = MochiListDefaults.RowPadding.roundToPx()
        val end = MochiListDefaults.RowPadding.roundToPx()
        val gap = TailGap.roundToPx()
        val tailWidth = tail.sumOf { it.second.width } + gap * (tail.size - 1).coerceAtLeast(0)
        val mainGap = if (tail.isEmpty()) 0 else MainToTailGap.roundToPx()
        val available = (width - start - end - tailWidth - mainGap).coerceAtLeast(0)
        val main = measurables[0].measure(loose.copy(minWidth = available, maxWidth = available))
        val vertical = MochiListDefaults.RowVerticalPadding.roundToPx()
        val tallestTail = tail.maxOfOrNull { it.second.height } ?: 0
        val height = constraints.constrainHeight(maxOf(minHeight.roundToPx(), main.height + vertical * 2, tallestTail + vertical))
        val mainY = (height - main.height) / 2f
        val baseline = main[FirstBaseline].takeIf { it != AlignmentLine.Unspecified }?.let { mainY + it }
        val positionedTail = tail.map { (slot, p) ->
            val centered = (height - p.height) / 2f
            // 值和标题共用基线；导航 / 选中 / 加载这些符号按墨迹中心在整行居中
            val y = when (slot) {
                ListRowSlot.Value -> {
                    val valueBaseline = p[FirstBaseline]
                    if (baseline != null && valueBaseline != AlignmentLine.Unspecified) baseline - valueBaseline else centered
                }

                ListRowSlot.Indicator, ListRowSlot.Check, ListRowSlot.Chevron -> {
                    val inkCenter = p[ConcentricTop]
                    if (inkCenter != AlignmentLine.Unspecified) height / 2f - inkCenter else centered
                }

                else -> centered
            }
            p to y
        }
        layout(width, height) {
            val contentY = info.contentCenterOffset()
            main.placeRelativeWithLayer(start, 0) { translationY = mainY + contentY }
            var x = width - end - tailWidth
            positionedTail.forEach { (p, y) ->
                p.placeRelativeWithLayer(x.coerceAtLeast(0), 0) { translationY = y + contentY }
                x += p.width + gap
            }
        }
    }
}

/**
 * 文字式的操作行（「添加指纹…」「更改密码」那种）：强调色文字，没有图标和行尾。
 * iOS 26 里这种行和普通行一样靠左；单独成组的「退出登录」那种可以 [centered]。
 */
@Composable
public fun MochiListButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    centered: Boolean = false,
    colors: MochiListColors = MochiListDefaults.colors(),
) {
    val info = remember { ListItemInfo(MochiListDefaults.TextRowPadding) }
    Box(modifier.fillMaxWidth().layoutId(info)) {
        CompositionLocalProvider(LocalListRowGeometry provides info) {
            MochiListRow(
                title = "",
                onClick = onClick,
                colors = colors,
            )
        }
        Text(
            text,
            style = MochiTheme.textStyles.body,
            color = if (destructive) colors.destructive else colors.accent,
            modifier = (
                if (centered) {
                    Modifier.align(Alignment.Center)
                } else {
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = MochiListDefaults.TextRowPadding)
                }
                ).layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { p.placeWithLayer(0, 0) { translationY = info.contentCenterOffset() } }
            },
        )
    }
}

@Composable
private fun ChevronGlyph(color: Color, capHeight: Float, modifier: Modifier = Modifier) {
    val h = with(LocalDensity.current) { capHeight.toDp() }
    // iOS 的 › ：约 7×12、半粗、三级文字色
    // 墨迹（连同圆头）正好是大写字母那么高，宽约 0.6 倍
    MochiIcon("list.chevron", modifier.size(width = h * 0.6f, height = h), tint = color) { slot ->
        Canvas(slot) {
            val w = 1.8.dp.toPx()
            val p = Path().apply {
                moveTo(w / 2f, w / 2f)
                lineTo(size.width - w / 2f, size.height / 2f)
                lineTo(w / 2f, size.height - w / 2f)
            }
            drawPath(p, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private enum class ListRowSlot { Main, Value, Indicator, Control, Check, Chevron }

/** 行尾几样东西之间（值和 ›） */
private val TailGap = 8.dp

// Trim only the line-height leading at block edges, not font ink or wrapped-line spacing.
// Keeping the theme's font and size avoids solving compact spacing by shrinking readable text.
private fun TextStyle.listSupportingStyle(): TextStyle = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

private const val HighlightDelayMillis = 70L
