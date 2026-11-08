// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextMotion
import kotlin.math.abs
import ui.feice.mochi.basic.Text
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.LocalTextStyle

/**
 * 计数器样式的数字：每一位是一个 0–9 循环的滚轮，数字变了滚轮真的转过去（6 变 1 依次经过 5、4、3、2），
 * 画在一段圆弧上，滚得越快越小、越淡、越糊（模糊走 [mochiBlur]），停下才清晰；落定时带一点回弹，
 * 连按时接着当前的速度滚，掉头也不会中途闪清楚。位数变多时新的高位从下面升上来，变少时沉下去，
 * 旁边的数字被平滑推开。用等宽数字（tnum），个位变来变去时整串不会左右抖。
 *
 * 绝对值变大往前滚（新数字从下面上来），变小往回滚。
 *
 * @param format 数字怎么显示（千分位、单位、小数……），各位从右往左对齐着比；数字以外的字符（¥、逗号、单位）
 *   变了做模糊替换（[transition]）
 */
@Composable
public fun MochiCounterText(
    value: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    transition: MochiBlurTransition = MochiBlurTransitions.Counter,
    format: (Long) -> String = { it.toString() },
) {
    val last = remember { LongRef(value) }
    // 方向只在值变的那次组合里算（和上一次的值比绝对值：−5 变 −6 也是往前滚）
    val direction = remember(value) {
        val d = abs(value).compareTo(abs(last.v))
        last.v = value
        d
    }
    MochiCounterText(format(value), direction, modifier, color, style, transition)
}

/**
 * 计数器样式的文字：和上一次的文字从右往左逐位比，数字位按 [direction] 滚过去（+1 往前、新数字从下面上来，
 * −1 往回，0 走近的那边），其余字符变了做模糊替换。适合自己格式化好的数字（价格、时长、百分比）。
 */
@Composable
public fun MochiCounterText(
    text: String,
    direction: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    transition: MochiBlurTransition = MochiBlurTransitions.Counter,
) {
    val digits = remember(style) {
        style.merge(TextStyle(fontFeatureSettings = "tnum", textMotion = style.textMotion ?: TextMotion.Animated))
    }
    val ink = when {
        color != Color.Unspecified -> color
        digits.color != Color.Unspecified -> digits.color
        else -> LocalContentColor.current
    }
    val glyphs = rememberDigitGlyphs(digits)
    // 位数只增不减：少掉的位留着一个空位，好让它沉下去、宽度缩回 0
    val slots = remember { IntRef(0) }
    slots.v = maxOf(slots.v, text.length)
    // 第一次组合时已经在的位直接停在位上；之后才出现的位从下面升上来
    val first = remember { BoolRef(true) }
    SideEffect { first.v = false }
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }, verticalAlignment = Alignment.CenterVertically) {
        for (i in slots.v - 1 downTo 0) {
            val ch = text.getOrNull(text.length - 1 - i)
            val isDigit = ch == null || ch in '0'..'9'
            key(i) {
                // 数字（和空位）都交给滚轮，滚轮自己管升起 / 沉下；数字和别的字符互换时两者之间做模糊替换
                MochiBlurCrossfade(if (isDigit) WheelSlot else ch, transition = transition, direction = direction) { c ->
                    if (c === WheelSlot) {
                        CounterWheel(if (isDigit) ch?.minus('0') else null, direction, born = !first.v, glyphs, ink)
                    } else {
                        Text(c.toString(), color = color, style = digits, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 这一位现在是滚轮（数字或空位） */
private object WheelSlot

private class LongRef(var v: Long)

private class IntRef(var v: Int)

private class BoolRef(var v: Boolean)
