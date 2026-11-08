// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import ui.feice.mochi.utils.smoothstep

/**
 * 页面返回栈。路由是 String，保存在 rememberSaveable 里（旋转屏幕、进程重建后还在）。
 * 配合 [MochiNavHost] 显示；用 [rememberMochiNavigator] 创建。
 *
 * ```kotlin
 * val nav = rememberMochiNavigator("home")
 * MochiNavHost(nav) { route -> … }
 * nav.push("detail", from = "card-1")   // 从带 mochiNavSource("card-1") 的卡片「打开应用」式展开
 * nav.pop()
 * ```
 */
@Stable
public class MochiNavigator internal constructor(root: String, restored: List<MochiNavEntry>?, nextId: Int) {
    internal val entries = mutableStateListOf<MochiNavEntry>()
    internal var nextId: Int = nextId
        private set

    init {
        if (restored.isNullOrEmpty()) entries += MochiNavEntry(this.nextId++, root, null, opened = true) else entries += restored
    }

    /** 当前返回栈（不含正在关闭的页） */
    public val backStack: List<String> get() = entries.filter { !it.closing }.map { it.route }

    /** 最上面（没在关闭）的路由 */
    public val current: String get() = entries.last { !it.closing }.route

    /** 能不能返回（栈里除了根还有页） */
    public val canPop: Boolean get() = entries.count { !it.closing } > 1

    /** 有页面正在打开、拖动返回或关闭 */
    public val isTransitioning: Boolean get() = entries.any { it.inTransition }

    /**
     * 打开一页。
     * @param from 来源元素的 key（[mochiNavSource]）。给了就从那个元素「打开应用」式地长成整页，关闭时缩回去；
     *   不给就是 iOS 的侧推转场（新页从右边推进来，下层页往左退一点）
     */
    public fun push(route: String, from: String? = null) {
        // 正在退出的页、又要进同一页（同一个来源）：直接让它掉头，从当前位置和速度连续地回来，不新开一页
        val leaving = entries.lastOrNull()?.takeIf { it.closing }
        if (leaving != null && leaving.source == from && leaving.route == route) {
            leaving.closing = false
            return
        }
        // 侧推的页正在退出、又推入别的页：两个转场同时进行（退出的那页接着滑走，新页从右边进来），不瞬间删掉；
        // 涉及「从来源打开」的页没法同时变形，正在关闭的页直接结束
        if (from != null || entries.any { it.closing && it.source != null }) entries.removeAll { it.closing }
        pendingPops = 0
        entries += MochiNavEntry(nextId++, route, from, opened = false)
    }

    /**
     * 返回上一页（带转场）。栈里只剩根时返回 false。
     *
     * 连续返回不会被吞掉、也不会跳：上一页还在侧推退出时，下面这页直接跟着一起退出（两页同时滑走，位置连续）；
     * 涉及「从来源打开」的页（没法和别的页同时变形）时排队，上一页一关完就接着关
     */
    public fun pop(): Boolean {
        val open = entries.filter { !it.closing }
        if (open.size <= 1) return false
        val leaving = entries.lastOrNull()?.takeIf { it.closing }
        if (leaving != null && (leaving.source != null || open.last().source != null)) {
            pendingPops++
            return true
        }
        open.last().closing = true
        return true
    }

    /** 排队中的返回次数（见 [pop]） */
    internal var pendingPops = 0

    /** 一页关完后调用：有排队的返回就接着执行 */
    internal fun onClosed() {
        if (pendingPops > 0) {
            pendingPops--
            pop()
        }
    }

    /**
     * [entry] 被上面那页盖住了多少：0 = 上面没页或没打开，1 = 完全盖住。跟着转场进度和返回手势走
     */
    internal fun coverOf(entry: MochiNavEntry): Float {
        val above = above(entry) ?: return 0f
        return if (above.source == null) {
            slideProgress(above).coerceIn(0f, 1f)
        } else {
            // 拖动返回时一边拖一边露出来（松手就回到这一页）
            (above.progress.value.coerceIn(0f, 1f) * (1f - CoverBackReveal * above.back.value)).coerceIn(0f, 1f)
        }
    }

    /** 栈里这一页上面那一页 */
    internal fun above(entry: MochiNavEntry): MochiNavEntry? {
        val i = entries.indexOf(entry)
        return if (i >= 0 && i < entries.size - 1) entries[i + 1] else null
    }

    /**
     * 返回到栈里第 [index] 页（0 = 根，按 [backStack] 的顺序）。中间的页直接移出，只有最上面那页带转场退出。
     * 返回 false：index 不在栈里，或者已经在那一页
     */
    public fun popTo(index: Int): Boolean {
        entries.removeAll { it.closing }
        if (index < 0 || index >= entries.size - 1) return false
        val top = entries.last()
        // 中间的页移走；下面那页换成目标页时，它的状态由 SaveableStateHolder 恢复
        while (entries.size > index + 2) entries.removeAt(index + 1)
        top.closing = true
        return true
    }

    internal fun remove(entry: MochiNavEntry) {
        entries.remove(entry)
    }

    internal fun under(entry: MochiNavEntry): MochiNavEntry? {
        val i = entries.indexOf(entry)
        return if (i > 0) entries[i - 1] else null
    }

    // —— 来源与共享元素登记（布局 / 绘制时读，不需要触发重组）——

    private val sources = HashMap<String, SourceHandle>()
    private val elements = HashMap<String, MutableList<SharedElementHandle>>()

    internal fun registerSource(h: SourceHandle) {
        sources[h.key] = h
    }

    internal fun unregisterSource(h: SourceHandle) {
        if (sources[h.key] === h) sources.remove(h.key)
    }

    internal fun source(key: String): SourceHandle? = sources[key]?.takeIf { it.shown() }

    /**
     * 这个来源对应的页面还在栈里（打开着或正在转场），来源自己要藏起来：
     * 转场里由页面容器接手画它录下来的画面（跟着容器一起缩放、淡出），关闭落地时正好和它原样重合
     */
    internal fun isSourceActive(key: String): Boolean = entries.any { it.source == key }

    internal fun registerElement(h: SharedElementHandle) {
        elements.getOrPut(h.key) { ArrayList(2) } += h
    }

    internal fun unregisterElement(h: SharedElementHandle) {
        val list = elements[h.key] ?: return
        list.remove(h)
        if (list.isEmpty()) elements.remove(h.key)
    }

    internal fun element(key: String, entry: MochiNavEntry?): SharedElementHandle? =
        if (entry == null) null else elements[key]?.firstOrNull { it.entry === entry && it.shown() }

    internal fun elementsOf(entry: MochiNavEntry): List<SharedElementHandle> =
        elements.values.flatMap { list -> list.filter { it.entry === entry && it.shown() } }

    // —— 遮挡物（顶栏、底栏、滚动边缘渐隐，见 mochiNavOccluder）：从来源打开时先盖在变形的容器上再淡掉 ——

    private val occluders = ArrayList<OccluderHandle>()

    internal fun registerOccluder(h: OccluderHandle) {
        occluders += h
    }

    internal fun unregisterOccluder(h: OccluderHandle) {
        occluders -= h
    }

    internal fun occludersOf(entry: MochiNavEntry): List<OccluderHandle> = occluders.filter { it.entry === entry && it.shown() }

    // —— 收起的栏（mochiHideWhenCovered）：从来源打开时由 MochiNavHost 代画，下层页内容才能整页离屏缓存 ——

    private val hidden = ArrayList<HideHandle>()

    internal fun registerHidden(h: HideHandle) {
        hidden += h
    }

    internal fun unregisterHidden(h: HideHandle) {
        hidden -= h
    }

    internal fun hiddenOf(entry: MochiNavEntry): List<HideHandle> = hidden.filter { it.entry === entry && it.shown() }

    /**
     * 这一页正被一个「从来源打开」的页盖着、在转场中：页里收起的栏不在页面里画、由 MochiNavHost 代画（位移照常），
     * 页面内容这段时间不变，可以整页画进离屏纹理只缩放
     */
    internal fun isCarriedUnderSource(entry: MochiNavEntry): Boolean {
        val above = above(entry) ?: return false
        return above.source != null && above.inTransition
    }

    /**
     * 这一页在转场中，或被上面的页盖着：页里取背后亮度的效果（玻璃标签栏、滚动边缘）这时不回读屏幕。
     * 转场每帧都在变，读到的是半路的画面，回读还会让渲染线程等 GPU，正好卡在动画上
     */
    internal fun isMovingOrCovered(entry: MochiNavEntry): Boolean = entry.inTransition || above(entry) != null

    /** [c] 在这一页某个收起的栏里、而栏正由 MochiNavHost 代画时，栏此刻往下滑了多少（px）；否则 0 */
    internal fun carriedShift(entry: MochiNavEntry, c: LayoutCoordinates): Float {
        if (!isCarriedUnderSource(entry)) return 0f
        for (h in hiddenOf(entry)) {
            val hc = h.coords ?: continue
            var p: LayoutCoordinates? = c
            while (p != null) {
                if (p === hc) return hideOffset(h)
                p = p.parentCoordinates
            }
        }
        return 0f
    }

    /** 收起的栏此刻往下滑了多少（px） */
    internal fun hideOffset(h: HideHandle): Float {
        val e = h.entry ?: return 0f
        // 侧推：照 iOS 的 hidesBottomBarWhenPushed，栏留在下层页里、跟着它往左退，被新页盖住；不往下滑
        if (above(e)?.source == null) return 0f
        return (h.height + h.below) * smoothstep(0f, HideDoneAt, coverOf(e))
    }

    // —— 跨页共享的顶栏（形状和内容，见 MochiNavBarItem.kt）——

    private val bars = ArrayList<BarHandle>()

    /** 登记表变了就加一，读它的绘制（按钮自己、MochiNavHost 的最上层）跟着重画 */
    private var barsVersion by mutableIntStateOf(0)

    internal fun registerBar(h: BarHandle) {
        bars += h
        barsVersion++
    }

    internal fun unregisterBar(h: BarHandle) {
        bars -= h
        barsVersion++
    }

    internal fun barsOf(entry: MochiNavEntry): List<BarHandle> {
        barsVersion
        return bars.filter { it.entry === entry && it.shown() }
    }

    /**
     * 顶栏正在变形的一串页：从栈顶往下，连续的侧推转场中的页，再加上它们下面那一页（起点）。
     * 快速连续推入 / 返回时好几页同时在动，顶栏从起点开始按每一页的进度依次变过去，只画一套、全程连续。
     * 栈顶没在侧推转场时为空
     */
    internal fun slideChain(): List<MochiNavEntry> {
        val n = entries.size
        if (n < 2) return emptyList()
        fun sliding(e: MochiNavEntry) = e.source == null && e.inTransition
        if (!sliding(entries[n - 1])) return emptyList()
        var i = n - 1
        while (i >= 1 && sliding(entries[i])) i--
        return entries.subList(i, n).toList()
    }

    /** 这个顶栏按钮所在的页在变形的那一串里：由 MochiNavHost 留在顶栏原位画，它自己的页里不画 */
    internal fun isBarCarried(h: BarHandle): Boolean {
        val e = h.entry ?: return false
        val page = e.contentCoords ?: return false
        if (h.restRect(page) == null) return false
        return slideChain().any { it === e }
    }

    /** 页面里的共享元素在卡片那边有没有同 key 的另一半（有才由转场接管绘制） */
    internal fun hasCounterpart(h: SharedElementHandle): Boolean {
        val e = h.entry ?: return false
        if (e.source == null) return false
        return element(h.key, under(e)) != null
    }

    public companion object {
        /** 当前所在 [MochiNavHost] 的导航器，不在里面时是 null。页面里返回：`MochiNavigator.current?.pop()` */
        public val current: MochiNavigator?
            @Composable @ReadOnlyComposable
            get() = LocalMochiNavigator.current

        internal fun saver(root: String): Saver<MochiNavigator, Any> = listSaver<MochiNavigator, String>(
            save = { nav ->
                buildList {
                    add(nav.nextId.toString())
                    nav.entries.filter { !it.closing }.forEach {
                        add(it.id.toString())
                        add(it.route)
                        add(it.source ?: "")
                    }
                }
            },
            restore = { list ->
                val next = list[0].toInt()
                val restored = list.drop(1).chunked(3).map { (id, route, source) ->
                    MochiNavEntry(id.toInt(), route, source.ifEmpty { null }, opened = true)
                }
                MochiNavigator(root, restored, next)
            },
        )
    }
}

/** 创建并记住一个导航器，root 是根路由（不能返回的那一页） */
@Composable
public fun rememberMochiNavigator(root: String): MochiNavigator =
    rememberSaveable(root, saver = MochiNavigator.saver(root)) { MochiNavigator(root, null, 0) }

/** 当前 [MochiNavHost] 的导航器 */
internal val LocalMochiNavigator = staticCompositionLocalOf<MochiNavigator?> { null }

/** 当前内容所在的页 */
internal val LocalMochiNavEntry = staticCompositionLocalOf<MochiNavEntry?> { null }

/** 栈里的一页和它的转场状态 */
@Stable
internal class MochiNavEntry(val id: Int, val route: String, val source: String?, opened: Boolean) {
    /** 打开进度：0 = 缩在来源处，1 = 整屏（弹簧，可能略微过冲）。打开和关闭是同一条弹簧，随时掉头 */
    val progress = Animatable(if (opened) 1f else 0f)

    /**
     * 从来源打开的页：位置的进度（页面中心从来源移到屏幕中心）。和 [progress]（大小、圆角、淡入淡出）分开，
     * 用一根带一点回弹的弹簧，到位时轻轻冲过一点再落回；侧推页不用它
     */
    val move = Animatable(if (opened) 1f else 0f)

    /** 侧推页：预测性返回松手那一刻的速度（打开进度 / 秒，往关闭方向为负），关闭动画从这个速度接着走 */
    var releaseVelocity = 0f

    /** 预测性返回的拖动进度 0..1 */
    val back = Animatable(0f)

    /** 从哪条边滑进来（BackEventCompat.EDGE_*） */
    var backEdge by mutableIntStateOf(0)

    /** 手指竖直方向相对按下点的位移（px） */
    var backDy by mutableFloatStateOf(0f)

    /** 返回手势进行中（从第一个事件起，进度还是 0 时下层页就要露出来） */
    var backActive by mutableStateOf(false)

    /** 已提交返回，正在缩回去 */
    var closing by mutableStateOf(false)

    /** 打开动画已经开始过（第一次打开前要先等页面画完第一帧） */
    var started = false

    /** 关闭开始那一刻的画面已经记下（之前那一帧仍按打开时的画面画，不跳） */
    var closeCaptured by mutableStateOf(false)

    /** 关闭开始那一刻的画面（关闭动画从这里连续地缩回来源） */
    var closeFrom: Rect? = null
    var closeUnder: Float = 1f
    var closeMix: Float = 1f
    var closeCover: Float = 0f

    /** 这一页内容根节点的坐标（未变换的页面坐标系） */
    var contentCoords: LayoutCoordinates? = null

    /** 这一页变换后的整页画面（屏幕坐标系）和它的坐标：下层页的玻璃在转场中透过它看到这一页（见 rememberMochiNavCoverBackdrop） */
    var coverLayer: GraphicsLayer? = null
    var coverCoords: LayoutCoordinates? = null

    /**
     * 打开、拖动返回、关闭中。用 derivedStateOf：只在「是否转场中」变化时通知读者，
     * 不会因为进度每帧变化让读它的组合 / 绘制每帧都重来
     */
    val inTransition: Boolean by derivedStateOf {
        closing || backActive || progress.isRunning || progress.value != 1f || back.value != 0f ||
            (source != null && (move.isRunning || move.value != 1f))
    }
}

/** 来源元素（卡片）的登记信息 */
internal class SourceHandle(var key: String) {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null

    /** 来源元素录下来的画面（转场时画进变形中的容器）和它的大小 */
    var layer: GraphicsLayer? = null
    var size: Size = Size.Zero
    var radius: Float = 0f
    var color: Color = Color.Transparent
}

/** 遮挡物的登记信息：位置 + 录下来的画面 */
internal class HideHandle {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null
    var layer: GraphicsLayer? = null
    var height: Float = 0f
    var below: Float = 0f
}

internal class OccluderHandle {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null
    var layer: GraphicsLayer? = null
}

/** 共享元素的登记信息：位置 + 录下来的画面（转场时由 MochiNavHost 画到飞行中的位置） */
internal class SharedElementHandle(var key: String) {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null
    var layer: GraphicsLayer? = null
    var size: Size = Size.Zero
}

/**
 * 当前页面的转场是否已经结束（打开动画走完、也没在关闭）。
 *
 * 转场期间页面按整屏大小排好版、只做缩放，布局本身是稳定的；但重的内容（网络请求、大图解码）最好等它变成 true
 * 再开始，期间用 [ui.feice.mochi.basic.MochiSkeleton] 占位——骨架和真内容占一样的位置，加载完成时原地交叉淡化，
 * 转场过程中不会有东西跳动。不在 [MochiNavHost] 里时总是 true。
 */
@Composable
public fun rememberMochiNavSettled(): State<Boolean> {
    val e = LocalMochiNavEntry.current
    return remember(e) { derivedStateOf { e == null || (!e.inTransition && !e.closing) } }
}

/** 有来源的页拖动返回拖到底时，下面那页「露出来」的比例 */
private const val CoverBackReveal = 0.6f
