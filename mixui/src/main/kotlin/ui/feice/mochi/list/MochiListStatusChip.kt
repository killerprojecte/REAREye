// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ui.feice.mochi.basic.Text
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.mochiConcentricOutline
import ui.feice.mochi.theme.MochiTheme

/**
 * 右下角的具名状态胶囊（如置顶）：图标 + 文字，默认放入 [MochiListRow] 的 status，与副标题底部对齐。
 * 这是只读状态，不是操作按钮；仅未读使用小圆点。图标限制在 14dp，使用传入的前景色。
 */
@Composable
public fun MochiListStatusChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: (@Composable (tint: Color) -> Unit)? = null,
    containerColor: Color = MochiTheme.colorScheme.primary,
    contentColor: Color = MochiTheme.colorScheme.onPrimary,
) {
    Row(
        modifier.mochiConcentricOutline().background(containerColor, MochiCapsule).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { icon(contentColor) }
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = MochiTheme.textStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
