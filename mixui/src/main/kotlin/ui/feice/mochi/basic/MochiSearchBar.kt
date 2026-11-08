// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.GlassSurface
import ui.feice.mochi.effect.glass.barGlassSource
import ui.feice.mochi.effect.glass.engine.rememberGlassGlow
import ui.feice.mochi.effect.glass.rememberGlassFill
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.MochiIcon
import ui.feice.mochi.theme.MochiTheme

public object MochiSearchBarDefaults {
    /** 输入胶囊的高度；并排的按钮（如侧边栏头部的「新建」）用同一个值 */
    public val Height: Dp = 36.dp
}

/**
 * iOS 的搜索栏：
 * - 胶囊输入框，左边放大镜，有内容时右边出现清除按钮
 * - 聚焦时右边滑进来一颗圆形 ✕ 按钮（iOS 26 的样子，带按压形变和发光），输入框同步收窄让位；
 *   点它清空、收起键盘、按钮滑出去
 * - 清除按钮看起来 18dp，可点区域按整个输入框高度算
 *
 * 想要 iOS「往下拉才露出搜索栏」的效果，把它放进 MochiLargeTitleScaffold 的 searchBar。
 */
@Composable
public fun MochiSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    /** 右边 ✕ 按钮的无障碍说明 */
    cancelText: String = "取消",
    onSearch: (String) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val cs = MochiTheme.colorScheme
    val ts = MochiTheme.textStyles
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val p by animateFloatAsState(if (focused) 1f else 0f, MochiMotions.responseSpring(0.38f, 0.86f), label = "cancel")
    val fieldColor = cs.onSurface.copy(alpha = 0.08f)
    val hint = cs.onSurfaceVariant

    // 放在提供了背景来源的栏里（标题脚手架的顶栏）且主题有 controlGlass 时，输入框画成玻璃；正文里的仍是纯色底
    val glass = rememberGlassFill(GlassRole.Control)
    val fieldInteraction = remember { MutableInteractionSource() }
    val glassGlow = if (glass != null) rememberGlassGlow(fieldInteraction) else null
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(MochiSearchBarDefaults.Height)) {
            if (glass != null) GlassSurface(glass, null, glassGlow, Modifier.matchParentSize())
            Row(
                Modifier
                    .matchParentSize()
                    .then(if (glass == null) Modifier.background(fieldColor, MochiCapsule) else Modifier)
                    .clickable(fieldInteraction, indication = null) { requester.requestFocus() }
                    // 放大镜离左端和离上下一样远（(36 − 18) / 2）：它和胶囊的圆头同心
                    .padding(start = 9.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MagnifierGlyph(hint)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, style = ts.bodyLarge, color = hint, maxLines = 1)
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = ts.bodyLarge.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requester)
                            .onFocusChanged { focused = it.isFocused },
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 36.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onQueryChange("") }
                            .semantics { contentDescription = "清除" },
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(Modifier.size(18.dp).background(hint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                            ClearGlyph(cs.surface)
                        }
                    }
                }
            }
        }
        // ✕ 按钮：宽度和透明度都跟着聚焦进度走，输入框靠 weight 自动让位
        Box(
            Modifier
                .layout { m, c ->
                    val pl = m.measure(c.copy(minWidth = 0))
                    // 弹簧收回时会略微冲过 0，宽度不能是负的
                    val w = (pl.width * p).roundToInt().coerceAtLeast(0)
                    layout(w, pl.height) { pl.place(0, 0) }
                }
                .graphicsLayer {
                    alpha = p.coerceIn(0f, 1f)
                    val s = 0.6f + 0.4f * p.coerceIn(0f, 1f)
                    scaleX = s
                    scaleY = s
                },
        ) {
            MochiIconButton(
                onClick = {
                    onQueryChange("")
                    focus.clearFocus()
                    onCancel()
                },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { contentDescription = cancelText },
                // iOS 26 搜索栏旁的取消钮是玻璃圆钮；主题没有玻璃时是和搜索框同色的纯色圆
                style = MochiButtonStyle.Glass,
                colors = MochiButtonDefaults.colors(
                    MochiButtonStyle.Glass,
                ).copy(container = fieldColor, pressedContainer = fieldColor, content = cs.onSurface),
                size = 36.dp,
            ) { ClearGlyph(cs.onSurface, glyphSize = 12.dp, stroke = 1.8.dp) }
        }
    }
}

@Composable
internal fun MagnifierGlyph(color: Color, glyphSize: Dp = 18.dp) {
    MochiIcon("search", Modifier.size(glyphSize), tint = color) { slot ->
        Canvas(slot) {
            val s = size.width
            val stroke = Stroke(s * 0.11f, cap = StrokeCap.Round)
            drawCircle(color, radius = s * 0.3f, center = Offset(s * 0.43f, s * 0.43f), style = stroke)
            drawLine(color, Offset(s * 0.66f, s * 0.66f), Offset(s * 0.88f, s * 0.88f), strokeWidth = s * 0.11f, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun ClearGlyph(color: Color, glyphSize: Dp = 8.dp, stroke: Dp = 1.6.dp) {
    MochiIcon("close", Modifier.size(glyphSize), tint = color) { slot ->
        Canvas(slot) {
            val w = stroke.toPx()
            drawLine(color, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
        }
    }
}
