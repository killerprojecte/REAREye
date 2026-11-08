// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic.parts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.theme.MochiIcon

/**
 * 新组件共用的几个小符号。都先找 [ui.feice.mochi.theme.LocalMochiIconProvider] 里的同名图标（接了图标库就用图标库的），
 * 没有才用这里手画的兜底，线宽和 iOS SF Symbols 的半粗差不多。
 */
internal object MochiGlyphs {
    /** › 箭头。[rotation]：0 = 朝右，90 = 朝下，180 = 朝左，-90 = 朝上 */
    @Composable
    fun Chevron(
        tint: Color,
        modifier: Modifier = Modifier,
        size: Dp = 14.dp,
        rotation: () -> Float = { 0f },
        key: String = "chevron-forward",
    ) {
        MochiIcon(key, modifier.size(size).graphicsLayer { rotationZ = rotation() }, tint = tint) { slot ->
            Canvas(slot) {
                val w = 2.dp.toPx()
                run {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val hh = this.size.height * 0.36f
                    val hw = hh * 0.55f
                    val p = Path().apply {
                        moveTo(cx - hw, cy - hh)
                        lineTo(cx + hw, cy)
                        lineTo(cx - hw, cy + hh)
                    }
                    drawPath(p, tint, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }

    /** 上下双箭头（iOS 菜单式选择器行尾的 chevron.up.chevron.down） */
    @Composable
    fun UpDown(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
        MochiIcon("chevron.up.chevron.down", modifier.size(width = size * 0.7f, height = size), tint = tint) { slot ->
            Canvas(slot) {
                val w = 1.7.dp.toPx()
                val cx = this.size.width / 2f
                val hw = this.size.width / 2f - w / 2f
                val h = this.size.height
                val up = Path().apply {
                    moveTo(cx - hw, h * 0.36f)
                    lineTo(cx, h * 0.12f)
                    lineTo(cx + hw, h * 0.36f)
                }
                val down = Path().apply {
                    moveTo(cx - hw, h * 0.64f)
                    lineTo(cx, h * 0.88f)
                    lineTo(cx + hw, h * 0.64f)
                }
                val s = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(up, tint, style = s)
                drawPath(down, tint, style = s)
            }
        }
    }

    /** ✓（列表选中行、菜单选中项、选择卡片的勾选点） */
    @Composable
    fun Check(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
        MochiIcon("checkmark", modifier.size(size), tint = tint) { slot ->
            Canvas(slot) {
                val w = this.size.width
                val h = this.size.height
                val p = Path().apply {
                    moveTo(w * 0.18f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.76f)
                    lineTo(w * 0.84f, h * 0.26f)
                }
                drawPath(p, tint, style = Stroke((2f * size.value / 20f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }

    /** ✕ */
    @Composable
    fun Close(tint: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
        MochiIcon("close", modifier.size(size), tint = tint) { slot ->
            Canvas(slot) {
                val w = 2.dp.toPx()
                val a = w / 2f
                val b = this.size.width - w / 2f
                drawLine(tint, Offset(a, a), Offset(b, b), w, StrokeCap.Round)
                drawLine(tint, Offset(b, a), Offset(a, b), w, StrokeCap.Round)
            }
        }
    }
}
