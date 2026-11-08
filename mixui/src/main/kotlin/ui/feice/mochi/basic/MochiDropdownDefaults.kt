// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.elastic.MochiRubberBandState
import ui.feice.mochi.effect.elastic.jellyCrossScale
import ui.feice.mochi.effect.elastic.jellyStretch
import ui.feice.mochi.effect.elastic.rememberMochiRubberBandState
import ui.feice.mochi.effect.elastic.rubberStretched
import ui.feice.mochi.effect.glow.MochiGlowDefaults
import ui.feice.mochi.effect.glow.MochiGlowSpec
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.effect.glow.mochiGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.effect.metaball.MochiBlob
import ui.feice.mochi.effect.metaball.MochiMetaballColors
import ui.feice.mochi.effect.metaball.MochiMetaballFrame
import ui.feice.mochi.effect.metaball.MochiMetaballShadow
import ui.feice.mochi.effect.metaball.drawMochiMetaball
import ui.feice.mochi.effect.metaball.proximityFuse
import ui.feice.mochi.effect.metaball.rememberMochiMetaballRenderer
import ui.feice.mochi.gesture.MochiCallbacks
import ui.feice.mochi.gesture.MochiController
import ui.feice.mochi.gesture.mochiButtonGesture
import ui.feice.mochi.overlay.InWindowOverlay
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.shape.G2RectClipShape
import ui.feice.mochi.shape.addG2RoundRect
import ui.feice.mochi.shape.continuityFor
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.MochiHaptics
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.smoothstep
import ui.feice.mochi.utils.windowOriginOnScreen

// ───────────────────────── 公开 API ─────────────────────────

/** 菜单项。icon 是一个拿到当前文字颜色的插槽，用什么图标库都行。 */
@Immutable
public class MochiItem(
    public val label: String,
    public val destructive: Boolean = false,
    public val icon: (@Composable (tint: Color) -> Unit)? = null,
)

/** 面板相对按钮的对齐 */
public enum class MochiAlign { Start, Center, End }

/** Detach：面板在按钮外长出来，中间有融球颈；Cover：按钮整个变成面板（iOS 的 ⋯ 菜单） */
public enum class MochiOpenMode { Detach, Cover }

/** Press：按下就开（iOS 主操作菜单的手感）；LongPress：长按才开，轻点也能开 */
public enum class MochiTrigger { Press, LongPress }

/** Grow：按下变大 + 从手指处发光（iOS 26 交互式玻璃）；Shrink：按下缩小（MD3 习惯） */
public enum class MochiPressStyle { Grow, Shrink }

/** Material：图标在左、勾在右、选中项铺底色；Ios：勾在最左（占位）、图标在最右、选中不铺底色 */
public enum class MochiItemLayout { Material, Ios }

/** 下拉菜单的颜色 */
@Immutable
public data class MochiDropdownColors(
    val buttonColor: Color,
    val buttonContentColor: Color,
    val panelColor: Color,
    val panelContentColor: Color,
    val selectedColor: Color,
    val selectedContentColor: Color,
    /** 手指滑过时的高亮（MD3 pressed state layer） */
    val highlightColor: Color,
    val destructiveColor: Color,
    val shadowColor: Color,
    /** 按压发光的颜色（带 alpha） */
    val glowColor: Color,
)

/** 交互行为 */
@Immutable
public data class MochiDropdownBehavior(
    val openMode: MochiOpenMode = MochiOpenMode.Detach,
    val trigger: MochiTrigger = MochiTrigger.Press,
    val pressStyle: MochiPressStyle = MochiPressStyle.Grow,
    val itemLayout: MochiItemLayout = MochiItemLayout.Material,
    val hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
    val align: MochiAlign = MochiAlign.Start,
    /** 选中后等多久再收起（让选中块滑过去） */
    val dismissDelayMillis: Long = 150,
)

/** 形变参数：融球、果冻、橡皮筋 */
@Immutable
public data class MochiDropdownPhysics(
    /** 融球半径，越大颈越粗越黏（只在分离模式有意义） */
    val fuseRadius: Dp = 28.dp,
    /** G2 平滑度：0 普通圆角，>0 连续曲率 */
    val g2Smoothing: Float = 1f,
    /** 果冻感：按展开速度沿展开方向拉长、横向收窄，0 = 关 */
    val gel: Float = 0.6f,
    /** 菜单展开途中按钮鼓起的量 */
    val squash: Float = 0.04f,
    /** 手指拖出面板时，面板最多被拉过去多少 */
    val rubberMax: Dp = 72.dp,
)

public object MochiDropdownDefaults {
    public val MenuWidth: Dp = 232.dp
    public val IconMenuWidth: Dp = 220.dp
    public val ItemHeight: Dp = 52.dp
    public val PanelPadding: Dp = 8.dp

    /** MD3E Large Increased */
    public val PanelCornerRadius: Dp = 20.dp

    /** 给大值 = 胶囊 / 圆 */
    public val ButtonCornerRadius: Dp = 10_000.dp
    public val ButtonMinHeight: Dp = 48.dp
    public val IconButtonSize: Dp = 48.dp
    public val ButtonPadding: PaddingValues = PaddingValues(start = 20.dp, end = 18.dp)

    /** 分离模式下按钮和面板的间距 */
    public val Gap: Dp = 8.dp

    /** 面板离屏幕边缘至少这么远 */
    public val ScreenMargin: Dp = 12.dp

    /** MD3E 配色：按钮 secondaryContainer，面板 surfaceContainerHigh，选中项 tertiaryContainer */
    @Composable
    public fun dropdownColors(
        buttonColor: Color = MochiTheme.colorScheme.secondaryContainer,
        buttonContentColor: Color = MochiTheme.colorScheme.onSecondaryContainer,
        panelColor: Color = MochiTheme.colorScheme.surfaceContainerHigh,
        panelContentColor: Color = MochiTheme.colorScheme.onSurface,
        selectedColor: Color = MochiTheme.colorScheme.tertiaryContainer,
        selectedContentColor: Color = MochiTheme.colorScheme.onTertiaryContainer,
        highlightColor: Color = MochiTheme.colorScheme.onSurface.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.12f else 0.08f),
        destructiveColor: Color = MochiTheme.colorScheme.error,
        shadowColor: Color = MochiTheme.colorScheme.shadow,
        glowColor: Color = Color.White.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.14f else 0.45f),
    ): MochiDropdownColors = remember(
        buttonColor,
        buttonContentColor,
        panelColor,
        panelContentColor,
        selectedColor,
        selectedContentColor,
        highlightColor,
        destructiveColor,
        shadowColor,
        glowColor,
    ) {
        MochiDropdownColors(
            buttonColor = buttonColor,
            buttonContentColor = buttonContentColor,
            panelColor = panelColor,
            panelContentColor = panelContentColor,
            selectedColor = selectedColor,
            selectedContentColor = selectedContentColor,
            highlightColor = highlightColor,
            destructiveColor = destructiveColor,
            shadowColor = shadowColor,
            glowColor = glowColor,
        )
    }

    /** 图标按钮用：按钮和面板同色（surfaceContainerHighest），包裹变形时无缝 */
    @Composable
    public fun iconDropdownColors(
        buttonColor: Color = MochiTheme.colorScheme.surfaceContainerHighest,
        buttonContentColor: Color = MochiTheme.colorScheme.onSurface,
        panelColor: Color = MochiTheme.colorScheme.surfaceContainerHighest,
    ): MochiDropdownColors = dropdownColors(
        buttonColor = buttonColor,
        buttonContentColor = buttonContentColor,
        panelColor = panelColor,
    )

    public fun behavior(
        openMode: MochiOpenMode = MochiOpenMode.Detach,
        trigger: MochiTrigger = MochiTrigger.Press,
        pressStyle: MochiPressStyle = MochiPressStyle.Grow,
        itemLayout: MochiItemLayout = MochiItemLayout.Material,
        hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
        align: MochiAlign = MochiAlign.Start,
        dismissDelayMillis: Long = 150,
    ): MochiDropdownBehavior = MochiDropdownBehavior(openMode, trigger, pressStyle, itemLayout, hapticProfile, align, dismissDelayMillis)

    /** 图标按钮默认：包裹模式、右对齐 */
    public fun iconBehavior(openMode: MochiOpenMode = MochiOpenMode.Cover, align: MochiAlign = MochiAlign.End): MochiDropdownBehavior =
        MochiDropdownBehavior(openMode = openMode, align = align)

    public fun physics(
        fuseRadius: Dp = 28.dp,
        g2Smoothing: Float = 1f,
        gel: Float = 0.6f,
        squash: Float = 0.04f,
        rubberMax: Dp = 72.dp,
    ): MochiDropdownPhysics = MochiDropdownPhysics(fuseRadius, g2Smoothing, gel, squash, rubberMax)
}
