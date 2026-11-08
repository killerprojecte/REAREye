// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ui.feice.mochi.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import ui.feice.mochi.shape.MochiContinuity

/**
 * 懒加载列表里相邻两项之间的间距（`LazyColumn(verticalArrangement = Arrangement.spacedBy(x))` 的 x）。
 * [mochiListSection] 的各行要跨过这段间距连成一张卡片，所以得知道它是多少。
 * [ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold] / [ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold]
 * 自己会提供；自己写的 LazyColumn 用了 spacedBy 时，用 CompositionLocalProvider 把同一个值提供进去。
 */
public val LocalMochiListItemSpacing: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }

/** [mochiListSection] 里加行的地方 */
public interface MochiListSectionScope {
    /** 一行（里面通常是一个 [MochiListRow]；放几个也行，它们算同一段，分隔线照常画） */
    public fun row(key: Any? = null, contentType: Any? = null, content: @Composable () -> Unit)

    /** [count] 行，第 index 行的内容由 [content] 给出 */
    public fun rows(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = { null },
        content: @Composable (index: Int) -> Unit,
    )
}

/** 一组数据各占一行 */
public inline fun <T> MochiListSectionScope.rows(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    crossinline contentType: (item: T) -> Any? = { null },
    crossinline content: @Composable (item: T) -> Unit,
): Unit = rows(
    count = items.size,
    key = if (key != null) { index: Int -> key(items[index]) } else null,
    contentType = { index: Int -> contentType(items[index]) },
) { index -> content(items[index]) }

/**
 * 懒加载版的 [MochiListSection]：小标题 + 一张分组卡片 + 说明，但卡片里**每一行是懒加载列表的一项**，
 * 只有屏幕附近的行会组合，几百行的分组也不卡。看起来和 [MochiListSection] 一样：首行上圆角、末行下圆角，
 * 行与行之间的分隔线从文字起点开始、按下时上下两条线一起淡出。
 *
 * ```
 * MochiLazyCompactTitleScaffold(title = "来源") {
 *     mochiListSection("sources", header = "全部来源") {
 *         rows(sources, key = { it.id }) { s -> MochiListRow(s.name, value = s.status, chevron = true, onClick = { open(s) }) }
 *     }
 * }
 * ```
 *
 * 不要在 LazyColumn 里一行套一个 MochiListCard（那是一串小卡片，不是分组），也不要把小标题单独写成一项
 * （会多出一段列表间距，小标题离卡片 38dp 而不是 10dp）。
 *
 * 行跨过列表的项间距连在一起，间距从 [LocalMochiListItemSpacing] 读；Mochi 的懒加载标题骨架已经提供了。
 *
 * @param key 分组的标识，同一个列表里唯一（各行的列表 key 由它和行的 key 拼成字符串，行的 key 要能转成有区别的字符串）
 * @param header 卡片上方的小标题
 * @param footer 卡片下方的说明
 * @param colors 不给就用 [MochiListDefaults.colors]
 */
public fun LazyListScope.mochiListSection(
    key: Any,
    header: String? = null,
    footer: String? = null,
    colors: MochiListColors? = null,
    content: MochiListSectionScope.() -> Unit,
) {
    val rows = SectionRows().apply(content)
    val total = rows.total
    if (total == 0) {
        if (header != null || footer != null) {
            item(key = "mochi-section:$key") {
                val c = colors ?: MochiListDefaults.colors()
                Column(Modifier.fillMaxWidth()) {
                    if (header != null) MochiListSectionHeader(header, color = c.secondary)
                    if (footer != null) MochiListSectionFooter(footer, color = c.secondary)
                }
            }
        }
        return
    }
    rows.intervals.forEach { iv ->
        items(
            count = iv.count,
            key = { i -> "mochi-section:$key:" + (iv.key?.invoke(i)?.toString() ?: "#${iv.start + i}") },
            contentType = { i -> SectionRowType(iv.contentType(i)) },
        ) { i ->
            val index = iv.start + i
            SectionSegment(
                section = key,
                index = index,
                first = index == 0,
                last = index == total - 1,
                header = header.takeIf { index == 0 },
                footer = footer.takeIf { index == total - 1 },
                colors = colors,
            ) { iv.content(i) }
        }
    }
}

private data class SectionRowType(val type: Any?)

private class SectionInterval(
    val start: Int,
    val count: Int,
    val key: ((Int) -> Any)?,
    val contentType: (Int) -> Any?,
    val content: @Composable (Int) -> Unit,
)

private class SectionRows : MochiListSectionScope {
    val intervals = ArrayList<SectionInterval>()
    var total = 0

    override fun row(key: Any?, contentType: Any?, content: @Composable () -> Unit) {
        rows(1, if (key != null) { _ -> key } else null, { contentType }) { content() }
    }

    override fun rows(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, content: @Composable (Int) -> Unit) {
        if (count <= 0) return
        intervals += SectionInterval(total, count, key, contentType, content)
        total += count
    }
}

@Composable
private fun SectionSegment(
    section: Any,
    index: Int,
    first: Boolean,
    last: Boolean,
    header: String?,
    footer: String?,
    colors: MochiListColors?,
    content: @Composable () -> Unit,
) {
    val c = colors ?: MochiListDefaults.colors()
    val r = MochiListDefaults.SectionCorner
    val shape = remember(first, last) {
        ContinuousRoundedRectangle(
            if (first) r else 0.dp,
            if (first) r else 0.dp,
            if (last) r else 0.dp,
            if (last) r else 0.dp,
            MochiContinuity,
        )
    }
    val spacing = LocalMochiListItemSpacing.current
    val link = remember(section, index) { SectionSegmentLink(section, index) }
    DisposableEffect(link) { onDispose { link.release() } }
    Column(Modifier.fillMaxWidth()) {
        if (header != null) MochiListSectionHeader(header, color = c.secondary)
        ListCardImpl(
            // 不是末行：报告的高度少一段项间距，画出来的部分正好盖住和下一行之间那段间距，两行连成一张卡片
            modifier = if (last) Modifier else Modifier.coverItemSpacing(spacing),
            colors = c,
            showDividers = true,
            animateHeight = true,
            shape = shape,
            topEdge = first,
            bottomEdge = last,
            link = if (last) null else link,
            content = content,
        )
        if (footer != null) MochiListSectionFooter(footer, color = c.secondary)
    }
}

private fun Modifier.coverItemSpacing(spacing: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val cut = spacing.roundToPx().coerceIn(0, p.height)
    layout(p.width, p.height - cut) { p.place(0, 0) }
}

/** 懒加载分组里相邻两段互相找到对方：段间分隔线要跟着下一段第一行的按下一起淡出 */
internal class SectionSegmentLink(private val section: Any, private val index: Int) {
    private val self = SegmentKey(section, index)
    private var published: ListItemInfo? = null

    fun publish(info: ListItemInfo?) {
        if (info === published) return
        published = info
        if (info == null) Links.remove(self) else Links[self] = info
    }

    fun next(): ListItemInfo? = Links[SegmentKey(section, index + 1)]

    fun release() {
        if (published != null && Links[self] === published) Links.remove(self)
        published = null
    }

    private data class SegmentKey(val section: Any, val index: Int)

    private companion object {
        // 只在主线程的测量 / 绘制里读写
        val Links = HashMap<SegmentKey, ListItemInfo>()
    }
}
