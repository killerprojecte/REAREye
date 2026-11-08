// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.shadow.drawMochiShadow
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.rememberMochiHaptics

/** 索引条的几何：竖条按字母数均分，手指的纵坐标换成第几个 */
internal object IndexBarGeometry {
    fun indexAt(y: Float, height: Float, count: Int): Int {
        if (count <= 0 || height <= 0f) return 0
        return (y / height * count).toInt().coerceIn(0, count - 1)
    }

    /** 第 i 个字母的中心 */
    fun centerOf(i: Int, height: Float, count: Int): Float = if (count <= 0) 0f else (i + 0.5f) * height / count
}

public object MochiIndexBarDefaults {
    /** 索引条的宽度（触摸范围也是这么宽）。贴着屏幕右边放，旁边的列表内容右边让出这么多，不和字母叠在一起 */
    public val Width: Dp = 20.dp
}

/**
 * 字母索引条（通讯录右边的 A–Z）：按住或滑动时每换一个字母轻震一下并回调 [onSelect]（由调用方滚动列表），
 * 左边弹出一个放大的字母气泡跟着手指，字母切换时模糊替换；松手气泡缩回。
 *
 * 只占 [MochiIndexBarDefaults.Width] 宽，气泡画在条外面、不占位置。直接贴着屏幕右边放（`Alignment.CenterEnd`，不要再加右边距），
 * 字母中心离屏幕边 10dp，和 iOS 通讯录一样。
 */
@Composable
public fun MochiIndexBar(labels: ImmutableList<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val select by rememberUpdatedState(onSelect)
    var current by remember { mutableIntStateOf(-1) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    val bubble = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val accent = MochiTheme.colorScheme.primary
    val dark = MochiTheme.colorScheme.isDark
    val shadow = MochiTheme.shadows.lifted
    Box(modifier.semantics { contentDescription = "索引" }) {
        Column(
            Modifier
                .width(MochiIndexBarDefaults.Width)
                .pointerInput(labels.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun track(y: Float) {
                            fingerY = y.coerceIn(0f, size.height.toFloat())
                            val i = IndexBarGeometry.indexAt(y, size.height.toFloat(), labels.size)
                            if (i != current) {
                                current = i
                                haptics.tick()
                                select(i)
                            }
                        }
                        scope.launch { bubble.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f)) }
                        track(down.position.y)
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            track(c.position.y)
                        }
                        current = -1
                        scope.launch { bubble.animateTo(0f, spring(dampingRatio = 1f, stiffness = 700f)) }
                    }
                }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            labels.forEach {
                Text(
                    it,
                    style = MochiTheme.textStyles.footnote.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                    color = accent,
                    maxLines = 1,
                )
            }
        }
        // 放大的字母气泡：在条的左边，纵向跟着手指
        val label = labels.getOrNull(current.coerceAtLeast(0)) ?: ""
        Box(
            Modifier
                .align(Alignment.TopStart)
                // 气泡画在条外面、不占位置：索引条只有 20dp 宽，贴着屏幕边放时字母就在边上
                .layout { m, _ ->
                    val p = m.measure(Constraints())
                    layout(0, 0) { p.place(-BubbleOffset.roundToPx() - p.width, (fingerY - p.height / 2f).roundToInt()) }
                }
                .graphicsLayer {
                    val s = bubble.value.coerceAtLeast(0f)
                    scaleX = s
                    scaleY = s
                    alpha = s.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .size(BubbleSize)
                .drawWithCache {
                    val path = Path().apply { addOval(Rect(Offset.Zero, size)) }
                    onDrawBehind { drawMochiShadow(path, shadow.resolve(this, dark), Color.Black) }
                }
                .background(MochiDefaults.groupedContainer(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MochiBlurText(
                label,
                style = MochiTheme.textStyles.body.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                color = accent,
                contentAlignment = Alignment.Center,
            )
        }
    }
}

private val BubbleSize = 56.dp
private val BubbleOffset = 12.dp
