// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import ui.feice.mochi.effect.glass.GlassSource
import ui.feice.mochi.effect.glass.LocalGlassSources
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.overlayBackdropSource
import ui.feice.mochi.navigation.LocalMochiNavEntry

/**
 * 浮层直接挂到当前 Activity 的 DecorView 上（同一个窗口），不用 Popup：
 * Popup 是独立的子窗口，会被系统限制在状态栏 / 导航栏之间，底部按钮的那一截会被切掉；
 * 而 edge-to-edge 下 DecorView 本身就铺满整屏。CompositionLocal 通过父 CompositionContext 继承。
 *
 * [leaving]：前景开始离开（关闭动画一开始）就返回 true。从这一刻起新按下的手指整个手势都直接落到下面的页面上，
 * 浮层只管把收起动画画完，不再拦着等动画结束；已经按在浮层上的那一下（比如拖着面板往下甩）照常走完。
 */
@Composable
internal fun InWindowOverlay(leaving: () -> Boolean = { false }, content: @Composable () -> Unit) {
    val hostView = LocalView.current
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentLeaving by rememberUpdatedState(leaving)
    val below by rememberUpdatedState(overlayBackdropSource())
    val overlay = remember(hostView) {
        OverlayFrame(hostView.context) { currentLeaving() }.apply {
            addView(
                ComposeView(hostView.context).apply {
                    setParentCompositionContext(parent)
                    setContent {
                        // 浮层不属于原页面的布局树，复制的按钮 / 共享元素不能登记成页内节点。
                        // 保留导航器，浮层里的操作仍可导航；内嵌 MochiNavHost 会提供自己的页面。
                        // 页面的背景来源也不往浮层里传：浮层盖在页面上面，浮层里的按钮再取样页面就是取到被盖住的东西
                        CompositionLocalProvider(LocalMochiNavEntry provides null, LocalMochiBackdrop provides null) {
                            val under = below
                            if (under == null) {
                                currentContent()
                            } else {
                                // 浮层的玻璃取样它底下的内容；从浮层里再打开的浮层，底下是那份内容再叠上这个浮层自己
                                val own = rememberLayerBackdrop()
                                val layer = rememberCombinedBackdrop(under.layer, own)
                                val face = rememberCombinedBackdrop(under.face, own)
                                val chain = remember(layer, face) { GlassSource(layer, face) }
                                Box(Modifier.fillMaxSize().layerBackdrop(own)) {
                                    CompositionLocalProvider(
                                        LocalGlassSources provides LocalGlassSources.current.copy(overlay = under, window = chain),
                                    ) { currentContent() }
                                }
                            }
                        }
                    }
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }
    DisposableEffect(overlay) {
        val decor = hostView.rootView as ViewGroup
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onDispose {
            if (overlay.hasFocus()) {
                // 浮层里有输入框聚焦时直接移除，系统会把焦点交回页面，Compose 顺手聚焦页面上第一个输入框、键盘又弹出来。
                // 移除的那一刻先不让页面接焦点，下一帧恢复：关掉后什么都不聚焦（iOS 关掉警告框键盘一起收起）
                val old = decor.descendantFocusability
                decor.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                decor.removeView(overlay)
                decor.post { decor.descendantFocusability = old }
            } else {
                decor.removeView(overlay)
            }
            (overlay.getChildAt(0) as ComposeView).disposeComposition()
        }
    }
}

/**
 * 浮层的外壳：按下时问一次 [leaving]，正在离开就不接这一整个手势（DecorView 会把它交给下面的页面），
 * 没在离开就照常交给浮层。只在按下时判断，按住途中开始关闭的那一下仍然归浮层，不会被拦腰截断
 */
private class OverlayFrame(context: Context, private val leaving: () -> Boolean) : FrameLayout(context) {
    private var passing = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) passing = leaving()
        return if (passing) false else super.dispatchTouchEvent(ev)
    }
}
