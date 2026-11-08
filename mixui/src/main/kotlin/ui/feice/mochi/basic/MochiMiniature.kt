// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 缩小版的真实界面：[content] 按放大 1 / [scale] 倍的尺寸排版，再整体缩回到这块地方，字号、控件、留白都是真实比例，
 * 不是把字号调小拼出来的「示意图」。外观选择、主题预览这类卡片里用（常放在 [MochiSelectFrame] 里）。
 * 想让预览用另一套配色，在外面套一层 MochiTheme。
 *
 * 只是画出来看，里面的控件仍然能收到触摸；不想让它被点到就放进 [MochiSelectFrame]，或者自己在上面盖一层接住点击。
 * @param scale 0..1，缩小到多少
 */
@Composable
public fun MochiMiniature(scale: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(maxWidth / scale, maxHeight / scale)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) { content() }
    }
}
