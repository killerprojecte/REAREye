// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // Deliberate theme token, alongside LocalMochiMaterials.

package ui.feice.mochi.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import ui.feice.mochi.effect.metaball.MochiMetaballShadow

/**
 * 一档阴影：大而淡、往里收的环境影 + 小而很淡的接触影（见 [MochiMetaballShadow]）。
 * 不透明度是乘在阴影颜色上的系数，浅色、深色各一份（深色背景上要更重才看得出）。
 * 只要一层贴着的软影（控件拇指）时把环境影的不透明度设成 0
 */
@Immutable
public data class MochiShadowLevel(
    val ambientOffset: Dp,
    val ambientBlur: Dp,
    val ambientInset: Dp,
    val ambientAlpha: Float,
    val ambientAlphaDark: Float,
    val keyOffset: Dp,
    val keyBlur: Dp,
    val keyAlpha: Float,
    val keyAlphaDark: Float,
) {
    /** 换算成画阴影用的像素参数；[visibility] 0..1 整体淡入淡出 */
    public fun resolve(density: Density, dark: Boolean, visibility: Float = 1f): MochiMetaballShadow = with(density) {
        MochiMetaballShadow(
            ambientOffset = ambientOffset.toPx(),
            ambientSigma = ambientBlur.toPx().coerceAtLeast(0.5f),
            ambientAlpha = (if (dark) ambientAlphaDark else ambientAlpha) * visibility,
            keyOffset = keyOffset.toPx(),
            keySigma = keyBlur.toPx().coerceAtLeast(0.5f),
            keyAlpha = (if (dark) keyAlphaDark else keyAlpha) * visibility,
            ambientInset = ambientInset.toPx(),
        )
    }
}

/** 两档之间插值（按压、展开时阴影跟着进度变） */
public fun lerp(start: MochiShadowLevel, stop: MochiShadowLevel, fraction: Float): MochiShadowLevel {
    val t = fraction.coerceIn(0f, 1f)
    fun f(a: Float, b: Float) = a + (b - a) * t
    return MochiShadowLevel(
        ambientOffset = lerp(start.ambientOffset, stop.ambientOffset, t),
        ambientBlur = lerp(start.ambientBlur, stop.ambientBlur, t),
        ambientInset = lerp(start.ambientInset, stop.ambientInset, t),
        ambientAlpha = f(start.ambientAlpha, stop.ambientAlpha),
        ambientAlphaDark = f(start.ambientAlphaDark, stop.ambientAlphaDark),
        keyOffset = lerp(start.keyOffset, stop.keyOffset, t),
        keyBlur = lerp(start.keyBlur, stop.keyBlur, t),
        keyAlpha = f(start.keyAlpha, stop.keyAlpha),
        keyAlphaDark = f(start.keyAlphaDark, stop.keyAlphaDark),
    )
}

/**
 * 阴影档位表：组件只选档位，具体数值在这里统一调，换一套就是整体换阴影风格（比如全部更平、或者更有层次）。
 * 用 `MochiTheme(shadows = MochiShadows(floating = …))` 换掉某一档。
 *
 * - [contact]：贴着底的控件零件，开关和滑块的拇指、分段控件的选中块；只有一层很软的接触影
 * - [contactPressed]：按住拖动的拇指，影子散开一点、深一点
 * - [low]：轨道上的小浮块（步进滑条的滑块）
 * - [raised]：常驻的浮动栏（标签栏静止时）
 * - [lifted]：抬起一些的浮动元素（按住的标签栏、索引条、提示条）
 * - [floating]：盖在页面上的面板（展开的菜单、气泡、警告框、侧边栏）
 *
 * 菜单、气泡展开时在 [rest] 和 [floating] 之间按进度插值。
 */
@Immutable
public data class MochiShadows(
    val contact: MochiShadowLevel = Contact,
    val contactPressed: MochiShadowLevel = ContactPressed,
    val low: MochiShadowLevel = elevation(0.2f),
    val raised: MochiShadowLevel = elevation(0.35f),
    val lifted: MochiShadowLevel = elevation(0.6f),
    val floating: MochiShadowLevel = elevation(1f),
    val rest: MochiShadowLevel = elevation(0f),
) {
    public companion object {
        /** 没有阴影：放进 `MochiTheme(shadows = …)` 就是全平的外观 */
        public val None: MochiShadows = MochiShadowLevel(0.dp, 0.dp, 0.dp, 0f, 0f, 0.dp, 0.dp, 0f, 0f).let {
            MochiShadows(it, it, it, it, it, it, it)
        }
    }
}

// 拇指静止：只有一层 3dp 的软影，颜色系数 0.5（拇指阴影色默认 22% 黑，乘出来 11%）
private val Contact = MochiShadowLevel(0.dp, 0.dp, 0.dp, 0f, 0f, 1.5.dp, 3.dp, 0.5f, 0.5f)

private val ContactPressed = MochiShadowLevel(0.dp, 0.dp, 0.dp, 0f, 0f, 3.5.dp, 7.dp, 0.8f, 0.8f)

/**
 * 浮起程度 0..1 的一档：越高越淡越散（大尺寸的玻璃投更大更柔的影子）。
 * 环境影往里收再大半径模糊，形状边上只有很淡的一点；接触影很软很淡，只让边缘有个落点，不压出硬线
 */
internal fun elevation(e: Float): MochiShadowLevel {
    fun f(a: Float, b: Float) = a + (b - a) * e
    return MochiShadowLevel(
        ambientOffset = f(4f, 14f).dp,
        ambientBlur = f(10f, 28f).dp,
        ambientInset = f(2f, 10f).dp,
        ambientAlpha = f(0.07f, 0.11f),
        ambientAlphaDark = f(0.16f, 0.26f),
        keyOffset = f(0.5f, 1.5f).dp,
        keyBlur = f(2.5f, 4f).dp,
        keyAlpha = f(0.025f, 0.035f),
        keyAlphaDark = f(0.10f, 0.14f),
    )
}

internal val LocalMochiShadows = staticCompositionLocalOf { MochiShadows() }
