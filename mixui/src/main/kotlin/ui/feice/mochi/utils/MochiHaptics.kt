// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 震动策略。
 * - IosLike（默认）：长按打开（一下清脆的 click）、手指滑过新选项（tick）、确认一个选择（开关拨动、松手选中，轻 click）。
 *   轻点打开、橡皮筋不震，学的是 iOS「少而准」的做法。
 * - Rich：在上面基础上，轻点打开也给一下轻的。
 * - Off：全关。
 */
public enum class MochiHapticProfile { IosLike, Rich, Off }

/**
 * 清脆感来自「组合原语」(VibrationEffect.Composition)：厂商调好的短脉冲，没有马达拖尾。
 * 原语只在 API 33+ 用，因为要配 USAGE_TOUCH，才会跟随系统「触摸反馈」强度设置；
 * 设备不支持原语、没声明 VIBRATE 权限、或者更老的系统，就退回 View.performHapticFeedback。
 * 不用 createOneShot / vibrate(ms)：官方明确说这种整段马达驱动会发闷发麻。
 */
public class MochiHaptics internal constructor(private val view: View, private val profile: MochiHapticProfile) {
    private val vibrator: Vibrator? = view.context.vibratorOrNull()
    private val canCompose: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            vibrator?.hasVibrator() == true &&
            view.context.checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
    private val lowTickOk = canCompose && vibrator.supports(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    private val tickOk = canCompose && vibrator.supports(VibrationEffect.Composition.PRIMITIVE_TICK)
    private val clickOk = canCompose && vibrator.supports(VibrationEffect.Composition.PRIMITIVE_CLICK)
    private var lastTickAt = 0L

    /** 轻点打开：IosLike 不震 */
    public fun tapOpen() {
        if (profile != MochiHapticProfile.Rich) return
        if (!primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, tickOk)) perform(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** 长按打开：一下 click，像 iOS 的 Haptic Touch */
    public fun longPress() {
        if (profile == MochiHapticProfile.Off) return
        if (!primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, clickOk)) perform(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * 手指滑过新选项：一下短促的 tick，24ms 内只震一次，快划时不会连成一串嗡嗡。
     * 用 TICK 原语（不用 LOW_TICK：部分机型上 LOW_TICK 轻到几乎感觉不到，像是丢了）。
     */
    public fun tick() {
        if (profile == MochiHapticProfile.Off) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt < TickGapMillis) return
        lastTickAt = now
        val played = primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, tickOk) ||
            primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1f, lowTickOk)
        if (!played) {
            perform(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    HapticFeedbackConstants.SEGMENT_TICK
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                },
            )
        }
    }

    /** 确认一个选择（开关拨动、松手选中、选中一个选项）：一下轻 click。IosLike 和 Rich 都震 */
    public fun confirm() {
        if (profile == MochiHapticProfile.Off) return
        if (!primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, clickOk)) {
            perform(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                },
            )
        }
    }

    /**
     * 越过一个「松手就会生效」的阈值：左滑过头会直接删除、下拉面板过头会关闭。
     * 来回越过时每次都震，告诉用户「现在松手会发生什么变了」（iOS 的做法）
     */
    public fun threshold() {
        if (profile == MochiHapticProfile.Off) return
        if (!primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, clickOk)) {
            perform(
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
                    else -> HapticFeedbackConstants.VIRTUAL_KEY
                },
            )
        }
    }

    private fun primitive(id: Int, scale: Float, supported: Boolean): Boolean {
        if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val v = vibrator ?: return false
        return try {
            val effect = VibrationEffect.startComposition().addPrimitive(id, scale).compose()
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun perform(constant: Int) {
        // 不看 View 自己的 isHapticFeedbackEnabled（有的容器会关掉它，震动就悄悄丢了）；系统的触摸反馈总开关照样生效
        @Suppress("DEPRECATION")
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}

private const val TickGapMillis = 24L

private fun Context.vibratorOrNull(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    getSystemService(VibratorManager::class.java)?.defaultVibrator
} else {
    @Suppress("DEPRECATION")
    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}

private fun Vibrator?.supports(primitive: Int): Boolean {
    if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return try {
        areAllPrimitivesSupported(primitive)
    } catch (_: RuntimeException) {
        false
    }
}

/**
 * 拿一个按库里统一规则震动的 [MochiHaptics]，自己的组件也能用同一套：
 * - tick：滑过新选项、刻度、过中线
 * - longPress：长按呼出（菜单、预览）
 * - threshold：越过「松手就生效」的阈值（左滑删除、下拉关闭）
 * - confirm：确认一个选择（开关、松手选中）
 * - tapOpen：只有 Rich 档才震
 */
@Composable
public fun rememberMochiHaptics(profile: MochiHapticProfile = MochiHapticProfile.IosLike): MochiHaptics {
    val view = LocalView.current
    return remember(view, profile) { MochiHaptics(view, profile) }
}
