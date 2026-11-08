// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.LocalTextStyle

/**
 * 文字。颜色不传就用 [LocalContentColor]，样式不传就用 [LocalTextStyle]，
 * 所以放在按钮、菜单这些容器里会自动拿到容器的前景色。
 *
 * @param minFontScale 放不下时先把字号缩小（最小缩到 字号 × minFontScale），还放不下才按 [overflow] 省略。
 *   iOS 标题的做法：长标题宁可小一点也尽量显示全。1 = 不缩（默认）
 */
@Composable
public fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    minFontScale: Float = 1f,
) {
    MochiMeasuredText(text, modifier, color, style, maxLines, overflow, softWrap, minFontScale)
}

/**
 * 设为 true 时，这棵子树里的 [Text] 换字体（字族、字重、字号这些排版属性）时原位模糊替换：旧字体的文字变糊淡出，
 * 新字体的从糊里清晰起来，只有文字自己在糊，不用整屏截图做模糊转场。颜色变化不算换字体，照常跟着配色插值。
 *
 * 默认 false：大多数页面只在主题页换字体，那里已经有整屏的转场；整页文字同时做模糊替换会多出很多模糊图层。
 * 适合文字不多、要在原地换整套风格的地方（比如引导里选主题）
 */
public val LocalMochiFontCrossfade: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Internal layout hook shares the public text renderer, including font resolution and autosizing. */
@Composable
internal fun MochiMeasuredText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    minFontScale: Float = 1f,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val c = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val size = style.fontSize
    val autoSize = if (minFontScale < 1f && size.isSpecified) {
        TextAutoSize.StepBased(minFontSize = size * minFontScale.coerceIn(0.3f, 1f), maxFontSize = size, stepSize = 0.5.sp)
    } else {
        null
    }
    if (LocalMochiFontCrossfade.current) {
        // 按排版属性比（去掉颜色）：配色插值每帧都在变颜色，不能每帧都触发一次替换
        val font = style.copy(color = Color.Unspecified)
        MochiBlurCrossfade(font, modifier, contentAlignment = Alignment.TopStart) { s ->
            BasicText(
                text = text,
                style = s.merge(TextStyle(color = c, textMotion = s.textMotion ?: TextMotion.Animated)),
                maxLines = maxLines,
                overflow = overflow,
                softWrap = softWrap,
                autoSize = autoSize,
                onTextLayout = if (s == font) onTextLayout else null,
            )
        }
        return
    }
    BasicText(
        text = text,
        modifier = modifier,
        // 没指定 textMotion 时用 Animated：见 MochiTextMotion
        style = style.merge(TextStyle(color = c, textMotion = style.textMotion ?: TextMotion.Animated)),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        autoSize = autoSize,
        onTextLayout = onTextLayout,
    )
}

private inline fun Color.takeOrElse(block: () -> Color): Color = if (this != Color.Unspecified) this else block()
