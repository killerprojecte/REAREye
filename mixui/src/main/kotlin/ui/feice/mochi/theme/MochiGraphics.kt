// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.feice.mochi.effect.content.MochiBlurWarmup

/** 图形档位：中、高、超高。默认 [High]。 */
public enum class MochiGraphicsLevel {
    /** 中：模糊和柔和阴影照常；着色器关闭（融球、玻璃、边缘流光走纯色画法），揭开转场是硬边。省电 */
    Mid,

    /** 高：完整效果。默认 */
    High,

    /** 超高：在高的基础上模糊加强（半径 ×1.5），并请求屏幕最高的刷新率。更耗电；不会打开 HDR（那是单独的开关） */
    Extra,
}

/**
 * 全局图形档位：库里所有「贵」的画法都按它决定画不画、画多重，同一个档位下整套界面的质感一致。
 * 改 [level] 立即生效（可观察状态），App 自己负责保存。
 *
 * 系统本身不支持的效果（API 31 以下没有模糊、33 以下没有 AGSL）不管档位多高都不会出现，见 [blur]、[shaders]。
 */
public object MochiGraphics {
    public var level: MochiGraphicsLevel by mutableStateOf(MochiGraphicsLevel.High)

    /** 模糊（内容变换、导航栏、出现动画）：每档都有，Android 12+ */
    public val blur: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** AGSL 着色器（融球、玻璃、边缘流光、导航栏融合）：高及以上，Android 13+ */
    public val shaders: Boolean
        get() = level >= MochiGraphicsLevel.High && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 任意形状的柔和阴影：每档都有，Android 9+；更低的系统退化成几圈越来越淡的环 */
    public val softShadows: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** 模糊半径倍数：超高档 1.5 */
    public val blurScale: Float
        get() = if (level == MochiGraphicsLevel.Extra) 1.5f else 1f

    /** 换主题的揭开转场：每档都有 */
    public val reveal: Boolean
        get() = true

    /** 揭开转场的羽化边和光晕：高及以上，中档是清楚的硬边 */
    public val softReveal: Boolean
        get() = level >= MochiGraphicsLevel.High

    internal var windowOwner: Any? by mutableStateOf(null)
}

/**
 * 按档位请求刷新率：超高档在当前分辨率下选最高刷新率，中、高交给系统。
 * 和 HDR 一样只由最外层的 MochiTheme 管窗口，离开时恢复。
 */
@Composable
internal fun MochiGraphicsWindowEffect() {
    val token = remember { Any() }
    DisposableEffect(token) {
        if (MochiGraphics.windowOwner == null) MochiGraphics.windowOwner = token
        onDispose { if (MochiGraphics.windowOwner === token) MochiGraphics.windowOwner = null }
    }
    if (MochiGraphics.windowOwner !== token) return
    // 模糊的着色器在后台先编好，第一次出现的模糊过渡不再因为现编程序掉帧（见 MochiBlurWarmup）
    LaunchedEffect(Unit) {
        if (MochiGraphics.blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            withContext(Dispatchers.Default) { MochiBlurWarmup.run() }
        }
    }
    val view = LocalView.current
    val level = MochiGraphics.level
    DisposableEffect(view, level) {
        val window = view.context.findActivity()?.window
        val display = view.display
        if (window == null || display == null) return@DisposableEffect onDispose { }
        val saved = window.attributes.preferredDisplayModeId
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val target = when (level) {
            MochiGraphicsLevel.Extra -> sameSize.maxByOrNull { it.refreshRate }?.modeId ?: 0
            else -> 0
        }
        window.attributes = window.attributes.apply { preferredDisplayModeId = target }
        onDispose { window.attributes = window.attributes.apply { preferredDisplayModeId = saved } }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
