// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay

/**
 * 预渲染：[content] 先正常画几帧，然后整块截成一张位图，之后只画这张图、不再组合和绘制 [content]。
 * 列表里放一张很贵的静态预览（带玻璃、模糊的缩小界面）时用：滚动时只是贴图，不会每帧重算玻璃。
 * [key] 变了（比如换了样式、深浅色）就重新画一次再截。
 *
 * 截图之前会等 [settleMillis]：玻璃、模糊要等背景录好、动画走完才画得对。
 * 截下来的是静态图，里面的控件不能点，也不会再动；内容要跟着状态变的地方别用。
 */
@Composable
public fun MochiPrerendered(key: Any, modifier: Modifier = Modifier, settleMillis: Long = 250L, content: @Composable BoxScope.() -> Unit) {
    var image by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    val shot = image
    if (shot != null) {
        Image(shot, contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds)
        return
    }
    val layer = rememberGraphicsLayer()
    Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        },
        content = content,
    )
    LaunchedEffect(key) {
        repeat(4) { withFrameNanos { } }
        delay(settleMillis)
        // 在列表里还没画过（滚在屏幕外时只组合不绘制）时图层是空的，截不了图：等它真的画出来
        while (layer.size.width <= 0 || layer.size.height <= 0) withFrameNanos { }
        image = layer.toImageBitmap()
    }
}
