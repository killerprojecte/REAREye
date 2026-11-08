// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import ui.feice.mochi.effect.glass.engine.GlassBody
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.MochiGlass
import ui.feice.mochi.theme.MochiTheme

/**
 * 一块胶囊形的玻璃（连续曲率，宽高相等时就是圆），里面放 [content]：iOS 浮在内容上的圆钮、搜索条这类。
 * 画法和玻璃标签栏同一套：轮廓外面一圈（细描边、黑环、外阴影）画在玻璃下面，玻璃本体按 [material] 的配方取样 [backdrop]。
 * 配方跟主题的深浅取 [MochiGlass.light] / [MochiGlass.dark]；[backdrop] 为 null 时画成 [MochiGlass.fallback] 纯色。
 * 只是外观，不处理点击和按压形变。
 */
@Composable
public fun MochiGlassCapsule(
    backdrop: MochiBackdrop?,
    modifier: Modifier = Modifier,
    material: MochiGlass? = MochiTheme.materials.bar,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    if (material == null || backdrop == null) {
        Box(
            modifier.background(material?.fallback ?: MochiTheme.colorScheme.surfaceContainer, MochiCapsule),
            contentAlignment,
            content = content,
        )
        return
    }
    val recipe = if (MochiTheme.colorScheme.isDark) material.dark else material.light
    Box(modifier, contentAlignment) {
        GlassBody(backdrop.layer, { recipe }, Modifier.matchParentSize(), faceBackdrop = backdrop.face)
        content()
    }
}
