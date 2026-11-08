// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.anim.awaitFrameSettle
import ui.feice.mochi.anim.rememberFramePeriodNanos
import ui.feice.mochi.effect.content.mochiAnimateHeight
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.GlassSurface
import ui.feice.mochi.effect.glass.rememberGlassFill
import ui.feice.mochi.effect.shadow.drawMochiShadow
import ui.feice.mochi.overlay.InWindowOverlay
import ui.feice.mochi.shape.MochiConcentricContainer
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.LocalMochiIconDraw
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.lerp

/** 对话框按钮的角色（决定配色） */
public enum class MochiDialogActionRole {
    /** 普通：灰色胶囊 */
    Default,

    /** 首选：主色实心胶囊（iOS 26 的蓝色按钮） */
    Primary,

    /** 取消：灰色胶囊，按返回键等同于点它 */
    Cancel,

    /** 危险操作：灰色胶囊 + 红字 */
    Destructive,
}

/**
 * 对话框的一个按钮。点了先执行 [onClick]，再关闭对话框（调 onDismissRequest）。
 *
 * @param holdToConfirm 要按住才生效（[MochiHoldButton]）：按住时一层颜色从左往右填满，填满才执行、关闭；
 *   用在「看完再关」的提示或危险操作上
 */
@Immutable
public class MochiDialogAction(
    public val label: String,
    public val role: MochiDialogActionRole = MochiDialogActionRole.Default,
    public val enabled: Boolean = true,
    public val holdToConfirm: Boolean = false,
    public val onClick: () -> Unit = {},
)

/** 对话框配色 */
@Immutable
public data class MochiDialogColors(
    val container: Color,
    val title: Color,
    val message: Color,
    /** 后面压暗的颜色（含不透明度） */
    val scrim: Color,
    val button: Color,
    val buttonContent: Color,
    val primary: Color,
    val onPrimary: Color,
    val destructive: Color,
)

public object MochiDialogDefaults {
    /** 对话框最大宽度 */
    public val MaxWidth: Dp = 320.dp

    /** 按钮离对话框边的距离；圆角 = 按钮半高 + 它（同心） */
    public val ButtonInset: Dp = 14.dp

    /** 按钮高度 */
    public val ButtonHeight: Dp = 48.dp

    @Composable
    public fun colors(
        container: Color = MochiTheme.colorScheme.run { if (isDark) surfaceContainerHigh else surfaceContainerLowest },
        title: Color = MochiTheme.colorScheme.onSurface,
        message: Color = MochiTheme.colorScheme.onSurfaceVariant,
        scrim: Color = MochiDefaults.scrim(),
        button: Color = MochiTheme.colorScheme.run { if (isDark) surfaceContainerHighest else surfaceContainerHigh },
        buttonContent: Color = MochiTheme.colorScheme.onSurface,
        primary: Color = MochiTheme.colorScheme.primary,
        onPrimary: Color = MochiTheme.colorScheme.onPrimary,
        destructive: Color = MochiTheme.colorScheme.error,
    ): MochiDialogColors = remember(container, title, message, scrim, button, buttonContent, primary, onPrimary, destructive) {
        MochiDialogColors(container, title, message, scrim, button, buttonContent, primary, onPrimary, destructive)
    }
}

/**
 * iOS 26 的警告框（alert），不做玻璃：
 * - 出现时从稍大一点缩放到位、淡入，后面的页面压暗；消失时淡出、略微缩小
 * - 圆角卡片，标题和说明靠左；按钮是胶囊：两个时并排，其余竖排；按钮离卡片边 [MochiDialogDefaults.ButtonInset]，
 *   卡片圆角 = 按钮半高 + 它，按钮和卡片同心
 * - 和标签栏一样的高光 / 形变：按钮按下放大、发光、跟手，并排的两个按钮之间拉出融球颈；
 *   整张卡片被手指带着轻微挪动，边缘接到按钮的环境光
 * - 预见式返回：返回手势拖动时卡片跟着缩小、朝手势那侧偏一点，后面的压暗变浅；松手关闭，取消则弹回
 *
 * 默认点外面不关闭（iOS 的警告框也是这样），返回键 / 返回手势关闭；[dismissOnBack] 为 false 时返回手势只让卡片
 * 跟着缩一下、松手弹回，不关闭（配合按住才关闭的按钮，确保提示被看到）。
 * 点任何一个按钮：先执行它的 onClick，再调 [onDismissRequest]。
 *
 * [icon] 放在标题上面。每次弹出，图标位里的 `MochiIonicon`（mochi-icons）会一笔一笔描出来、实心部分最后淡入
 * （通过 [LocalMochiIconDraw]），所有带图标的对话框出场一致，调用方不用自己写动画。警告类提示用 "warning" 之类的描边图标。
 */
@Suppress("ktlint:compose:modifier-not-used-at-root")
@Composable
public fun MochiDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    actions: ImmutableList<MochiDialogAction>,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: (@Composable () -> Unit)? = null,
    dismissOnOutsideTap: Boolean = false,
    dismissOnBack: Boolean = true,
    colors: MochiDialogColors = MochiDialogDefaults.colors(),
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentVisible by rememberUpdatedState(visible)
    val presence = remember { Animatable(0f) }
    val back = remember { Animatable(0f) }
    // 图标的描绘进度：每次弹出从头画（LocalMochiIconDraw），关闭途中又打开时接着画完
    val iconDraw = remember { Animatable(if (visible) 0f else 1f) }
    var composed by remember { mutableStateOf(visible) }
    var closing by remember { mutableStateOf(false) }
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val hostFocus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val framePeriod = rememberFramePeriodNanos()
    LaunchedEffect(visible) {
        if (visible) {
            val fresh = !composed || presence.value == 0f
            // 页面上原来聚焦的输入框先放掉：不然对话框关掉后焦点会回到它身上、键盘又弹出来
            hostFocus.clearFocus(force = true)
            composed = true
            closing = false
            back.snapTo(0f)
            // 新弹出的对话框：先等它组合、排版、画完、帧率回稳再动，开头几帧不被排版吞掉（关闭途中又打开不等）
            if (fresh) {
                iconDraw.snapTo(0f)
                awaitFrameSettle(framePeriod)
            }
            // 卡片放大到位的同时，图标一笔一笔画出来（比卡片稍晚起笔，卡片还在缩时看不清细节）
            launch {
                delay(IconDrawDelayMillis)
                iconDraw.animateTo(1f, tween(IconDrawMillis, easing = LinearEasing))
            }
            presence.animateTo(1f, MochiMotions.responseSpring(0.36f, 0.78f))
        } else if (composed) {
            // 对话框里有输入框时，关掉的同时收起键盘（iOS）
            keyboard?.hide()
            closing = true
            presence.animateTo(0f, MochiMotions.responseSpring(0.22f, 1f))
            composed = false
            back.snapTo(0f)
        }
    }
    if (!composed) return

    InWindowOverlay(leaving = { closing || !currentVisible }) {
        val scope = rememberCoroutineScope()
        PredictiveBackHandler(enabled = visible) { events ->
            try {
                events.collect { e ->
                    edge = e.swipeEdge
                    back.snapTo(e.progress)
                }
                if (dismissOnBack) {
                    currentOnDismiss()
                } else {
                    scope.launch { back.animateTo(0f, MochiMotions.responseSpring(0.3f, 0.8f)) }
                }
            } catch (c: CancellationException) {
                scope.launch { back.animateTo(0f, MochiMotions.responseSpring(0.3f, 0.8f)) }
                throw c
            }
        }

        val inset = MochiDialogDefaults.ButtonInset
        val corner = MochiDialogDefaults.ButtonHeight / 2 + inset
        val shape = remember(corner) { MochiRoundedRectangle(corner) }
        val dark = MochiTheme.colorScheme.isDark
        val shadowColor = MochiTheme.colorScheme.shadow
        val shadow = MochiTheme.shadows.floating
        // 卡片在根坐标里的范围：点在卡片外面才算「点外面」
        val card = remember { floatArrayOf(0f, 0f, 0f, 0f) }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // 后面压暗；预见式返回拖动时变浅一点，提示「松手就回去了」
                    val a = presence.value.coerceIn(0f, 1f) * (1f - 0.4f * back.value)
                    if (a > 0f) drawRect(colors.scrim, alpha = a)
                }
                // 拦住后面页面的触摸；可选点外面关闭
                .pointerInput(dismissOnOutsideTap) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p = down.position
                        val outside = p.x < card[0] || p.y < card[1] || p.x > card[2] || p.y > card[3]
                        val up = waitForUpOrCancellation()
                        if (up != null && outside && dismissOnOutsideTap && visible) currentOnDismiss()
                    }
                }
                .systemBarsPadding()
                // 里面有输入框时，键盘弹出把卡片顶到键盘上方居中（跟着键盘动画走）
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            MochiConcentricContainer(corner, inset) {
                // 主题有警告框玻璃（alertGlass）且浮层底下有页面录制时，卡片画成玻璃；否则纯色
                val glass = rememberGlassFill(GlassRole.Alert)
                Box(
                    modifier
                        .padding(horizontal = 36.dp)
                        .widthIn(max = MochiDialogDefaults.MaxWidth)
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            val b = it.boundsInRoot()
                            card[0] = b.left
                            card[1] = b.top
                            card[2] = b.right
                            card[3] = b.bottom
                        }
                        // 跟手形变放在最外层：卡片连同投影、文字、按钮整个一起被手指带着挪动、朝手指那侧略微拉长
                        .mochiPressDeform(scale = 1f, follow = 6.dp)
                        .graphicsLayer {
                            val p = presence.value
                            val b = back.value
                            // 出现：1.12 → 1；消失：1 → 0.94；预见式返回：最多缩到 0.9，朝手势那侧偏 12dp
                            val enter = if (closing) lerp(0.94f, 1f, p) else lerp(1.12f, 1f, p)
                            val s = enter * (1f - 0.1f * b)
                            scaleX = s
                            scaleY = s
                            alpha = p.coerceIn(0f, 1f)
                            val dir = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                            translationX = dir * 12.dp.toPx() * b
                            transformOrigin = TransformOrigin.Center
                            // 不裁切：按钮按下放大时可以探出卡片一点（和别处的按钮一样）
                            this.shape = shape
                            clip = false
                        }
                        // 柔和投影（和菜单同一套），不用系统按光源算的阴影：那个底边一道实影，又硬又重。玻璃自己带外阴影
                        .then(
                            if (glass == null) {
                                Modifier.drawBehind {
                                    drawMochiShadow(
                                        size.toRect(),
                                        corner.toPx(),
                                        shadow.resolve(this, dark, presence.value.coerceIn(0f, 1f)),
                                        shadowColor,
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .then(if (glass == null) Modifier.background(colors.container, shape) else Modifier)
                        .mochiAnimateHeight(shape),
                    // 边缘接按钮的环境光
                ) {
                    if (glass != null) GlassSurface(glass, corner, null, Modifier.matchParentSize())
                    Column {
                        Column(
                            Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (icon != null) {
                                Box(Modifier.padding(bottom = 4.dp)) {
                                    CompositionLocalProvider(LocalMochiIconDraw provides { iconDraw.value }) { icon() }
                                }
                            }
                            if (title.isNotEmpty()) {
                                Text(
                                    title,
                                    style = MochiTheme.textStyles.navTitle,
                                    color = colors.title,
                                )
                            }
                            if (message != null) {
                                Text(
                                    message,
                                    style = MochiTheme.textStyles.bodyLarge.copy(
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        letterSpacing = 0.sp,
                                    ),
                                    color = colors.message,
                                )
                            }
                            content?.invoke(this)
                        }
                        DialogButtons(actions, colors, inset) { a ->
                            a.onClick()
                            currentOnDismiss()
                        }
                    }
                }
            }
        }
    }
}

/** 图标描绘出场：弹出后多久起笔、整个描绘多长 */
private const val IconDrawDelayMillis = 90L
private const val IconDrawMillis = 720

@Composable
private fun DialogButtons(
    actions: ImmutableList<MochiDialogAction>,
    colors: MochiDialogColors,
    inset: Dp,
    onAction: (MochiDialogAction) -> Unit,
) {
    val m = Modifier.padding(start = inset, end = inset, bottom = inset, top = 20.dp)
    // iOS 警告框的规矩：并排两个时「取消」在左边；竖排时「取消」在最下面（不管传进来的顺序）
    val cancelFirst = actions.sortedBy { if (it.role == MochiDialogActionRole.Cancel) 0 else 1 }
    val cancelLast = actions.sortedBy { if (it.role == MochiDialogActionRole.Cancel) 1 else 0 }
    if (actions.size == 2) {
        // 并排：按住一个，它和旁边那个之间拉出融球颈
        MochiButtonGroup(m.fillMaxWidth(), spacing = 8.dp) {
            cancelFirst.forEach { a -> DialogButton(a, colors, onClick = { onAction(a) }, modifier = Modifier.weight(1f)) }
        }
    } else {
        Column(m.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cancelLast.forEach { a -> DialogButton(a, colors, onClick = { onAction(a) }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun DialogButton(action: MochiDialogAction, colors: MochiDialogColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = action.role == MochiDialogActionRole.Primary
    val base = MochiButtonDefaults.colors(if (primary) MochiButtonStyle.Filled else MochiButtonStyle.Tonal)
    val c = base.copy(
        container = if (primary) colors.primary else colors.button,
        content = when (action.role) {
            MochiDialogActionRole.Primary -> colors.onPrimary
            MochiDialogActionRole.Destructive -> colors.destructive
            else -> colors.buttonContent
        },
    )
    if (action.holdToConfirm) {
        MochiHoldButton(
            onConfirm = onClick,
            modifier = modifier.height(MochiDialogDefaults.ButtonHeight),
            enabled = action.enabled,
            colors = MochiHoldButtonDefaults.colors(
                container = c.container,
                content = c.content,
                progress = c.content.copy(alpha = if (primary) 0.24f else 0.14f),
            ),
        ) {
            Text(action.label, style = MochiTheme.textStyles.navTitle, maxLines = 1)
        }
        return
    }
    MochiButton(
        onClick = onClick,
        modifier = modifier,
        enabled = action.enabled,
        // iOS 26 警告框的按钮是玻璃（主题有控件玻璃时）；没有玻璃时退回填充 / 色调的纯色
        style = if (primary) MochiButtonStyle.ProminentGlass else MochiButtonStyle.Glass,
        colors = c,
        minHeight = MochiDialogDefaults.ButtonHeight,
        contentPadding = PaddingValues(horizontal = 12.dp),
        // 警告框的按钮又宽又挨着：按下每边最多长 3dp，不然放大得很突兀、还会顶到对话框边
        behavior = MochiButtonDefaults.behavior(maxGrow = DialogButtonMaxGrow),
    ) {
        Text(
            action.label,
            // iOS 26 alert 按钮：17 Semibold
            style = MochiTheme.textStyles.navTitle,
            maxLines = 1,
        )
    }
}

/** 对话框按钮按下时每边最多长出多少 */
private val DialogButtonMaxGrow = 3.dp
