// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 组件库主题：MD3 Expressive 的配色 / 字阶 / 形状 / 动效。
 *
 * ```kotlin
 * MochiTheme(controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }) {
 *     // 里面的组件都从 MochiTheme.colorScheme 等取值
 * }
 * ```
 * 根部还会提供 [LocalContentColor]（= onSurface）和 [LocalTextStyle]（= bodyLarge），
 * 不放在任何容器里的文字也有正确的颜色，深色模式下不会是黑字。
 *
 * 最外层的 MochiTheme 还会让状态栏、导航栏图标跟着明暗走（浅色主题深色图标），应用不用自己写
 * `WindowCompat.getInsetsController(…).isAppearanceLightStatusBars`。页面要自己控制时传 [systemBarAppearance] = false。
 *
 * [materials] 决定浮在内容上的栏用纯色还是液态玻璃、滚动边缘要不要渐进模糊，和配色无关：任何主题都能打开。
 * 玻璃、模糊的配方在 mochi-ios 里（`iosMaterials(isDark, glass = …, blur = …)`），默认纯色（[MochiMaterials] 的默认值）。
 * [shadows] 是阴影档位表（[MochiShadows]），组件只选档位，换一套就整体换阴影风格。
 * 组件都从主题读这些，应用里不需要按主题、按开关分别写组件。
 */
@Composable
public fun MochiTheme(
    controller: ThemeController = remember { ThemeController() },
    textStyles: MochiTextStyles = defaultTextStyles(),
    shapes: MochiShapes = defaultShapes(),
    motionScheme: MochiMotionScheme = defaultMotionScheme(),
    systemBarAppearance: Boolean = true,
    materials: MochiMaterials = LocalMochiMaterials.current,
    shadows: MochiShadows = LocalMochiShadows.current,
    content: @Composable () -> Unit,
) {
    MochiTheme(
        colors = animateMochiColorScheme(controller.currentColorScheme()),
        textStyles = textStyles,
        shapes = shapes,
        motionScheme = motionScheme,
        systemBarAppearance = systemBarAppearance,
        materials = materials,
        shadows = shadows,
        content = content,
    )
}

/**
 * 换种子色、取色算法或明暗时，整套配色在 420ms 内平滑过渡，不瞬切（内容不瞬切同样适用于主题）。
 * 过渡途中再换，从当前插值到的颜色接着走。[MochiTheme] 的 controller 版本已经用它；
 * 直接给配色的版本（`MochiTheme(colors = …)`）自己包一层：`MochiTheme(colors = animateMochiColorScheme(scheme))`。
 *
 * @param animate false = 一步到位。整套换装用 [ui.feice.mochi.effect.content.MochiThemeRevealHost] 揭开时传
 *   `!state.isRevealing`：洞里露出来的新画面必须已经是最终颜色
 */
@Composable
public fun animateMochiColorScheme(target: MochiColorScheme, animate: Boolean = true): MochiColorScheme {
    val state = remember { SchemeTransition(target) }
    LaunchedEffect(target, animate) {
        if (target == state.to) return@LaunchedEffect
        if (!animate) {
            state.from = target
            state.to = target
            state.progress.snapTo(1f)
            return@LaunchedEffect
        }
        state.from = state.current()
        state.to = target
        state.progress.snapTo(0f)
        state.progress.animateTo(1f, tween(ColorTransitionMillis, easing = FastOutSlowInEasing))
    }
    return if (animate) state.current() else target
}

private class SchemeTransition(initial: MochiColorScheme) {
    var from by mutableStateOf(initial)
    var to by mutableStateOf(initial)
    val progress = Animatable(1f)

    fun current(): MochiColorScheme = progress.value.let { if (it >= 1f) to else lerp(from, to, it) }
}

/** 配色过渡时长 */
private const val ColorTransitionMillis = 420

/** 直接给一套配色的版本；[materials]、[shadows] 默认沿用外层提供的 */
@Composable
public fun MochiTheme(
    colors: MochiColorScheme,
    textStyles: MochiTextStyles = defaultTextStyles(),
    shapes: MochiShapes = defaultShapes(),
    motionScheme: MochiMotionScheme = defaultMotionScheme(),
    systemBarAppearance: Boolean = true,
    materials: MochiMaterials = LocalMochiMaterials.current,
    shadows: MochiShadows = LocalMochiShadows.current,
    content: @Composable () -> Unit,
) {
    // HDR 窗口模式只由最外层（最先出现）的 MochiTheme 管一次，见 MochiHdrWindowEffect
    MochiHdrWindowEffect()
    MochiGraphicsWindowEffect()
    if (systemBarAppearance) MochiSystemBarsEffect(colors.isDark)
    CompositionLocalProvider(
        LocalMochiColorScheme provides colors,
        LocalMochiTextStyles provides textStyles,
        LocalMochiShapes provides shapes,
        LocalMochiMotionScheme provides motionScheme,
        LocalMochiMaterials provides materials,
        LocalMochiShadows provides shadows,
        LocalContentColor provides colors.onSurface,
        LocalTextStyle provides textStyles.bodyLarge,
        content = content,
    )
}

/** 在组件里读主题：`MochiTheme.colorScheme.primary`、`MochiTheme.textStyles.bodyLarge`… */
public object MochiTheme {
    public val colorScheme: MochiColorScheme
        @Composable @ReadOnlyComposable
        get() = LocalMochiColorScheme.current

    public val textStyles: MochiTextStyles
        @Composable @ReadOnlyComposable
        get() = LocalMochiTextStyles.current

    public val shapes: MochiShapes
        @Composable @ReadOnlyComposable
        get() = LocalMochiShapes.current

    public val motionScheme: MochiMotionScheme
        @Composable @ReadOnlyComposable
        get() = LocalMochiMotionScheme.current

    /** 浮在内容上的栏用纯色还是玻璃，见 [MochiMaterials] */
    public val materials: MochiMaterials
        @Composable @ReadOnlyComposable
        get() = LocalMochiMaterials.current

    /** 阴影档位，见 [MochiShadows] */
    public val shadows: MochiShadows
        @Composable @ReadOnlyComposable
        get() = LocalMochiShadows.current
}

// 没包 MochiTheme 时也能用：默认浅色配色和默认 token
internal val LocalMochiColorScheme = staticCompositionLocalOf { lightColorScheme() }
internal val LocalMochiTextStyles = staticCompositionLocalOf { defaultTextStyles() }
internal val LocalMochiShapes = staticCompositionLocalOf { defaultShapes() }
internal val LocalMochiMotionScheme = staticCompositionLocalOf { defaultMotionScheme() }

/** 当前内容（文字、图标）的颜色，容器组件会按自己的背景色改写它 */
public val LocalContentColor: androidx.compose.runtime.ProvidableCompositionLocal<Color> =
    androidx.compose.runtime.compositionLocalOf { Color.Black }

/** 当前默认文字样式 */
public val LocalTextStyle: androidx.compose.runtime.ProvidableCompositionLocal<TextStyle> =
    androidx.compose.runtime.compositionLocalOf { TextStyle.Default }
