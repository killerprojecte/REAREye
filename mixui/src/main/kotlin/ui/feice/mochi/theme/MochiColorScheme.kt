// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/**
 * MD3 的全套颜色角色。组件只读这里的颜色，不写死色值。
 *
 * 改个别颜色用 `copy(...)`；整套换色用 [lightColorScheme] / [darkColorScheme] 换种子色，
 * 或者用 [ThemeController] 的 Monet 模式跟随系统取色。
 */
@Immutable
public data class MochiColorScheme(
    val isDark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val shadow: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
)

/** 种子色 → tonal 配色的算法，对应 Material 的 dynamic scheme variant */
public enum class PaletteStyle { TonalSpot, Expressive, Vibrant, Content, Fidelity, Neutral }

/** 默认种子色：青绿 */
public val DefaultKeyColor: Color = Color(0xFF2B6A5A)

/** 浅色配色，按 MD3 Expressive（2025 配色规范）从种子色生成 */
public fun lightColorScheme(
    keyColor: Color = DefaultKeyColor,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    contrastLevel: Double = 0.0,
): MochiColorScheme = generateColorScheme(keyColor, isDark = false, style, contrastLevel)

/** 深色配色，参数同 [lightColorScheme] */
public fun darkColorScheme(
    keyColor: Color = DefaultKeyColor,
    style: PaletteStyle = PaletteStyle.TonalSpot,
    contrastLevel: Double = 0.0,
): MochiColorScheme = generateColorScheme(keyColor, isDark = true, style, contrastLevel)

private data class SchemeKey(val argb: Int, val isDark: Boolean, val style: PaletteStyle, val contrast: Double)

// 生成一次配色要做不少 HCT 运算，按参数缓存
private val schemeCache = HashMap<SchemeKey, MochiColorScheme>()

internal fun generateColorScheme(keyColor: Color, isDark: Boolean, style: PaletteStyle, contrastLevel: Double): MochiColorScheme {
    val key = SchemeKey(keyColor.toArgb(), isDark, style, contrastLevel)
    return synchronized(schemeCache) {
        schemeCache.getOrPut(key) { buildScheme(key).toMochiColorScheme() }
    }
}

private fun buildScheme(k: SchemeKey): DynamicScheme {
    val hct = Hct.fromInt(k.argb)
    val spec = ColorSpec.SpecVersion.SPEC_2025
    return when (k.style) {
        PaletteStyle.TonalSpot -> SchemeTonalSpot(hct, k.isDark, k.contrast, spec)
        PaletteStyle.Expressive -> SchemeExpressive(hct, k.isDark, k.contrast, spec)
        PaletteStyle.Vibrant -> SchemeVibrant(hct, k.isDark, k.contrast, spec)
        PaletteStyle.Content -> SchemeContent(hct, k.isDark, k.contrast, spec)
        PaletteStyle.Fidelity -> SchemeFidelity(hct, k.isDark, k.contrast, spec)
        PaletteStyle.Neutral -> SchemeNeutral(hct, k.isDark, k.contrast, spec)
    }
}

private fun DynamicScheme.toMochiColorScheme(): MochiColorScheme = MochiColorScheme(
    isDark = isDark,
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(surfaceTint),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    shadow = Color(shadow),
    surfaceBright = Color(surfaceBright),
    surfaceDim = Color(surfaceDim),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
)

/**
 * 两套配色之间插值（在 Oklab 里混，不发灰）。[MochiTheme] 换配色时用它做过渡：明暗属性直接取目标的。
 */
internal fun lerp(from: MochiColorScheme, to: MochiColorScheme, t: Float): MochiColorScheme = MochiColorScheme(
    isDark = to.isDark,
    primary = lerp(from.primary, to.primary, t),
    onPrimary = lerp(from.onPrimary, to.onPrimary, t),
    primaryContainer = lerp(from.primaryContainer, to.primaryContainer, t),
    onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, t),
    inversePrimary = lerp(from.inversePrimary, to.inversePrimary, t),
    secondary = lerp(from.secondary, to.secondary, t),
    onSecondary = lerp(from.onSecondary, to.onSecondary, t),
    secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, t),
    onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, t),
    tertiary = lerp(from.tertiary, to.tertiary, t),
    onTertiary = lerp(from.onTertiary, to.onTertiary, t),
    tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, t),
    onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, t),
    background = lerp(from.background, to.background, t),
    onBackground = lerp(from.onBackground, to.onBackground, t),
    surface = lerp(from.surface, to.surface, t),
    onSurface = lerp(from.onSurface, to.onSurface, t),
    surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, t),
    onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, t),
    surfaceTint = lerp(from.surfaceTint, to.surfaceTint, t),
    inverseSurface = lerp(from.inverseSurface, to.inverseSurface, t),
    inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, t),
    error = lerp(from.error, to.error, t),
    onError = lerp(from.onError, to.onError, t),
    errorContainer = lerp(from.errorContainer, to.errorContainer, t),
    onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, t),
    outline = lerp(from.outline, to.outline, t),
    outlineVariant = lerp(from.outlineVariant, to.outlineVariant, t),
    scrim = lerp(from.scrim, to.scrim, t),
    shadow = lerp(from.shadow, to.shadow, t),
    surfaceBright = lerp(from.surfaceBright, to.surfaceBright, t),
    surfaceDim = lerp(from.surfaceDim, to.surfaceDim, t),
    surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, t),
    surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, t),
    surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, t),
    surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, t),
)
