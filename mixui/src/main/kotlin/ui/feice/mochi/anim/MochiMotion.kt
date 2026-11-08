// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.anim

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一套开/关动画。
 * - spatial：驱动形状（位置、尺寸、融球），允许过冲；它的「速度」还会驱动果冻形变
 * - effects：驱动内容显影（透明度 + 模糊 + 轻微放大），不该过冲
 */
@Immutable
public data class MochiMotion(
    val name: String,
    val openSpatial: AnimationSpec<Float>,
    val closeSpatial: AnimationSpec<Float>,
    val openEffects: AnimationSpec<Float>,
    val closeEffects: AnimationSpec<Float>,
    /** 形状展开到多少时内容开始显影（跟进度绑定，不跟计时器） */
    val contentAt: Float = 0.15f,
    /** 菜单项依次出现的错开比例，0 = 一起出现（iOS 是一起） */
    val stagger: Float = 0f,
)

public object MochiMotions {
    /** SwiftUI 的 response（一个周期多少秒）→ 刚度：k = (2π / response)² */
    public fun stiffnessFor(response: Float): Float = (2f * PI.toFloat() / response).pow(2)

    /** 用 SwiftUI 的说法写弹簧：response 秒 + dampingFraction */
    public fun responseSpring(response: Float, damping: Float): AnimationSpec<Float> =
        spring(dampingRatio = damping, stiffness = stiffnessFor(response), visibilityThreshold = 0.0005f)

    private fun spr(damping: Float, stiffness: Float): AnimationSpec<Float> =
        spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = 0.0005f)

    /**
     * 自定义弹簧。关闭默认用临界阻尼 + 1.6 倍刚度：收回去不回弹，
     * 不然 progress 冲到 0 以下菜单会「翻面」。
     */
    public fun springPreset(
        name: String,
        damping: Float,
        stiffness: Float,
        effectsStiffness: Float = stiffnessFor(0.26f),
        stagger: Float = 0f,
    ): MochiMotion = MochiMotion(
        name = name,
        openSpatial = spr(damping, stiffness),
        closeSpatial = spr(1f, stiffness * 1.6f),
        openEffects = spr(1f, effectsStiffness),
        closeEffects = responseSpring(0.12f, 1f),
        stagger = stagger,
    )

    /** SwiftUI 的 Spring(duration:bounce:)：dampingRatio = 1 − bounce（bounce ≥ 0） */
    public fun appleSpring(name: String, duration: Float, bounce: Float): MochiMotion =
        springPreset(name, 1f - bounce, stiffnessFor(duration))

    // —— 默认：按 iOS 26 菜单手感估的，不是苹果公开参数 ——
    public val Ios26: MochiMotion = MochiMotion(
        name = "iOS 26 菜单（估计）",
        openSpatial = responseSpring(0.40f, 0.80f),
        closeSpatial = responseSpring(0.30f, 1f),
        openEffects = responseSpring(0.26f, 1f),
        closeEffects = responseSpring(0.12f, 1f),
    )

    /**
     * iOS 27 的玻璃菜单（UIKit ContextMenu 的液态形变，AnimationKit MorphAnimationSettings 的原生值）：
     * 展开时位置、宽高、内容同一组弹簧 dampingRatio 0.8、response 0.3；收起（liquidMorphShrink）宽高、位置、内容 0.9 / 0.3。
     * 内容比形状晚 secondStepDelay = 0.3 × 0.1 = 0.03 秒，换成形状弹簧在 0.03 秒时走到的进度。
     * blurInSpring / blurOutSpring 是透镜里内容的模糊和放大用的（见 DropdownOverlay 的 MenuLens），不管内容透明度。
     * 开了液态玻璃的菜单默认用它（见 [ui.feice.mochi.theme.MochiMaterials.menuGlass]）
     */
    public val Ios27Menu: MochiMotion = MochiMotion(
        name = "iOS 27 玻璃菜单",
        openSpatial = responseSpring(0.3f, 0.8f),
        closeSpatial = responseSpring(0.3f, 0.9f),
        openEffects = responseSpring(0.3f, 0.8f),
        closeEffects = responseSpring(0.3f, 0.9f),
        contentAt = springProgress(0.3f, 0.8f, 0.03f),
    )

    /** response / dampingRatio 的弹簧（零初速、目标 1，欠阻尼）在 [t] 秒时的进度 */
    private fun springProgress(response: Float, damping: Float, t: Float): Float {
        val w = 2f * PI.toFloat() / response
        val wd = w * sqrt(1f - damping * damping)
        val zw = damping * w
        return 1f - exp(-zw * t) * (cos(wd * t) + zw / wd * sin(wd * t))
    }

    // —— 仿 iOS（SwiftUI 预设换算）——
    public val IosSmooth: MochiMotion = appleSpring("iOS smooth", 0.5f, 0f)
    public val IosSnappy: MochiMotion = appleSpring("iOS snappy", 0.5f, 0.15f)
    public val IosBouncy: MochiMotion = appleSpring("iOS bouncy", 0.5f, 0.3f)

    // —— MD3E 官方 token（androidx ExpressiveMotionTokens / StandardMotionTokens）——
    public val ExpressiveFast: MochiMotion = springPreset("M3E 表现力（快）", 0.6f, 800f, effectsStiffness = 3800f, stagger = 0.05f)
    public val ExpressiveDefault: MochiMotion = springPreset("M3E 表现力（默认）", 0.8f, 380f, stagger = 0.05f)
    public val ExpressiveSlow: MochiMotion = springPreset("M3E 表现力（慢）", 0.8f, 200f, effectsStiffness = 800f, stagger = 0.05f)
    public val Standard: MochiMotion = springPreset("M3 标准", 0.9f, 700f, stagger = 0.04f)

    // —— 夸张款 ——
    public val Jelly: MochiMotion = springPreset("果冻", 0.45f, 300f)

    // —— M3 Emphasized 缓动（不是弹簧，固定时长）——
    public val Emphasized: MochiMotion = MochiMotion(
        name = "M3 Emphasized 缓动",
        openSpatial = tween(500, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)),
        closeSpatial = tween(200, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)),
        openEffects = tween(250, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        closeEffects = tween(100),
        contentAt = 0.2f,
        stagger = 0.05f,
    )

    public val all: List<MochiMotion> = listOf(
        Ios26, Ios27Menu, IosSmooth, IosSnappy, IosBouncy,
        ExpressiveFast, ExpressiveDefault, ExpressiveSlow, Standard, Jelly, Emphasized,
    )
}
