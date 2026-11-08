// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * MD3 字阶：display / headline / title / body / label × Large / Medium / Small，
 * 外加几个 iOS 26 位置专用的字阶（大标题、导航栏标题、脚注），所有组件引用它们，字号不会各写各的。
 */
@Immutable
public data class MochiTextStyles(
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val displaySmall: TextStyle,
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
    /** 大标题（iOS large title：34 Bold） */
    val largeTitle: TextStyle = scale(34, 41, FontWeight.Bold, 0.sp),
    /** 导航栏 / 面板顶栏的小标题（iOS：17 Semibold） */
    val navTitle: TextStyle = scale(17, 22, FontWeight.SemiBold, 0.sp),
    /** 列表行的标题和值（iOS body：17） */
    val body: TextStyle = scale(17, 22, FontWeight.Normal, 0.sp),
    /** 列表行的副标题（iOS subheadline：15） */
    val subhead: TextStyle = scale(15, 20, FontWeight.Normal, 0.sp),
    /** 列表页脚、说明文字（iOS footnote：13） */
    val footnote: TextStyle = scale(13, 18, FontWeight.Normal, 0.sp),
)

// 行高多出来的部分上下平分（默认按字体上下伸比例分，中文字形会偏下一点），
// 这样文字和旁边的图标、开关、对勾的中心在同一条线上。
// textMotion = Animated：Android 默认（Static）为了静止时锐利，会对字形做 hinting、把每个字的位置和字宽对齐到整像素，
// 缩放、位移、改字号 / 字间距时每个字各自在像素格之间跳，整行看起来在抖。Animated 关掉 hinting、
// 用亚像素定位和线性（不取整）的字宽，和 iOS 一样平滑；高分屏上静止时几乎看不出差别
private fun scale(size: Int, line: Int, weight: FontWeight, tracking: TextUnit) = TextStyle(
    textMotion = TextMotion.Animated,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/**
 * 部分文字换成系统衬线体：大标题、导航标题和 display / headline 几级（和 Nothing 主题的标题字一样的范围）。
 * 正文、列表、按钮、标签不变。系统衬线体会按字体回退带上中文宋体（如思源宋体 / Noto Serif CJK）。
 */
public fun MochiTextStyles.withSerifDisplay(): MochiTextStyles {
    fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif)
    return copy(
        displayLarge = displayLarge.serif(),
        displayMedium = displayMedium.serif(),
        displaySmall = displaySmall.serif(),
        headlineLarge = headlineLarge.serif(),
        headlineMedium = headlineMedium.serif(),
        headlineSmall = headlineSmall.serif(),
        largeTitle = largeTitle.serif(),
        navTitle = navTitle.serif(),
    )
}

/** MD3 type scale token 的默认数值 */
public fun defaultTextStyles(): MochiTextStyles {
    val regular = FontWeight.Normal
    val medium = FontWeight.Medium
    return MochiTextStyles(
        displayLarge = scale(57, 64, regular, (-0.25).sp),
        displayMedium = scale(45, 52, regular, 0.sp),
        displaySmall = scale(36, 44, regular, 0.sp),
        headlineLarge = scale(32, 40, regular, 0.sp),
        headlineMedium = scale(28, 36, regular, 0.sp),
        headlineSmall = scale(24, 32, regular, 0.sp),
        titleLarge = scale(22, 28, regular, 0.sp),
        titleMedium = scale(16, 24, medium, 0.15.sp),
        titleSmall = scale(14, 20, medium, 0.1.sp),
        bodyLarge = scale(16, 24, regular, 0.5.sp),
        bodyMedium = scale(14, 20, regular, 0.25.sp),
        bodySmall = scale(12, 16, regular, 0.4.sp),
        labelLarge = scale(14, 20, medium, 0.1.sp),
        labelMedium = scale(12, 16, medium, 0.5.sp),
        labelSmall = scale(11, 16, medium, 0.5.sp),
    )
}

/**
 * MD3 Expressive 的 emphasized 字阶：同字号，字重提一档（Regular → Medium，Medium → SemiBold），
 * 用在标题、选中项这些需要强调的地方。
 */
public fun TextStyle.emphasized(): TextStyle {
    val w = fontWeight ?: FontWeight.Normal
    return copy(fontWeight = if (w.weight >= FontWeight.Medium.weight) FontWeight.SemiBold else FontWeight.Medium)
}

/** 大标题用的字重（Bold）：iOS 的 large title 是粗体，比 [emphasized] 再重一档 */
public fun TextStyle.prominent(): TextStyle = copy(fontWeight = FontWeight.Bold)
