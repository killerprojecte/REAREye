// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // LocalMochiIconDraw：图标出场进度，和 LocalMochiIconProvider 一样是主题级

package ui.feice.mochi.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight

/** Resolve stable semantic keys; null preserves the caller's original icon. */
public fun interface MochiIconProvider {
    /** Resolve the requested original or filled variant; unknown keys return null. */
    @Composable
    public fun resolve(key: String, filled: Boolean): ImageVector?
}

/** Current optional provider. mochi-compose itself ships no additional icon or font assets. */
public val LocalMochiIconProvider: androidx.compose.runtime.ProvidableCompositionLocal<MochiIconProvider?> =
    staticCompositionLocalOf { null }

/** Install a provider without replacing component geometry or interaction. */
@Composable
public fun ProvideMochiIcons(provider: MochiIconProvider?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMochiIconProvider provides provider, content = content)
}

/** Draw in the caller's existing slot; keep a fallback for unknown keys and default Mochi appearance. */
@Composable
public fun MochiIcon(
    key: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
    contentDescription: String? = null,
    fallback: @Composable (Modifier) -> Unit,
) {
    val vector = LocalMochiIconProvider.current?.resolve(key, filled)
    if (vector == null) {
        // No wrapper in the default path: existing Mochi measurement stays byte-for-byte equivalent.
        fallback(modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Image(rememberVectorPainter(vector), contentDescription, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(tint))
        }
    }
}

/**
 * 符号图标的字重（iOS 的 SF Symbols 按旁边文字的字体取字重：正文常规、标题半粗……）。
 * 列表行把标题的字重放进来，描边图标按它加粗或变细；图标库自己画的图标可以读它决定线宽
 */
public val LocalMochiSymbolWeight: androidx.compose.runtime.ProvidableCompositionLocal<FontWeight> =
    staticCompositionLocalOf { FontWeight.Normal }

/**
 * 图标的描绘进度（0 还没画 … 1 画完），在组合里读。带图标的对话框等在图标出现时提供它：
 * 图标库（mochi-icons 的 MochiIonicon）按它把描边一笔一笔画出来、最后填上实心部分，所有带图标的对话框是同一种出场。
 * null（默认）= 直接画完整的图标。自己画的图标也可以读它做同样的出场
 */
public val LocalMochiIconDraw: androidx.compose.runtime.ProvidableCompositionLocal<(() -> Float)?> =
    staticCompositionLocalOf { null }
