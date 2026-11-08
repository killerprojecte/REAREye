// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.feice.mochi.effect.content.MochiBlurVisibility
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiTheme

/** 空状态的尺寸 */
public object MochiEmptyStateDefaults {
    /** 图标区域的大小 */
    public val IconSize: Dp = 56.dp
}

/**
 * 空状态（iOS 的 ContentUnavailableView）：图标、标题、说明和一个可选的操作，整体居中。
 * 第一次出现时模糊淡入（[MochiBlurVisibility]），不瞬切；常见用法：搜索无结果、离线、空列表。
 *
 * @param icon 图标，默认颜色是次要文字色（通过 [LocalContentColor] 给）
 * @param action 操作按钮，通常是 [MochiButton]
 */
@Composable
public fun MochiEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    MochiBlurVisibility(shown, modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                CompositionLocalProvider(LocalContentColor provides MochiTheme.colorScheme.onSurfaceVariant) {
                    Box(Modifier.size(MochiEmptyStateDefaults.IconSize), contentAlignment = Alignment.Center) { icon() }
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                title,
                style = MochiTheme.textStyles.body.copy(
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                color = MochiTheme.colorScheme.onSurface,
            )
            if (message != null) {
                Text(
                    message,
                    style = MochiTheme.textStyles.subhead.copy(textAlign = TextAlign.Center),
                    color = MochiTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                action()
            }
        }
    }
}
