// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ui.feice.mochi.effect.content.mochiBlur
import ui.feice.mochi.effect.content.mochiBlurAlpha
import ui.feice.mochi.shape.MochiCapsule

/** 按 [left] / [width] 把各项排在底栏里（宽度可以每帧变，只重新布局）；每项是 [GlassTabCell] */
@Composable
internal fun GlassCells(
    items: ImmutableList<MochiTabItem>,
    tint: () -> Color,
    behavior: MochiTabBarBehavior,
    inline: Boolean,
    extras: FloatArray,
    colors: MochiTabBarColors,
    left: (Int) -> Float,
    width: (Int) -> Float,
    selectedness: (Int) -> Float,
    cellModifier: (Int) -> Modifier,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            items.forEachIndexed { i, item ->
                GlassTabCell(
                    item,
                    tint(),
                    behavior,
                    inline,
                    extras[i],
                    { width(i) },
                    { selectedness(i) },
                    colors,
                    cellModifier(i),
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val h = constraints.maxHeight
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(width(i).roundToInt().coerceAtLeast(0), h)) }
        layout(constraints.maxWidth, h) {
            placeables.forEachIndexed { i, p -> p.place(left(i).roundToInt(), 0) }
        }
    }
}

@Composable
private fun GlassTabCell(
    item: MochiTabItem,
    tint: Color,
    behavior: MochiTabBarBehavior,
    inline: Boolean,
    extra: Float,
    width: () -> Float,
    selectedness: () -> Float,
    colors: MochiTabBarColors,
    modifier: Modifier = Modifier,
) {
    val icon = @Composable {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            // iOS 26 玻璃底栏的图标选中、未选中都是实心的（SF Symbols 的 .fill），只靠颜色和选中块区分
            (item.selectedIcon ?: item.icon)(tint)
            MochiBadge(item.badge, badgeAnchor(item.badge), colors.badge, colors.onBadge)
        }
    }
    if (inline) {
        // 图标 + 右边的标签，整体在格子里居中；没选中时标签宽度是 0、图标居中
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            InlineTabCell(width = width, selectedness = selectedness, extra = extra, icon = icon) {
                BasicText(item.label, style = InlineLabelStyle.copy(color = tint), maxLines = 1)
            }
        }
        return
    }
    // 只在选中时显示标签：没选中的图标在整格里居中，选中时往上让出标签的位置、标签模糊淡入（同纯色标签栏）
    val shift = if (behavior.showLabels && behavior.selectedLabelOnly) LabelShift else 0.dp
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.graphicsLayer { translationY = shift.toPx() * (1f - selectedness()) }) { icon() }
        if (behavior.showLabels) {
            BasicText(
                item.label,
                style = GlassLabelStyle.copy(color = tint),
                maxLines = 1,
                modifier = if (shift > 0.dp) {
                    Modifier
                        .graphicsLayer {
                            mochiBlurAlpha(labelAlpha(selectedness()), labelBlur(selectedness()))
                            translationY = shift.toPx() * (1f - selectedness())
                        }
                        .mochiBlur { labelBlur(selectedness()) }
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * 按压状态。弹簧都按 iOS 26.5 标签栏按压时（_UILiquidLensView）逐帧记下的图层拟合，写成 response（秒）/ dampingRatio：
 * - 按压进度 p：按下、松手都是 0.25 / 1
 * - 玻璃泡四边外扩：按下 0.6 / 0.85，松手和 p 一样 0.25 / 1
 * - 玻璃泡位置：按下时去手指下那一项 0.4 / 0.85；拖起来跟手 0.2 / 0.85；松手回最近一项 0.4 / 0.85
 * - 整条底栏：放大按下 0.33 / 0.62、松手 0.44 / 0.63；拖动平移 0.3 / 0.85，松手和放大一起回去
 * - 玻璃泡拉伸：被玻璃泡的移动速度驱动的弹簧 0.6 / 0.21（近似，iOS 的拉伸不是简单的线性滤波）
 * - 光斑：按下淡入 0.2 / 0.82；拖起来放大到 2 倍 0.5 / 1，中心跟手 0.2 / 0.85；松手放大到 4 倍 0.63 / 0.9、淡出 0.65 / 0.87
 */
@Stable
internal class GlassTabState(private val scope: CoroutineScope, initial: Float) {
    private val position = Animatable(initial, 0.0005f)
    private val press = Animatable(0f, 0.0005f)
    private val grow = Animatable(0f, 0.0005f)
    private val bar = Animatable(0f, 0.0005f)
    private val pan = Animatable(0f, 0.05f)
    private val glow = Animatable(0f, 0.0005f)
    private val glowSize = Animatable(1f, 0.0005f)
    private val glowPos = Animatable(0f, 0.05f)
    private var stretchVelocity = 0f
    private var stretchJob: Job? = null
    var last = 0f

    /** 一项有多宽（dp），把玻璃泡速度换成 dp/s */
    var unitDp = 1f
    var held by mutableStateOf(false)
        private set

    /** 拉伸量：被「玻璃泡速度（千 dp/s）」驱动 */
    var stretch by mutableFloatStateOf(0f)
        private set

    val value: Float get() = position.value
    val target: Float get() = position.targetValue
    val pressProgress: Float get() = press.value
    val growProgress: Float get() = grow.value
    val barProgress: Float get() = bar.value
    val panValue: Float get() = pan.value

    /** 光斑：强度、放大倍数、中心（底栏里的 x，px） */
    val glowProgress: Float get() = glow.value
    val glowScale: Float get() = glowSize.value
    val glowX: Float get() = glowPos.value

    fun press(index: Float, x: Float) {
        held = true
        scope.launch { glowPos.snapTo(x) }
        scope.launch { glowSize.snapTo(1f) }
        scope.launch { glow.animateTo(1f, iosSpring(0.2f, 0.82f)) }
        scope.launch { press.animateTo(1f, iosSpring(0.25f, 1f)) }
        scope.launch { grow.animateTo(1f, iosSpring(0.6f, 0.85f)) }
        scope.launch { bar.animateTo(1f, iosSpring(0.33f, 0.62f)) }
        go(index, iosSpring(0.4f, 0.85f))
    }

    /** 拖动：玻璃泡跟手；[dx] 是从按下处算起的横向距离（px） */
    fun drag(index: Float, dx: Float, x: Float) {
        go(index, iosSpring(0.2f, 0.85f))
        scope.launch { glowPos.animateTo(x, iosSpring(0.2f, 0.85f)) }
        if (glowSize.targetValue < 2f) scope.launch { glowSize.animateTo(2f, iosSpring(0.5f, 1f)) }
        scope.launch { pan.animateTo(dx, iosSpring(0.3f, 0.85f)) }
    }

    /** 松手：回到 [index] 那一项，同时缩回 */
    fun release(index: Float) {
        held = false
        scope.launch { press.animateTo(0f, iosSpring(0.25f, 1f)) }
        scope.launch { grow.animateTo(0f, iosSpring(0.25f, 1f)) }
        scope.launch { bar.animateTo(0f, iosSpring(0.44f, 0.63f)) }
        scope.launch { pan.animateTo(0f, iosSpring(0.44f, 0.63f)) }
        scope.launch { glow.animateTo(0f, iosSpring(0.65f, 0.87f)) }
        scope.launch { glowSize.animateTo(4f, iosSpring(0.63f, 0.9f)) }
        go(index, iosSpring(0.4f, 0.85f))
    }

    /** 不按下，直接滑过去 */
    fun slideTo(index: Float) = go(index, iosSpring(0.4f, 0.85f))

    private fun go(index: Float, spec: AnimationSpec<Float>) {
        scope.launch { position.animateTo(index.coerceIn(0f, last), spec) }
        runStretch()
    }

    private fun runStretch() {
        if (stretchJob?.isActive == true) return
        stretchJob = scope.launch {
            var prevTime = withFrameNanos { it }
            var prevX = position.value
            var quiet = 0
            while (quiet < 3) {
                val time = withFrameNanos { it }
                val dt = ((time - prevTime) / 1e9f).coerceIn(1e-4f, 0.05f)
                val v = abs(position.value - prevX) * unitDp / dt / 1000f
                prevTime = time
                prevX = position.value
                val w = StretchOmega
                stretchVelocity += (w * w * (v - stretch) - 2f * StretchDamping * w * stretchVelocity) * dt
                stretch += stretchVelocity * dt
                quiet = if (!held && v < 1e-3f && abs(stretch) < 1e-4f && abs(stretchVelocity) < 1e-3f) quiet + 1 else 0
            }
            stretch = 0f
            stretchVelocity = 0f
        }
    }
}

/** iOS 的弹簧写法（response 秒、dampingRatio）换成 Compose 的 spring（质量 1） */
private fun iosSpring(response: Float, dampingRatio: Float): AnimationSpec<Float> {
    val w = 2f * PI.toFloat() / response
    return spring(dampingRatio = dampingRatio, stiffness = w * w, visibilityThreshold = 0.0005f)
}

/**
 * 选中块背后的模糊（iOS 的 UITabSelectionView：在 1/4 分辨率上取样再 gaussianBlur 半径 2）。
 * 写成等效的高斯 sigma：竖条纹上量选中块里外条纹的衰减，扣掉选中矩阵的增益，得到 2.9pt
 */
internal val SelectionBlur = 2.9.dp

/** 按下时底栏上的图标放大多少（1 + 0.16p） */
internal const val IconPressScale = 0.16f

private val GlassLabelStyle = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** 缓存一条平移过的胶囊路径：尺寸和位置都没变时返回同一个 [Path]，变了才重建 */
internal class CapsuleClip {
    private val path = Path()
    private var key = FloatArray(4) { Float.NaN }

    fun get(w: Float, h: Float, x: Float, y: Float, scope: DrawScope): Path {
        if (key[0] != w || key[1] != h || key[2] != x || key[3] != y) {
            key = floatArrayOf(w, h, x, y)
            path.reset()
            path.addOutline(MochiCapsule.createOutline(Size(w, h), scope.layoutDirection, scope))
            path.translate(Offset(x, y))
        }
        return path
    }
}
