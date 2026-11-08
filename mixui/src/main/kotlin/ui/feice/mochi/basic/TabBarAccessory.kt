// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.effect.glow.mochiPressGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.effect.shadow.drawMochiShadow
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiShadowLevel
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.smoothstep

/** 胶囊底板：柔和投影 + 底色（形状每帧按当前大小取，圆角永远是短边一半） */
private fun Modifier.capsulePlate(path: G2PathCache, color: Color, shadow: Color, level: MochiShadowLevel, dark: Boolean): Modifier =
    drawBehind {
        val p = path.get(size.toRect(), size.minDimension / 2f, 1f)
        drawMochiShadow(size.toRect(), size.minDimension / 2f, level.resolve(this, dark), shadow)
        drawPath(p, color)
    }

/** 纯色标签栏的胶囊底板：柔和投影 + 底色 */
@Composable
internal fun solidPlate(colors: MochiTabBarColors, dark: Boolean): TabBarPlate {
    val path = remember { G2PathCache() }
    return TabBarPlate(Modifier.capsulePlate(path, colors.container, colors.shadow, MochiTheme.shadows.raised, dark))
}

/**
 * 附件条、搜索框的底板：纯色标签栏是 [modifier] 画的投影 + 底色；玻璃标签栏是 [under] 画在内容下面的一块玻璃
 * （玻璃的外阴影、亮边在胶囊外面，不能和内容一起被胶囊裁掉）
 */
internal class TabBarPlate(val modifier: Modifier = Modifier, val under: (@Composable BoxScope.() -> Unit)? = null)

/** 标签栏上方的附件条；收起时挪到小圆右边同一行（位置由 [rect] 给），内容按 [inline] 模糊替换成紧凑版 */
@Composable
internal fun TabBarAccessory(
    rect: () -> Rect,
    alpha: () -> Float,
    inline: Boolean,
    blocked: Boolean,
    colors: MochiTabBarColors,
    plate: TabBarPlate,
    content: @Composable (inline: Boolean) -> Unit,
) {
    val glow = rememberMochiGlowState()
    Box(
        Modifier
            .placedAt(rect)
            .graphicsLayer { this.alpha = alpha() }
            .mochiPressDeform()
            .then(plate.modifier),
    ) {
        plate.under?.invoke(this)
        AccessoryContent(blocked, colors, glow, inline, content)
    }
}

@Composable
private fun BoxScope.AccessoryContent(
    blocked: Boolean,
    colors: MochiTabBarColors,
    glow: MochiGlowState,
    inline: Boolean,
    content: @Composable (inline: Boolean) -> Unit,
) {
    Box(
        Modifier
            .matchParentSize()
            .mochiPressGlow(glow, MochiCapsule)
            // 搜索时附件条淡出，期间不响应点击
            .then(
                if (blocked) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown(pass = PointerEventPass.Initial).consume() }
                    }
                } else {
                    Modifier
                },
            )
            .clip(MochiCapsule),
        contentAlignment = Alignment.CenterStart,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.selectedContent) {
            MochiBlurCrossfade(inline, Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { content(it) }
        }
    }
}

/** 标签栏右边的搜索圆钮：点开后（位置由 [rect] 给）展开成搜索框，输入框随 [progress] 淡入并自动聚焦 */
@Composable
internal fun TabBarSearchField(
    search: MochiTabBarSearch,
    rect: () -> Rect,
    progress: () -> Float,
    height: Dp,
    colors: MochiTabBarColors,
    plate: TabBarPlate,
) {
    val glow = rememberMochiGlowState()
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    // 只在从搜索中退出时清焦点（第一次组合不动别处的焦点）
    val wasActive = remember { booleanArrayOf(search.active) }
    LaunchedEffect(search.active) {
        if (search.active) {
            requester.requestFocus()
        } else if (wasActive[0]) {
            focus.clearFocus()
        }
        wasActive[0] = search.active
    }
    Box(Modifier.placedAt(rect).mochiPressDeform().then(plate.modifier)) {
        plate.under?.invoke(this)
        SearchContent(search, progress, height, colors, glow, requester)
    }
}

@Composable
private fun BoxScope.SearchContent(
    search: MochiTabBarSearch,
    progress: () -> Float,
    height: Dp,
    colors: MochiTabBarColors,
    glow: MochiGlowState,
    requester: FocusRequester,
) {
    val cs = MochiTheme.colorScheme
    val currentSearch by rememberUpdatedState(search)
    Box(
        Modifier
            .matchParentSize()
            .mochiPressGlow(glow, MochiCapsule)
            .clip(MochiCapsule)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                if (currentSearch.active) requester.requestFocus() else currentSearch.onActiveChange(true)
            }
            .semantics { contentDescription = search.placeholder },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 放大镜所在的格子和圆钮一样大：没展开时它就在圆心，展开后留在左端（和胶囊圆头同心）
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(height), contentAlignment = Alignment.Center) {
                val icon = search.icon
                if (icon != null) icon(colors.content) else MagnifierGlyph(colors.content, glyphSize = 22.dp)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = height - 8.dp, end = 20.dp)
                .graphicsLayer { alpha = smoothstep(0.35f, 1f, progress()) },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (search.query.isEmpty()) {
                Text(
                    search.placeholder,
                    style = MochiTheme.textStyles.bodyLarge,
                    color = colors.content,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = search.query,
                onValueChange = search.onQueryChange,
                enabled = search.active,
                singleLine = true,
                textStyle = MochiTheme.textStyles.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { currentSearch.onSearch(currentSearch.query) }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
        }
    }
}
