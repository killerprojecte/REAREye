// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 配色模式。
 * - System / Light / Dark：用 [ThemeController.keyColor] 生成的静态配色（跟随系统 / 固定浅 / 固定深）
 * - MonetSystem / MonetLight / MonetDark：Android 12+ 取系统壁纸主色当种子（Monet），更老的系统退回 keyColor
 */
public enum class ColorSchemeMode { System, Light, Dark, MonetSystem, MonetLight, MonetDark }

/** 这个模式此刻是不是深色：跟随系统的两种看 [systemDark]（`isSystemInDarkTheme()`） */
public fun ColorSchemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
    ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
    ColorSchemeMode.System, ColorSchemeMode.MonetSystem -> systemDark
}

/**
 * 主题控制器：管配色模式、种子色、取色算法和对比度。属性都是可观察状态，改了界面会自动重组。
 *
 * ```kotlin
 * val controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }
 * MochiTheme(controller = controller) { ... }
 * ```
 */
@Stable
public class ThemeController(
    colorSchemeMode: ColorSchemeMode = ColorSchemeMode.System,
    keyColor: Color = DefaultKeyColor,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    contrastLevel: Double = 0.0,
) {
    public var colorSchemeMode: ColorSchemeMode by mutableStateOf(colorSchemeMode)
    public var keyColor: Color by mutableStateOf(keyColor)
    public var paletteStyle: PaletteStyle by mutableStateOf(paletteStyle)
    public var contrastLevel: Double by mutableStateOf(contrastLevel)

    /** 当前模式下实际用的配色 */
    @Composable
    public fun currentColorScheme(): MochiColorScheme {
        val systemDark = isSystemInDarkTheme()
        val mode = colorSchemeMode
        val dark = when (mode) {
            ColorSchemeMode.System, ColorSchemeMode.MonetSystem -> systemDark
            ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
            ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        }
        return colorScheme(dark)
    }

    /**
     * 同一套种子色、取色算法和对比度，指定明暗生成的配色（不看 [colorSchemeMode] 里的明暗）。
     * 用于预览：比如在深色界面里预览这套主题，预览也该是深色的。
     */
    @Composable
    public fun colorScheme(isDark: Boolean): MochiColorScheme {
        val mode = colorSchemeMode
        val monet = mode == ColorSchemeMode.MonetSystem || mode == ColorSchemeMode.MonetLight || mode == ColorSchemeMode.MonetDark
        val seed = if (monet) systemSeedColor() ?: keyColor else keyColor
        val style = paletteStyle
        val contrast = contrastLevel
        return remember(seed, isDark, style, contrast) { generateColorScheme(seed, isDark, style, contrast) }
    }
}

/** Android 12+ 的系统取色种子（system_accent1 的 500 色阶）；更老的系统返回 null */
@SuppressLint("LocalContextGetResourceValueCall") // 换壁纸取色会触发配置变化，按 LocalConfiguration 重新读
@Composable
private fun systemSeedColor(): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(context, config) { Color(context.getColor(android.R.color.system_accent1_500)) }
}
