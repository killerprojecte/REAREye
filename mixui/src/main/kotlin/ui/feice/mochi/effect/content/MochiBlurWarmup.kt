// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * 启动时把每一档模糊（见 [mochiCachedBlur]）在一块看不见的离屏画布上各画一次。
 *
 * 渲染线程第一次用到某个模糊半径时要现编着色器程序，编译要几毫秒到十几毫秒；不预热时这些编译正好落在
 * 第一次出现的过渡动画中间（引导页「糊着升起来」会把所有档位扫一遍），第一次过必掉帧。
 * 渲染线程和它的 GPU 上下文是整个进程共用的，这里编好的程序之后直接复用，也会进系统的着色器磁盘缓存。
 * 界面里不加任何节点；每个进程只做一次，在后台线程发起。
 */
@RequiresApi(31)
internal object MochiBlurWarmup {
    private val started = AtomicBoolean(false)

    fun run() {
        if (!started.compareAndSet(false, true)) return
        val size = 64
        val reader = ImageReader.newInstance(
            size,
            size,
            PixelFormat.RGBA_8888,
            2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val root = RenderNode("mochi-blur-warmup").apply { setPosition(0, 0, size, size) }
            val content = RenderNode("mochi-blur-warmup-content").apply { setPosition(0, 0, size, size) }
            val paint = Paint().apply { color = Color.BLACK }
            renderer.setContentRoot(root)
            for (level in 0..MaxBlurLevel) {
                val r = StepRatio.pow(level)
                // 和 mochiCachedBlur 同样的半径、同样的边缘处理（Decal），编出来的是同一个程序
                content.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
                content.beginRecording().apply { drawCircle(size / 2f, size / 2f, size / 4f, paint) }
                content.endRecording()
                root.beginRecording().apply {
                    drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                    drawRenderNode(content)
                }
                root.endRecording()
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                reader.acquireLatestImage()?.close()
            }
        } catch (_: Throwable) {
            // 预热失败不影响任何功能，只是第一次过渡可能多编一次着色器
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}
