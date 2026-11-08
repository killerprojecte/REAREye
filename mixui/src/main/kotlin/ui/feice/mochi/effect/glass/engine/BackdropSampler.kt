// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass.engine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.graphics.Color

/**
 * 取玻璃附近背景的平均颜色（Android 8+），不重画内容、不卡主线程：用 PixelCopy 从窗口已经画好的那一帧里异步复制一小条
 * （缩成 [W]×[H]），后台线程求平均。
 *
 * 取的是玻璃上方紧挨着的一条（玻璃本身在画面里已经盖住了下面的内容）。iOS 也会把玻璃外面一圈算进背后亮度。
 * 以前是把背后内容在另一个渲染器里重画一遍再读回来：背后有别的玻璃或重效果时每次都要从头算，会把 GPU 和主线程一起堵住。
 *
 * 上次复制之后窗口没再画过（画面静止）就不复制：结果不会变，白白让渲染线程等一次 GPU。
 */
internal class BackdropSampler {
    private val thread = HandlerThread("MochiBackdropSample").apply { start() }
    private val handler = Handler(thread.looper)
    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

    @Volatile
    private var busy = false

    // 窗口每画一帧加一；和上次复制时的值一样说明画面没变
    @Volatile
    private var frames = 0L
    private var copiedAt = -1L
    private var observed: View? = null
    private val onDraw = ViewTreeObserver.OnDrawListener { frames++ }

    /** 最近一次读到的平均颜色（sRGB 编码值），还没读到时为 null */
    @Volatile
    var average: Color? = null
        private set

    /** 复制 [view] 所在窗口里 [rect]（窗口坐标）那一块，结果稍后出现在 [average] */
    fun request(view: View, rect: Rect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || busy || rect.width() < 1 || rect.height() < 1) return
        val window = view.context.activity()?.window ?: return
        if (!view.isAttachedToWindow) return
        if (observed !== view) {
            observed?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(onDraw)
            view.viewTreeObserver.addOnDrawListener(onDraw)
            observed = view
            copiedAt = -1L
        }
        val f = frames
        if (f == copiedAt) return
        copiedAt = f
        busy = true
        runCatching {
            PixelCopy.request(window, rect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    var sr = 0L
                    var sg = 0L
                    var sb = 0L
                    for (y in 0 until H) {
                        for (x in 0 until W) {
                            val c = bitmap.getPixel(x, y)
                            sr += c shr 16 and 0xFF
                            sg += c shr 8 and 0xFF
                            sb += c and 0xFF
                        }
                    }
                    val n = 255f * W * H
                    average = Color(sr / n, sg / n, sb / n)
                }
                busy = false
            }, handler)
        }.onFailure { busy = false }
    }

    fun release() {
        observed?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(onDraw)
        observed = null
        thread.quitSafely()
    }

    private companion object {
        const val W = 16
        const val H = 4

        tailrec fun Context.activity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.activity()
            else -> null
        }
    }
}
