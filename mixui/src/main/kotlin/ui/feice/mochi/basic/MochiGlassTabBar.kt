// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.glass.MochiBackdrop
import ui.feice.mochi.effect.glass.engine.BackdropSampler
import ui.feice.mochi.effect.glass.engine.GlassBody
import ui.feice.mochi.effect.glass.engine.GlowAlpha
import ui.feice.mochi.effect.glass.engine.GlowMatrixDark
import ui.feice.mochi.effect.glass.engine.GlowMatrixLight
import ui.feice.mochi.effect.glass.engine.GlowReach
import ui.feice.mochi.effect.glass.engine.GlowStops
import ui.feice.mochi.effect.glass.engine.applyTo
import ui.feice.mochi.effect.glass.engine.drawGlassOuterShadow
import ui.feice.mochi.effect.glass.engine.lerp
import ui.feice.mochi.effect.glass.engine.preimage
import ui.feice.mochi.effect.glass.engine.selection
import ui.feice.mochi.navigation.LocalMochiNavEntry
import ui.feice.mochi.navigation.LocalMochiNavigator
import ui.feice.mochi.navigation.mochiNavOccluder
import ui.feice.mochi.navigation.rememberMochiNavCarriedBackdrop
import ui.feice.mochi.navigation.rememberMochiNavCoverBackdrop
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.MochiGlass
import ui.feice.mochi.theme.MochiGlassRecipe
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHaptics
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.smoothstep

/** Compare resolving the bar once for its visible body, glow and selection lens. */
internal var GlassRetainedBarTexture by mutableStateOf(false)

/**
 * 玻璃标签栏（iOS 26 的 Liquid Glass 标签栏），[MochiTabBar] 在材质是玻璃时用它画：
 *
 * - 底栏是一块玻璃胶囊，按 iOS 玻璃着色器的顺序画：边上往里折射出倒影、轻模糊和重模糊逐通道混合、面色矩阵、
 *   边缘泄色、一圈很淡的黑环、上下两道定向高光；没有投影
 * - 选中块平时是一块半透明灰胶囊，里面的图标是选中色——颜色变化被选中块的轮廓裁着，滑过去时一半蓝一半黑
 * - 按下（任意位置）：选中块鼓成一颗比底栏还高的透明玻璃泡（Kyant catalog 的透镜），跑到手指下面，里面的图标被放大、
 *   染成选中色，边缘折射、色散；底栏整体微微放大。拖动时玻璃泡跟手，按速度横向拉长、纵向压扁；底栏被带着挪一点
 * - 松手：玻璃泡滑到最近的标签，快到时缩回灰色选中块；滑过标签时轻震
 * - 布局和纯色标签栏一样（[tabBarFrame]）：只有图标、只在选中时显示标签（竖排，或只有图标时横排在右边）、
 *   滚动收起成只剩当前图标的小圆、右边的搜索圆钮展开成搜索框、上方的附件条；搜索框和附件条也是同一种玻璃
 *
 * 渲染用 Kyant 的 backdrop：底栏读页面来源；另录一份「选中色图标 + 底栏」给玻璃泡，玻璃泡读两者叠在一起的画面。
 */
@Composable
internal fun MochiGlassTabBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    items: ImmutableList<MochiTabItem>,
    enabled: Boolean,
    colors: MochiTabBarColors,
    behavior: MochiTabBarBehavior,
    windowInsets: WindowInsets,
    onReselect: ((Int) -> Unit)?,
    scrollBehavior: MochiTabBarScrollBehavior?,
    search: MochiTabBarSearch?,
    accessory: (@Composable (inline: Boolean) -> Unit)?,
    glass: MochiGlass,
    backdrop: MochiBackdrop?,
    modifier: Modifier = Modifier,
) {
    val n = items.size
    val view = LocalView.current
    val density = LocalDensity.current
    val haptics = remember(view, behavior.hapticProfile) { MochiHaptics(view, behavior.hapticProfile) }
    val scope = rememberCoroutineScope()
    val selected by rememberUpdatedState(selectedIndex.coerceIn(0, n - 1))
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnReselect by rememberUpdatedState(onReselect)
    val state = remember(scope) { GlassTabState(scope, selected.toFloat()) }
    state.last = (n - 1).toFloat()
    // 外部换了选中项（比如左右滑页面）：选中块滑过去，不鼓起
    LaunchedEffect(state) {
        snapshotFlow { selected }.collect { if (!state.held && state.target.roundToInt() != it) state.slideTo(it.toFloat()) }
    }

    // 页面来源 + 从来源打开转场中正盖上来的那一页：栏留在变形的容器上面时，玻璃里透出的是容器，不是被盖住的旧内容
    val pageSource: Backdrop = backdrop?.layer ?: emptyBackdrop()
    val cover = rememberMochiNavCoverBackdrop()
    val pageAndCover: Backdrop = if (cover == null) pageSource else rememberCombinedBackdrop(pageSource, cover)
    // 栏被导航主机代画着往下滑时，背景按滑下去的量挪回来，不和栏一起错位
    val page: Backdrop = rememberMochiNavCarriedBackdrop(pageAndCover)
    val faceSource = backdrop?.face ?: pageSource
    val faceAndCover = if (cover == null) faceSource else rememberCombinedBackdrop(faceSource, cover)
    val pageFace = rememberMochiNavCarriedBackdrop(faceAndCover)
    val tabsBackdrop = rememberLayerBackdrop()
    // 底栏玻璃本身（不含图标）录一份：选中块、按下时留在原位的选中块读它来模糊、过矩阵
    val barGlass = rememberLayerBackdrop()
    val retainBar = GlassRetainedBarTexture
    // 玻璃泡读：页面 + 底栏玻璃 + 光斑和选中色图标（tabsBackdrop 里只录后两样；底栏玻璃直接读 barGlass，不再复制一份）
    val glassBackdrop = rememberCombinedBackdrop(page, barGlass, tabsBackdrop)
    // 浅色 / 深色玻璃：默认跟 App 深浅色。自适应时每 50ms 取一次栏上方那条背景的平均色（平滑过、带死区），
    // 只有背后特别暗（浅色模式下 < flipToDark）或特别亮（深色模式下 > flipToLight）、并且连续 3 次采样都这样才翻到另一种，
    // 回来要越过 ±0.1 的回差；中等亮度的内容按 App 走（同一块内容，浅色模式是浅色玻璃、深色模式是深色玻璃，和 iOS 一致）。
    // 翻转整体过渡 450ms，玻璃和上面的图标一起变，不会停在半深半浅。
    // 材质有 byLuma 时，配方再跟着这个亮度变：颜色和配方都用 response 0.3s 的临界阻尼弹簧，换目标时保留速度
    val dark = MochiTheme.colorScheme.isDark
    val toneAnim = remember { Animatable(if (dark) 0f else 1f) }
    // 按亮度变的配方：不对亮度本身做动画（配方是分档的，亮度连续扫过时会一档一档地跳），而是从当前显示的配方
    // 插到新亮度对应的配方（和背后颜色同一条曲线）。每份是（深色玻璃，浅色玻璃）两个配方，再按 tone 插值
    var lumaFrom by remember { mutableStateOf<Pair<MochiGlassRecipe, MochiGlassRecipe>?>(null) }
    var lumaTo by remember { mutableStateOf<Pair<MochiGlassRecipe, MochiGlassRecipe>?>(null) }
    // 上面两份配方各自对应的背后亮度：材质换了（比如拖通透度），按新材质从这两个亮度重新生成，不用等下一次取样
    val lumaPair = remember { FloatArray(2) }
    // 插值进度由一个连续的亮度弹簧推出来：换目标时弹簧带着速度接着走，变色不会一顿一顿
    val lumaAnim = remember { Animatable(0f) }
    var lumaStart by remember { mutableFloatStateOf(0f) }
    fun lumaProgress(): Float {
        val span = lumaAnim.targetValue - lumaStart
        return if (abs(span) < 1e-4f) 1f else ((lumaAnim.value - lumaStart) / span).coerceIn(0f, 1f)
    }
    // 背后的平均颜色：图标、选中块按配方里的矩阵从「玻璃本身的颜色」算出来（iOS 的 vibrancy）
    val backdropColor = remember { androidx.compose.animation.Animatable(Color(0.5f, 0.5f, 0.5f)) }
    // 背后亮度：取栏上方紧挨着的一条（窗口坐标，栏位置变了就更新）
    val sampler = remember(glass.adaptive, backdrop) { if (glass.adaptive && backdrop != null) BackdropSampler() else null }
    DisposableEffect(sampler) { onDispose { sampler?.release() } }
    val barBounds = remember { android.graphics.Rect() }
    val sampleGap = with(density) { 8.dp.roundToPx() }
    val sampleHeight = with(density) { 16.dp.roundToPx() }
    val navigator = LocalMochiNavigator.current
    val navEntry = LocalMochiNavEntry.current

    // 「从来源打开」的页正盖上来时，玻璃要透过变形中的容器每帧重算（rememberMochiNavCoverBackdrop）。
    // 盖到 CoverFadeEnd 之前平滑退成半透明的备用色，之后玻璃图层完全透明、系统直接跳过它，不再算玻璃；
    // 返回时跟着露出来的进度原路恢复。侧推转场不用：下层页整页缓存成纹理，玻璃本来就不重算
    fun coverFade(): Float {
        val nav = navigator ?: return 0f
        val e = navEntry ?: return 0f
        if (nav.above(e)?.source == null) return 0f
        return (nav.coverOf(e) / CoverFadeEnd).coerceIn(0f, 1f)
    }
    // 下面的取样循环和几个 derivedStateOf 只建一次，材质要从这里读最新的（深浅色切换、拖通透度都会换一份材质）。
    // 取样循环要是读启动时捕获的 glass，拖完通透度一滚动就又按旧材质生成配方，预览跳回旧的通透度
    val latestGlass by rememberUpdatedState(glass)
    fun pressProgress(): Float = if (latestGlass.blurOnly) 0f else state.pressProgress
    fun growProgress(): Float = if (latestGlass.blurOnly) 0f else state.growProgress
    fun lensStretch(): Float = if (latestGlass.blurOnly) 0f else state.stretch
    LaunchedEffect(dark, glass.adaptive, backdrop) {
        var flipped = false
        var flipVotes = 0
        var smooth: Color? = null
        var lumaTarget = -1f
        if (!glass.adaptive) {
            // 不自适应：图标、选中块的颜色按中灰背后算、定死，不停在关掉之前最后取到的颜色上
            launch { backdropColor.animateTo(Color(0.5f, 0.5f, 0.5f), ColorSpring) }
        }
        toneAnim.animateTo(if (dark) 0f else 1f, tween(ToneMillis))
        if (!glass.adaptive || backdrop == null) return@LaunchedEffect
        while (isActive) {
            delay(SampleMillis.toLong())
            if (navEntry != null && navigator?.isMovingOrCovered(navEntry) == true) continue
            val top = (barBounds.top - sampleGap - sampleHeight).coerceAtLeast(0)
            sampler?.request(view, android.graphics.Rect(barBounds.left, top, barBounds.right, top + sampleHeight))
            val raw = sampler?.average ?: continue
            // 防抖：采样先做指数平滑，和当前目标差得不多就不重新起动画（内容滚动时平均色每次都在小幅跳）
            val avg = smooth?.let { androidx.compose.ui.graphics.lerp(it, raw, SampleSmoothing) } ?: raw
            smooth = avg
            val l = 0.2126f * avg.red + 0.7152f * avg.green + 0.0722f * avg.blue
            if (backdropColor.targetValue.distanceTo(avg) > ColorDeadBand) {
                launch { backdropColor.animateTo(avg, ColorSpring) }
            }
            val g = latestGlass
            val byLuma = g.byLuma
            if (byLuma != null && (lumaTarget < 0f || abs(lumaTarget - l) > LumaDeadBand)) {
                lumaTarget = l
                val next = byLuma(l, false) to byLuma(l, true)
                val from = lumaFrom
                val to = lumaTo
                if (from == null || to == null) {
                    lumaFrom = next
                    lumaTo = next
                    lumaPair[0] = l
                    lumaPair[1] = l
                    lumaAnim.snapTo(l)
                    lumaStart = l
                } else if (next != to) {
                    // 中途换目标：从现在显示的配方接着走，亮度弹簧保留速度
                    val p = lumaProgress()
                    lumaFrom = lerp(from.first, to.first, p) to lerp(from.second, to.second, p)
                    lumaTo = next
                    lumaPair[0] = androidx.compose.ui.util.lerp(lumaPair[0], lumaPair[1], p)
                    lumaPair[1] = l
                    lumaStart = lumaAnim.value
                    launch { lumaAnim.animateTo(l, LumaSpring) }
                }
            }
            val want = if (dark) {
                if (flipped) l > g.flipToLight - 0.1f else l > g.flipToLight
            } else {
                if (flipped) l < g.flipToDark + 0.1f else l < g.flipToDark
            }
            // 深浅翻转要连续几次采样都同意才翻，滑过一小块很亮 / 很暗的内容不会来回闪
            flipVotes = if (want != flipped) flipVotes + 1 else 0
            if (flipVotes >= FlipSamples) {
                flipped = want
                flipVotes = 0
            }
            val target = if (dark != flipped) 0f else 1f
            if (toneAnim.targetValue != target) launch { toneAnim.animateTo(target, tween(ToneMillis)) }
        }
    }
    fun light(): Float = toneAnim.value
    val tone by remember { derivedStateOf { light() } }
    fun surfaceColor(): Color = if (backdrop == null) glass.fallback else Color.Transparent
    fun indicatorColor(): Color = if (glass.adaptive) {
        androidx.compose.ui.graphics.lerp(
            Color.Black.copy(alpha = 0.3f),
            Color.Black.copy(alpha = 0.1f),
            light(),
        )
    } else {
        colors.indicator
    }

    // 玻璃本体按 iOS 玻璃着色器的顺序画（见 GlassPipeline）：浅色、深色两份配方按当前深浅插值
    // 材质换了（通透度、深浅色）：按新材质从记下的两个亮度重新生成配方，立刻生效
    LaunchedEffect(glass.byLuma) {
        val byLuma = glass.byLuma ?: return@LaunchedEffect
        if (lumaFrom == null || lumaTo == null) return@LaunchedEffect
        lumaFrom = byLuma(lumaPair[0], false) to byLuma(lumaPair[0], true)
        lumaTo = byLuma(lumaPair[1], false) to byLuma(lumaPair[1], true)
    }
    val latestColors by rememberUpdatedState(colors)
    fun recipe() = latestGlass.let { g ->
        val from = lumaFrom
        val to = lumaTo
        if (g.byLuma != null && from != null && to != null) {
            val p = lumaProgress()
            lerp(lerp(from.first, to.first, p), lerp(from.second, to.second, p), light())
        } else {
            lerp(g.dark, g.light, light())
        }
    }

    // 玻璃本身的颜色（背后平均色过面色矩阵），图标和选中块的矩阵作用在它上面
    val glassColor by remember { derivedStateOf { recipe().faceMatrix.applyTo(backdropColor.value) } }
    val contentColor by remember {
        derivedStateOf {
            recipe().contentMatrix?.applyTo(glassColor)
                ?: if (latestGlass.adaptive) androidx.compose.ui.graphics.lerp(Color.White, Color.Black, tone) else latestColors.content
        }
    }
    // 选中的图标在选中块上面，作用在选中块的颜色上
    val accentColor by remember {
        derivedStateOf {
            val r = recipe()
            r.accentMatrix?.applyTo(r.selectionMatrix?.applyTo(glassColor) ?: glassColor) ?: latestColors.selectedContent
        }
    }

    // 搜索展开进度：和纯色标签栏一样，和滚动收起同一种弹簧
    val searchActive = search?.active == true
    val searchProgress = remember { Animatable(if (searchActive) 1f else 0f) }
    LaunchedEffect(searchActive) { searchProgress.animateTo(if (searchActive) 1f else 0f, MochiMotions.responseSpring(0.42f, 0.82f)) }
    if (search != null) BackHandler(enabled = searchActive) { search.onActiveChange(false) }
    val currentSearch by rememberUpdatedState(search)
    // 搜索框、附件条也在玻璃上：里面的文字、图标用玻璃上的内容色（和未选中的标签同一种）
    val plateColors = colors.copy(content = contentColor, selectedContent = contentColor)

    BoxWithConstraints(modifier.mochiNavOccluder().windowInsetsPadding(windowInsets).fillMaxWidth()) {
        val maxPx = constraints.maxWidth.toFloat()
        val hPx = with(density) { behavior.height.toPx() }
        val insetPx = with(density) { BarInset.toPx() }
        val gapPx = with(density) { SideGap.toPx() }
        val accPx = with(density) { AccessoryHeight.toPx() }
        val searchSlot = if (search != null) gapPx + hPx else 0f
        // 只有图标、只在选中时显示标签：选中项的标签横排在图标右边，那一格按标签宽度变宽（同纯色标签栏）
        val inlineLabel = !behavior.showLabels && behavior.selectedLabelOnly
        val measurer = rememberTextMeasurer()
        val extras = remember(items, inlineLabel, density) {
            if (!inlineLabel) {
                FloatArray(n)
            } else {
                val gap = with(density) { InlineLabelGap.toPx() }
                FloatArray(n) { measurer.measure(items[it].label, InlineLabelStyle, maxLines = 1, density = density).size.width + gap }
            }
        }
        val maxExtra = extras.maxOrNull() ?: 0f
        // 不算横排标签时的宽度：给最宽的标签留出位置
        val fullPx = min(maxPx - searchSlot - maxExtra, with(density) { (behavior.itemWidth * n + BarInset * 2).toPx() })
        // 有附件条时，标签栏那一行在附件条下面
        val rowTop = if (accessory != null) accPx + gapPx else 0f
        val tabPx = (fullPx - insetPx * 2f) / n
        val indicatorHeight = behavior.height - BarInset * 2
        val indicatorHeightPx = with(density) { indicatorHeight.toPx() }
        val lensGrowPx = with(density) { LensGrow.toPx() }
        state.unitDp = with(density) { tabPx.toDp().value }

        // 第 i 项此刻的选中程度（选中块经过时连续变化）
        fun selectedness(i: Int): Float = (1f - abs(state.value - i)).coerceIn(0f, 1f)

        // 第 i 格的左边和宽度（横排标签时选中的格子变宽；否则等宽），底栏坐标
        fun cellLeft(i: Int): Float {
            var x = insetPx + i * tabPx
            if (inlineLabel) for (j in 0 until i) x += extras[j] * selectedness(j)
            return x
        }

        fun cellWidth(i: Int): Float = tabPx + if (inlineLabel) extras[i] * selectedness(i) else 0f

        // 展开时整条的宽度：横排标签时跟着选中块连续变化
        fun barW(): Float {
            if (!inlineLabel) return fullPx
            var w = fullPx
            for (i in 0 until n) w += extras[i] * selectedness(i)
            return w
        }

        // 选中块 / 玻璃泡停在第 x 项（可以是小数）时的宽度和中心：落在两格之间时按比例插值
        fun slotWidth(x: Float): Float {
            if (!inlineLabel) return tabPx
            val k = x.toInt().coerceIn(0, n - 1)
            val f = (x - k).coerceIn(0f, 1f)
            return if (k + 1 < n && f > 0f) lerp(cellWidth(k), cellWidth(k + 1), f) else cellWidth(k)
        }

        fun slotCenter(x: Float): Float {
            if (!inlineLabel) return insetPx + (x + 0.5f) * tabPx
            val k = x.toInt().coerceIn(0, n - 1)
            val f = (x - k).coerceIn(0f, 1f)
            val a = cellLeft(k) + cellWidth(k) / 2f
            return if (k + 1 < n && f > 0f) lerp(a, cellLeft(k + 1) + cellWidth(k + 1) / 2f, f) else a
        }

        fun collapse(): Float = scrollBehavior?.fraction?.value ?: 0f

        // 标签栏收成小圆的程度：滚动收起、进入搜索都会收
        fun barCollapse(): Float = max(collapse(), searchProgress.value)

        fun frame(): TabBarFrame = tabBarFrame(
            maxPx = maxPx,
            barWidth = barW(),
            searchSlot = searchSlot,
            rowTop = rowTop,
            height = hPx,
            gap = gapPx,
            accessoryHeight = accPx,
            hasAccessory = accessory != null,
            hasSearch = search != null,
            collapse = collapse(),
            search = searchProgress.value,
        )

        // 展开时的内容（各项图标、选中块、玻璃泡）：收起途中先淡掉，再换成只有当前图标的小圆
        fun expandedAlpha(): Float = 1f - smoothstep(0f, 0.6f, barCollapse())

        // 整条底栏跟着按压放大、被拖动的手指带着平移并略微横向拉长（iOS 26.5 实测：放大到 1.0355，
        // 平移是拖动距离的 1.17%，每拖 1pt 横向 +0.0000206、纵向 −0.0000404）
        fun barScaleX(): Float = if (latestGlass.blurOnly) {
            1f
        } else {
            1f + BarPressScale * state.barProgress +
                BarDragStretchX * abs(state.panValue) / density.density
        }
        fun barScaleY(): Float = if (latestGlass.blurOnly) {
            1f
        } else {
            1f + BarPressScale * state.barProgress -
                BarDragStretchY * abs(state.panValue) / density.density
        }
        fun barShift(): Float = BarDragShift * state.panValue

        fun indexAt(x: Float): Float {
            if (!inlineLabel) return ((x - insetPx) / tabPx - 0.5f).coerceIn(0f, n - 1f)
            // 格子不等宽：找到手指所在的格子，按格内位置换算成小数的「第几项」
            for (i in 0 until n) {
                val l = cellLeft(i)
                val w = cellWidth(i)
                if (x < l + w || i == n - 1) return (i + (x - l) / w - 0.5f).coerceIn(0f, n - 1f)
            }
            return n - 1f
        }

        // 按下时手指下的光斑（iOS 的 _UIFlexInteractionLittleGlowView）：一个 93pt 的圆的阴影（半径 46.5），
        // 把背后的玻璃过一组提饱和矩阵后按这个阴影的形状混进来。按下淡入；拖起来放大到 2 倍、亮度跟着减半；松手放大到 4 倍淡掉
        // 裁剪用的胶囊路径：形状不变就一直是同一个 Path 对象，系统能缓存它光栅化好的遮罩（每帧新建的话每帧重画遮罩、重新上传）
        val glowClip = remember { CapsuleClip() }
        val iconClip = remember { CapsuleClip() }

        @Composable
        fun PressGlow() {
            Box(
                Modifier
                    .fillMaxSize()
                    // 离屏缓冲按这一层的矩形裁：只有底栏那么大的话，按下放大出去的两头光斑被切成一条直边，
                    // 玻璃泡读到它（tabsBackdrop 里也有一份光斑）就在两端看到这条切线。和录制范围一样往外扩 SampleMargin
                    .sampleOutset()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = (GlowAlpha * state.glowProgress / state.glowScale).coerceIn(0f, 1f)
                    }
                    .drawWithContent {
                        if (state.glowProgress <= 0.001f) return@drawWithContent
                        // 光斑只在底栏胶囊里：不按胶囊裁的话，两端胶囊外的矩形角上也会亮
                        val m = SampleMargin.toPx()
                        val bp = barW()
                        val bw = bp * barScaleX()
                        val bh = (size.height - m * 2f) * barScaleY()
                        val capsule = glowClip.get(bw, bh, m + bp / 2f + barShift() - bw / 2f, size.height / 2f - bh / 2f, this)
                        clipPath(capsule) { this@drawWithContent.drawContent() }
                        val r = GlowReach.toPx() * state.glowScale * barScaleX()
                        val cx = m + bp / 2f + (state.glowX - bp / 2f) * barScaleX() + barShift()
                        drawRect(
                            Brush.radialGradient(*GlowStops, center = Offset(cx, size.height / 2f), radius = r),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .drawBackdrop(
                        backdrop = barGlass,
                        shape = { RectangleShape },
                        effects = {
                            if (state.glowProgress > 0.001f) {
                                selection(GlowMatrixDark.indices.map { lerp(GlowMatrixDark[it], GlowMatrixLight[it], light()) }, 1f)
                            }
                        },
                        highlight = null,
                        shadow = null,
                    ),
            )
        }

        // 各项的图标（和标签）：按 cellLeft / cellWidth 排在展开的底栏里，跟着底栏一起放大、平移
        @Composable
        fun Cells(tint: () -> Color, modifier: Modifier = Modifier, cellModifier: (Int) -> Modifier) {
            GlassCells(
                items = items,
                tint = tint,
                behavior = behavior,
                inline = inlineLabel,
                extras = extras,
                colors = colors,
                left = ::cellLeft,
                width = ::cellWidth,
                selectedness = ::selectedness,
                cellModifier = cellModifier,
                modifier = modifier.fillMaxSize().graphicsLayer {
                    translationX = barShift()
                    scaleX = barScaleX()
                    scaleY = barScaleY()
                },
            )
        }

        // A bar must not sample its own shadow when the selection blurs or
        // refracts barGlass. Keep only the optical body in that source.
        val opticalRecipe by remember { derivedStateOf { recipe().copy(shadowOpacity = 0f) } }

        // 搜索框、附件条的底板：同一份配方、同一个背景来源的玻璃；被从来源打开的页盖上时一样退成备用色
        val plate =
            TabBarPlate(
                under = {
                    if (backdrop != null) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = coverFade() }
                                .background(latestGlass.fallback, MochiCapsule),
                        )
                    }
                    GlassBody(
                        backdrop = page,
                        faceBackdrop = pageFace,
                        recipe = { recipe() },
                        modifier = Modifier.matchParentSize(),
                        alpha = { 1f - coverFade() },
                        onDrawSurface = { drawRect(surfaceColor()) },
                    )
                },
            )

        Box(Modifier.fillMaxWidth().height(with(density) { (rowTop + hPx).toDp() })) {
            if (accessory != null) {
                TabBarAccessory(
                    rect = { frame().accessory },
                    alpha = { frame().accessoryAlpha },
                    inline = scrollBehavior?.collapsed == true,
                    blocked = searchActive,
                    colors = plateColors,
                    plate = plate,
                    content = accessory,
                )
            }
            if (search != null) {
                TabBarSearchField(
                    search = search,
                    rect = { frame().search },
                    progress = { searchProgress.value },
                    height = behavior.height,
                    colors = plateColors,
                    plate = plate,
                )
            }
            Box(
                Modifier
                    // 收起、进入搜索时宽度连续变窄（只重新布局，不重组）
                    .placedAt { frame().main }
                    .onGloballyPositioned {
                        val b = it.boundsInWindow()
                        barBounds.set(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                    }
                    .pointerInput(enabled, n, tabPx, inlineLabel) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (barCollapse() > 0.5f) {
                                // 收成小圆时点它：搜索中退出搜索，滚动收起时展开
                                down.consume()
                                if (waitForUpOrCancellation() != null) {
                                    val s = currentSearch
                                    if (s != null && s.active) s.onActiveChange(false) else scrollBehavior?.expand()
                                }
                                return@awaitEachGesture
                            }
                            state.press(indexAt(down.position.x), down.position.x)
                            var tick = state.target.roundToInt()
                            var dragging = false
                            var released = false
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    released = true
                                    break
                                }
                                val dx = ch.position.x - down.position.x
                                if (!dragging && abs(dx) > viewConfiguration.touchSlop) dragging = true
                                if (dragging) {
                                    state.drag(indexAt(ch.position.x), dx, ch.position.x)
                                    val t = state.target.roundToInt()
                                    if (t != tick) {
                                        tick = t
                                        haptics.tick()
                                    }
                                }
                                ch.consume()
                            }
                            val target = if (released) state.target.roundToInt().coerceIn(0, n - 1) else selected
                            state.release(target.toFloat())
                            if (released) {
                                if (target != selected) currentOnSelect(target) else currentOnReselect?.invoke(target)
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // 被盖上来的页挡住时代替玻璃的备用色（见 coverFade）
                if (backdrop != null) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                translationX = barShift()
                                alpha = coverFade()
                            }
                            .background(glass.fallback, MochiCapsule),
                    )
                }
                // 底栏玻璃：顺便录进 barGlass。录制范围比底栏大一圈（SampleMargin）：按下时底栏放大、玻璃泡外扩到两端外面，
                // 录制范围只有底栏那么大的话，放大出去的两头会被录制边界切掉，玻璃泡在两端折射到的就是这条直边
                Box(
                    Modifier.matchParentSize().sampleOutset()
                        .drawBehind {
                            val m = SampleMargin.toPx()
                            translate(barShift(), 0f) {
                                scale(barScaleX(), barScaleY(), pivot = size.center) {
                                    drawGlassOuterShadow(recipe(), Rect(m, m, size.width - m, size.height - m), 1f - coverFade())
                                }
                            }
                        }
                        .layerBackdrop(barGlass).graphicsLayer {
                            compositingStrategy = if (retainBar) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                        },
                ) {
                    // 玻璃本体：重的部分降采样算，贴边的细节全分辨率（见 GlassBody）
                    GlassBody(
                        backdrop = page,
                        faceBackdrop = pageFace,
                        recipe = { opticalRecipe },
                        modifier = Modifier
                            .padding(SampleMargin)
                            .fillMaxSize()
                            .graphicsLayer { translationX = barShift() },
                        // 淡出交给 GlassBody：透明度挂在这里会按底栏矩形离屏，外阴影被切成直角
                        alpha = { 1f - coverFade() },
                        scaleX = { barScaleX() },
                        scaleY = { barScaleY() },
                        onDrawSurface = { drawRect(surfaceColor()) },
                    )
                }

                // 展开时的内容：按展开的宽度排版（不跟着变窄重排），收起途中淡掉、裁在变窄的玻璃里
                Box(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .layout { measurable, constraints ->
                            val w = barW().roundToInt()
                            val p = measurable.measure(Constraints.fixed(w, constraints.maxHeight))
                            layout(w, constraints.maxHeight) { p.place(0, 0) }
                        }
                        .graphicsLayer { alpha = expandedAlpha() }
                        .drawWithContent {
                            if (barCollapse() > 0f) {
                                clipRect(right = frame().main.width) { this@drawWithContent.drawContent() }
                            } else {
                                drawContent()
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // 未选中色的图标（和选中的一样用实心图标）
                    Cells(
                        tint = { contentColor },
                        modifier = Modifier,
                        cellModifier = { i ->
                            Modifier
                                // 只显示图标时标签文字不在树里，读屏要靠这里给名字；整格合成一个节点，选中时不读两遍
                                .clearAndSetSemantics {
                                    role = Role.Tab
                                    contentDescription = items[i].label
                                    this.selected = i == selected
                                    onClick {
                                        if (i != selected) currentOnSelect(i) else currentOnReselect?.invoke(i)
                                        true
                                    }
                                }
                                .graphicsLayer {
                                    val s = 1f + IconPressScale * pressProgress()
                                    scaleX = s
                                    scaleY = s
                                }
                        },
                    )

                    if (!glass.blurOnly) PressGlow()

                    // 给玻璃泡看的光斑 + 选中色图标（底栏玻璃本身玻璃泡直接读 barGlass）；本身不可见，只录进 tabsBackdrop。
                    // 选中色图标按按压进度淡入：平时选中块读到的只有底栏玻璃（它要先模糊），选中色图标另画在选中块上面
                    Box(
                        Modifier
                            .matchParentSize()
                            .sampleOutset()
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop),
                    ) {
                        Box(Modifier.padding(SampleMargin).fillMaxSize()) {
                            if (!glass.blurOnly) PressGlow()
                            Cells(
                                // 选中块会把下面这份图标也过一遍它的矩阵（平时全强度，按下时渐渐没有），先反过来抵消
                                tint = { recipe().selectionMatrix?.preimage(accentColor, 1f - pressProgress()) ?: accentColor },
                                modifier = Modifier,
                                cellModifier = {
                                    Modifier.graphicsLayer {
                                        val s = 1f + IconPressScale * pressProgress()
                                        scaleX = s
                                        scaleY = s
                                        alpha = pressProgress()
                                    }
                                },
                            )
                        }
                    }

                    // At rest the selection is entirely inside the opaque glass body: reading
                    // the page and invisible pressed icons again only repeats offscreen work.
                    // Keep the combined source for the lens while it is growing/pressed/releasing.
                    val lensActive by remember(glass.blurOnly) { derivedStateOf { pressProgress() > 0f || growProgress() > 0f } }
                    val selectionBackdrop = if (lensActive) glassBackdrop else barGlass

                    // 选中块 / 玻璃泡：平时是选中块；按下时四边各外扩 LensGrow，里面的选中块按 1 − p 淡掉。
                    // 玻璃泡本身照 Kyant catalog 的 LiquidBottomTabs：透镜折射 10 / 14 带色散、默认高光、外阴影、内阴影，都乘按压进度 p。
                    // 它在底栏里面，跟着底栏一起放大、平移；自己再按速度拉伸。横排标签时宽度跟着所在的格子
                    Box(
                        Modifier
                            .graphicsLayer {
                                val bp = barW()
                                val w = slotWidth(state.value) + lensGrowPx * 2f * growProgress()
                                translationX = bp / 2f + (slotCenter(state.value) - bp / 2f) * barScaleX() + barShift() - w / 2f
                            }
                            .layout { measurable, _ ->
                                val g = lensGrowPx * growProgress()
                                val w = (slotWidth(state.value) + g * 2f).roundToInt()
                                val h = (indicatorHeightPx + g * 2f).roundToInt()
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(w, h) { placeable.place(0, 0) }
                            }
                            .drawBackdrop(
                                backdrop = selectionBackdrop,
                                shape = { MochiCapsule },
                                effects = {
                                    val p = pressProgress()
                                    // 平时：选中块把下面的玻璃过一遍 selectionMatrix（iOS 的 UITabSelectionView），按下时渐渐淡掉
                                    val m = recipe().selectionMatrix
                                    if (m != null && p < 1f) selection(m, 1f - p, SelectionBlur.toPx())
                                    if (p > 0.002f) {
                                        lens(LensRefractionHeight.toPx() * p, LensRefractionAmount.toPx() * p, chromaticAberration = true)
                                    }
                                },
                                highlight = { Highlight.Default.copy(alpha = pressProgress()) },
                                shadow = { Shadow(alpha = pressProgress()) },
                                innerShadow = { InnerShadow(radius = LensInnerShadow * pressProgress(), alpha = pressProgress()) },
                                layerBlock = {
                                    val u = lensStretch()
                                    scaleX = barScaleX() * (1f + LensStretchX * u)
                                    scaleY = barScaleY() * (1f - LensStretchY * u)
                                },
                                onDrawSurface = {
                                    if (recipe().selectionMatrix == null) drawRect(indicatorColor(), alpha = 1f - pressProgress())
                                    drawRect(Color.Black.copy(alpha = 0.03f * pressProgress()))
                                },
                            ),
                    )

                    // 平时的选中色图标：画在选中块上面、按选中块（玻璃泡）当前的轮廓裁着。iOS 的选中块模糊在图标后面，图标本身是清楚的；
                    // 按下时淡掉，换成玻璃泡里那份（会被折射）
                    Cells(
                        tint = { accentColor },
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .drawWithContent {
                                if (pressProgress() >= 0.999f) return@drawWithContent
                                val bp = barW()
                                val g = lensGrowPx * growProgress()
                                val w = (slotWidth(state.value) + g * 2f) * barScaleX() * (1f + LensStretchX * lensStretch())
                                val h = (indicatorHeightPx + g * 2f) * barScaleY() * (1f - LensStretchY * lensStretch())
                                val cx = bp / 2f + (slotCenter(state.value) - bp / 2f) * barScaleX() + barShift()
                                val clip = iconClip.get(w, h, cx - w / 2f, size.height / 2f - h / 2f, this)
                                clipPath(clip) { this@drawWithContent.drawContent() }
                            }
                            .graphicsLayer { alpha = 1f - pressProgress() },
                        cellModifier = {
                            Modifier.graphicsLayer {
                                val s = 1f + IconPressScale * pressProgress()
                                scaleX = s
                                scaleY = s
                            }
                        },
                    )
                }

                // 收成小圆后：只剩当前项的图标，在圆心
                if (barCollapse() > 0f) {
                    Box(
                        Modifier
                            .size(behavior.height)
                            .graphicsLayer { alpha = smoothstep(0.4f, 1f, barCollapse()) },
                        contentAlignment = Alignment.Center,
                    ) { (items[selected].selectedIcon ?: items[selected].icon)(accentColor) }
                }
            }
        }
    }
}

/** 底栏四周到选中块的距离 */
private val BarInset = 4.dp

/** 底栏玻璃、给玻璃泡的那一份录制时四周多录多少：盖住底栏放大、玻璃泡外扩和折射取样 */
private val SampleMargin = 24.dp

/** 录制范围往四周外扩 [SampleMargin]，报给父布局的尺寸不变 */
private fun Modifier.sampleOutset(): Modifier = layout { measurable, constraints ->
    val m = SampleMargin.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + m * 2, constraints.maxHeight + m * 2))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-m, -m) }
}

/** 按下时玻璃泡四边各外扩多少（iOS：选中块 82×54 → 98×70） */
private val LensGrow = 8.dp

/** 玻璃泡完全按下时的透镜：折射高度、折射量、内阴影半径（Kyant catalog 的 LiquidBottomTabs） */
private val LensRefractionHeight = 10.dp
private val LensRefractionAmount = 14.dp
private val LensInnerShadow = 8.dp

/** 按下时整条底栏放大多少；拖动时平移是拖动距离的多少、每 dp 横向 / 纵向缩放变多少 */
private const val BarPressScale = 0.0355f
private const val BarDragShift = 0.0117f
private const val BarDragStretchX = 0.0000206f
private const val BarDragStretchY = 0.0000404f

/** 玻璃泡拉伸：每千 dp/s 横向拉长、纵向压扁多少；驱动它的弹簧 */
private const val LensStretchX = 0.128f
private const val LensStretchY = 0.194f
internal val StretchOmega = 2f * PI.toFloat() / 0.6f
internal const val StretchDamping = 0.21f

/**
 * 背后取色的间隔、平滑系数（新采样占的比例）、死区：颜色（RGB 最大通道差）和亮度变化小于它不重新起动画。
 * 每次取色（PixelCopy）要让渲染线程等 GPU 画完上一帧再复制，真机（骁龙 SM8850）上一次约 6ms；以前 50ms 一次，
 * 画面在动时渲染线程有一成多的时间耗在这上面。改成 100ms，平滑系数按「两次合一次」换算（1 − 0.5² = 0.75），颜色跟随的快慢不变
 */
private const val SampleMillis = 100
private const val SampleSmoothing = 0.75f
private const val ColorDeadBand = 3f / 255f
private const val LumaDeadBand = 0.01f

/** 背后颜色、亮度变化的弹簧（response 0.3s、临界阻尼，换目标时保留速度）；深浅翻转的过渡时长和要连续同意的采样次数 */
private val LumaSpring = spring(dampingRatio = 1f, stiffness = (2f * PI.toFloat() / 0.3f).let { it * it }, visibilityThreshold = 0.0005f)
private val ColorSpring = spring<Color>(dampingRatio = 1f, stiffness = (2f * PI.toFloat() / 0.3f).let { it * it })
private const val ToneMillis = 450

/** 「从来源打开」的页盖上来到这个进度时，标签栏的玻璃已经完全退成备用色 */
private const val CoverFadeEnd = 0.35f

/** 深浅翻转要连续同意的采样次数：2 × [SampleMillis] = 200ms（以前 3 × 50ms） */
private const val FlipSamples = 2

private fun Color.distanceTo(o: Color): Float = maxOf(abs(red - o.red), abs(green - o.green), abs(blue - o.blue))
