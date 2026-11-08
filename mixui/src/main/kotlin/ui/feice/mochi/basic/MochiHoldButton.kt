// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ui.feice.mochi.effect.elastic.mochiClaimedPressDeform
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.rememberMochiHaptics

/** 长按确认按钮的颜色 */
@Immutable
public data class MochiHoldButtonColors(
    val container: Color,
    val content: Color,
    /** 按住时从左往右填满的那层 */
    val progress: Color,
)

/** 长按确认按钮的默认值 */
public object MochiHoldButtonDefaults {
    /** 按住多久执行 */
    public const val HoldMillis: Int = 1200

    public val Height: Dp = 52.dp

    /** 默认是危险操作的配色（删除、重置这类才需要长按确认） */
    @Composable
    public fun colors(
        container: Color = MochiTheme.colorScheme.error.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.22f else 0.12f),
        content: Color = MochiTheme.colorScheme.error,
        progress: Color = MochiTheme.colorScheme.error.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.4f else 0.24f),
    ): MochiHoldButtonColors = remember(container, content, progress) { MochiHoldButtonColors(container, content, progress) }
}

/**
 * 长按确认按钮：危险操作要按住 [holdMillis] 才执行。
 * - 按住时一层颜色从左往右匀速填满，每填满一成轻震一下；填满时重震、执行 [onConfirm]，随后退回
 * - 提前松手（或手指滑走）进度用弹簧退回，什么都不执行
 * - 按下时整颗按钮轻微形变（[mochiPressDeform]）
 */
@Composable
public fun MochiHoldButton(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMillis: Int = MochiHoldButtonDefaults.HoldMillis,
    colors: MochiHoldButtonColors = MochiHoldButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val confirm by rememberUpdatedState(onConfirm)
    val scope = rememberCoroutineScope()
    LaunchedEffect(progress) {
        snapshotFlow { (progress.value * 10).toInt() }
            .distinctUntilChanged()
            .collect { step -> if (step in 1..9 && progress.isRunning && progress.targetValue == 1f) haptics.tick() }
    }
    Row(
        modifier
            .defaultMinSize(minHeight = MochiHoldButtonDefaults.Height)
            .mochiClaimedPressDeform()
            .clip(MochiCapsule)
            .drawBehind {
                drawRect(colors.container)
                val p = progress.value
                if (p > 0f) drawRect(colors.progress, size = Size(size.width * p, size.height))
            }
            .semantics {
                role = Role.Button
                if (enabled) {
                    onLongClick {
                        confirm()
                        true
                    }
                }
            }
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(holdMillis) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            val job = scope.launch {
                                progress.animateTo(
                                    1f,
                                    tween(((1f - progress.value) * holdMillis).toInt().coerceAtLeast(1), easing = LinearEasing),
                                )
                                haptics.threshold()
                                confirm()
                                delay(250)
                                progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 300f))
                            }
                            // 按在按钮上的这一整下都归按钮：外层的滚动、面板拖动不接手，手指挪开也继续按住，松手才算
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull() ?: break
                                c.consume()
                                if (!c.pressed) break
                            }
                            if (progress.value < 1f && job.isActive) {
                                job.cancel()
                                scope.launch { progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f)) }
                            }
                        }
                    }
                },
            )
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.content else colors.content.copy(alpha = 0.4f)) {
            content()
        }
    }
}
