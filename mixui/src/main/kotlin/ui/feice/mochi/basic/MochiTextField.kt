// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.content.MochiBlurVisibility
import ui.feice.mochi.effect.content.MochiCounterText
import ui.feice.mochi.effect.content.mochiAnimateHeight
import ui.feice.mochi.effect.content.mochiBlur
import ui.feice.mochi.effect.content.mochiBlurAlpha
import ui.feice.mochi.effect.content.mochiRevealBlur
import ui.feice.mochi.list.ListItemInfo
import ui.feice.mochi.list.LocalListCardClip
import ui.feice.mochi.list.MochiListColors
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.shape.mochiBorder
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiIcon
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.rememberMochiHaptics

/** 输入框配色 */
@Immutable
public data class MochiTextFieldColors(
    val container: Color,
    val content: Color,
    val placeholder: Color,
    val cursor: Color,
    /** 行首图标、清除按钮、小眼睛 */
    val icon: Color,
    /** 出错时的说明文字和光标 */
    val error: Color,
    /** 出错时的底色 */
    val errorContainer: Color,
    /** 说明文字、字数 */
    val supporting: Color,
)

public object MochiTextFieldDefaults {
    /**
     * 单行输入框的高度；圆角是它的一半，单行时是胶囊，多行长高后变成圆角矩形。
     * 直接取按钮的标准高度：输入框旁边并排的按钮 / 图标按钮（MochiButton、MochiIconButton 默认尺寸）和它一样高、同一条中线
     */
    public val Height: Dp = MochiButtonDefaults.Height

    /** 输入框里行尾按钮离胶囊边的距离（上下右都是它），按钮和胶囊同心 */
    public val TrailingInset: Dp = 6.dp

    /** 输入框里的行尾按钮（发送键这类）的尺寸 = 高度 − 2 × [TrailingInset] */
    public val TrailingButtonSize: Dp = Height - TrailingInset * 2

    /** 文字离左右边的距离 */
    public val HorizontalPadding: Dp = 16.dp

    /** 默认形状：半径 = 单行高度的一半 */
    public val Shape: Shape = MochiRoundedRectangle(Height / 2)

    @Composable
    public fun colors(
        container: Color = MochiDefaults.fill().copy(alpha = if (MochiTheme.colorScheme.isDark) 0.2f else 0.08f),
        content: Color = MochiTheme.colorScheme.onSurface,
        placeholder: Color = MochiTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        cursor: Color = MochiTheme.colorScheme.primary,
        icon: Color = MochiTheme.colorScheme.onSurfaceVariant,
        error: Color = MochiTheme.colorScheme.error,
        errorContainer: Color = MochiTheme.colorScheme.error.copy(alpha = 0.12f),
        supporting: Color = MochiTheme.colorScheme.onSurfaceVariant,
    ): MochiTextFieldColors = remember(container, content, placeholder, cursor, icon, error, errorContainer, supporting) {
        MochiTextFieldColors(container, content, placeholder, cursor, icon, error, errorContainer, supporting)
    }
}

/**
 * iOS 26 的输入框：
 * - 填充底色，不画边框、不画下划线、聚焦不变色，只有光标是主色；单行是胶囊，多行随内容长高（高度平滑过渡）、变成圆角矩形
 * - 聚焦且有内容时，右边模糊淡入一颗清除按钮（iOS 的 whileEditing）
 * - [secure]：密码点，右边小眼睛切换显示，斜杠原位画出 / 收回
 * - [error] 不为 null：底色带一点警示色、下面模糊淡入说明，并左右抖一下（iOS 密码输错）；[shakeTrigger] 变化时也抖
 * - [maxLength]：超出的字输不进去，下方右侧的字数像计数器一样滚动
 * - [selectAllOnFocus]：获得焦点时选中全部文字，一输入就整段换掉（Safari 的地址栏、改名框）
 *
 * 想要聚焦，把 `Modifier.focusRequester(...)` 传进 [modifier] 就行。
 */
@Composable
public fun MochiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 6,
    secure: Boolean = false,
    error: String? = null,
    supportingText: String? = null,
    maxLength: Int? = null,
    clearButton: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    shakeTrigger: Any? = error,
    shape: Shape = MochiTextFieldDefaults.Shape,
    textStyle: TextStyle = MochiTheme.textStyles.body,
    colors: MochiTextFieldColors = MochiTextFieldDefaults.colors(),
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selectAllOnFocus: Boolean = false,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // 选区自己记着；文字从外面改了（清除、换了一份）就按新文字、光标放到末尾
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val current = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    var revealed by remember { mutableStateOf(false) }
    val shake = rememberMochiShake(shakeTrigger)
    val container by animateColorAsState(
        if (error != null) colors.errorContainer else colors.container,
        spring(stiffness = 500f),
        label = "fieldContainer",
    )
    val density = LocalDensity.current
    val lineHeight = with(density) { textStyle.lineHeight.takeIf { it.isSpecified }?.toDp() ?: textStyle.fontSize.toDp() * 1.3f }
    // 单行时高度就是 Height（文字在里面居中，不由字体实际行高撑大，中文回退字体的行高比 lineHeight 略大）；
    // 多行时上下留白比单行的居中留白略小一点，保证单行永远落在 Height 里
    val textPad = ((MochiTextFieldDefaults.Height - lineHeight) / 2 - 2.dp).coerceAtLeast(8.dp)
    val hasTrailing = clearButton || secure || trailing != null
    val alpha = if (enabled) 1f else MochiDefaults.DisabledAlpha
    val bringer = rememberFieldBringer(focused)

    Column(
        modifier
            .mochiFieldBringer(bringer)
            .graphicsLayer {
                translationX = shake.value.dp.toPx()
                this.alpha = alpha
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(container, shape)
                .mochiAnimateHeight(shape)
                .heightIn(min = MochiTextFieldDefaults.Height)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { requester.requestFocus() }
                .padding(
                    start = if (leading != null) 14.dp else MochiTextFieldDefaults.HorizontalPadding,
                    end = if (hasTrailing) MochiTextFieldDefaults.TrailingInset else MochiTextFieldDefaults.HorizontalPadding,
                ),
            // 多行时行首图标、按钮都和最后一行对齐（iMessage 的发送键在右下角）
            verticalAlignment = Alignment.Bottom,
        ) {
            if (leading != null) {
                FieldSlot {
                    CompositionLocalProvider(ui.feice.mochi.theme.LocalContentColor provides colors.icon) { leading() }
                }
                Spacer(Modifier.width(10.dp))
            }
            Box(
                Modifier.weight(1f).heightIn(min = MochiTextFieldDefaults.Height).padding(vertical = textPad),
                contentAlignment = Alignment.CenterStart,
            ) {
                FieldPlaceholder(placeholder, value.isEmpty(), textStyle, colors.placeholder)
                MochiSelectionColors(colors.cursor) {
                    BasicTextField(
                        value = current,
                        onValueChange = { v ->
                            val t = if (maxLength != null) v.text.take(maxLength) else v.text
                            field = if (t == v.text) v else TextFieldValue(t, v.selection.clampTo(t.length))
                            if (t != value) onValueChange(t)
                        },
                        enabled = enabled,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        textStyle = textStyle.copy(color = colors.content),
                        cursorBrush = SolidColor(if (error != null) colors.error else colors.cursor),
                        visualTransformation = if (secure && !revealed) PasswordVisualTransformation('•') else VisualTransformation.None,
                        keyboardOptions = if (secure && keyboardOptions.keyboardType == KeyboardType.Unspecified) {
                            keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                        } else {
                            keyboardOptions
                        },
                        keyboardActions = keyboardActions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requester)
                            .onFocusChanged {
                                if (selectAllOnFocus && it.isFocused && !focused) {
                                    field = current.copy(selection = TextRange(0, current.text.length))
                                }
                                focused = it.isFocused
                            },
                    )
                }
            }
            if (clearButton) {
                ClearSlot(
                    focused && value.isNotEmpty() && enabled,
                    { onValueChange("") },
                    colors.icon,
                    container,
                    Modifier.size(width = 32.dp, height = MochiTextFieldDefaults.Height),
                )
            }
            if (secure) {
                val slash by animateFloatAsState(if (revealed) 1f else 0f, MochiMotions.responseSpring(0.3f, 0.9f), label = "eye")
                FieldSlot(
                    Modifier
                        .width(40.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { revealed = !revealed }
                        .semantics { contentDescription = if (revealed) "隐藏密码" else "显示密码" },
                ) { EyeGlyph(colors.icon, slash) }
            }
            if (trailing != null) {
                FieldSlot { CompositionLocalProvider(ui.feice.mochi.theme.LocalContentColor provides colors.icon) { trailing() } }
            }
        }
        // 说明 / 错误 / 字数：没有的时候整行不占地方，出现时模糊淡入、把下面的东西推开
        val message = error ?: supportingText
        MochiBlurVisibility(message != null || maxLength != null, contentAlignment = Alignment.TopStart) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = MochiTextFieldDefaults.HorizontalPadding, end = MochiTextFieldDefaults.HorizontalPadding, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MochiBlurText(
                    message.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MochiTheme.textStyles.footnote,
                    color = if (error != null) colors.error else colors.supporting,
                )
                if (maxLength != null) {
                    val left = maxLength - value.length
                    MochiCounterText(
                        value.length.toLong(),
                        Modifier.padding(start = 8.dp),
                        style = MochiTheme.textStyles.footnote,
                        color = if (left <= 0) colors.error else colors.supporting,
                        format = { "$it / $maxLength" },
                    )
                }
            }
        }
    }
}

/**
 * iOS 设置 / 通讯录表单里的输入行，放在 MochiListSection 里：左边可选的标签（「姓名」），右边一直到行尾都是输入区。
 * 行高和同一张卡片里的普通 MochiListRow 相同（同样的最小高度和上下留白），多行时自然长高；
 * 聚焦且有内容时行尾原地模糊渐显清除按钮（占位固定，文字不跟着挪）。点整行任何地方都会聚焦。
 * 表单里不能打字、点了选值的行（生日、地区）用 [MochiListFormRow]，值和输入区对齐成同一列。
 *
 * @param labelWidth 标签占的宽度；同一张卡片里几行用同一个值，输入区就左对齐成一列
 */
@Composable
public fun MochiListTextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 5,
    secure: Boolean = false,
    labelWidth: Dp = MochiListTextFieldDefaults.LabelWidth,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: MochiListColors = MochiListDefaults.colors(),
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val style = MochiTheme.textStyles.body
    val bringer = rememberFieldBringer(focused)
    FormRowShell(
        label,
        labelWidth,
        enabled,
        colors,
        endPadding = 10.dp,
        highlight = false,
        onClick = { requester.requestFocus() },
        modifier = modifier.mochiFieldBringer(bringer),
    ) {
        Box(Modifier.weight(1f).padding(vertical = MochiListDefaults.RowVerticalPadding).padding(end = 12.dp)) {
            FieldPlaceholder(placeholder, value.isEmpty(), style, colors.secondary.copy(alpha = 0.7f))
            MochiSelectionColors(colors.accent) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    textStyle = style.copy(color = colors.content),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = if (secure) PasswordVisualTransformation('•') else VisualTransformation.None,
                    keyboardOptions = if (secure && keyboardOptions.keyboardType == KeyboardType.Unspecified) {
                        keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                    } else {
                        keyboardOptions
                    },
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requester)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
        }
        ClearSlot(
            focused && value.isNotEmpty() && enabled,
            { onValueChange("") },
            colors.secondary,
            colors.container,
            Modifier.size(width = 32.dp, height = 36.dp),
        )
    }
}

/**
 * 表单里点了去选值的行（通讯录的「生日」、「地区」）：和 [MochiListTextFieldRow] 同一套布局，
 * 标签占同样的宽度，值从同一列开始（不像设置页那样靠右），和上下的输入行对齐；点整行有按下高亮。
 * 没有值时显示 [placeholder]（淡色）。值换掉时模糊替换。
 */
@Composable
public fun MochiListFormRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    labelWidth: Dp = MochiListTextFieldDefaults.LabelWidth,
    colors: MochiListColors = MochiListDefaults.colors(),
) {
    FormRowShell(
        label,
        labelWidth,
        enabled,
        colors,
        endPadding = MochiListDefaults.TextRowPadding,
        highlight = true,
        onClick = onClick,
        modifier = modifier,
    ) {
        MochiBlurText(
            value ?: placeholder,
            modifier = Modifier.weight(1f).padding(vertical = MochiListDefaults.RowVerticalPadding),
            style = MochiTheme.textStyles.body,
            color = if (value != null) colors.content else colors.secondary.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

public object MochiListTextFieldDefaults {
    /** 表单行标签的宽度：同一张卡片里都用它，输入区和值对齐成一列 */
    public val LabelWidth: Dp = 88.dp
}

/**
 * 表单行的外壳：标签 + 内容列。行高规则和普通 MochiListRow 一样（至少 RowMinHeight、上下至少 RowVerticalPadding、整行居中），
 * 标签从 22dp 文字边开始，内容列从 22dp + [labelWidth] 开始；输入行和取值行共用它，所以列一定对齐。
 */
@Composable
private fun FormRowShell(
    label: String?,
    labelWidth: Dp,
    enabled: Boolean,
    colors: MochiListColors,
    endPadding: Dp,
    highlight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val info = remember { ListItemInfo(MochiListDefaults.TextRowPadding) }
    val grouped = LocalListCardClip.current != null
    info.ownsVerticalInsets = grouped
    val press = info.press
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val alpha = if (enabled) 1f else MochiDefaults.DisabledAlpha
    Row(
        modifier
            .layoutId(info)
            .fillMaxWidth()
            // 按下高亮连同卡片给首末行多留的白一起盖住（和 MochiListRow 一样）
            .drawBehind {
                if (press.value > 0f) {
                    drawRect(
                        colors.highlight,
                        Offset(0f, -info.extraTop),
                        Size(size.width, size.height + info.extraTop + info.extraBottom),
                        alpha = press.value,
                    )
                }
            }
            .defaultMinSize(minHeight = MochiListDefaults.RowMinHeight)
            .then(
                if (!enabled) {
                    Modifier
                } else if (highlight) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // 稍等再亮：手指是来滚动的话不闪
                                val lit = scope.launch {
                                    delay(70)
                                    press.snapTo(1f)
                                }
                                val released = tryAwaitRelease()
                                lit.cancel()
                                if (released) press.snapTo(1f)
                                scope.launch { press.animateTo(0f, tween(if (released) 350 else 120)) }
                            },
                            onTap = { currentOnClick() },
                        )
                    }
                } else {
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { currentOnClick() }
                },
            )
            .padding(start = MochiListDefaults.TextRowPadding, end = endPadding)
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { p.placeWithLayer(0, 0) { translationY = info.contentCenterOffset() } }
            }
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(
                label,
                style = MochiTheme.textStyles.body,
                color = colors.content,
                maxLines = 1,
                modifier = Modifier.width(labelWidth).padding(vertical = MochiListDefaults.RowVerticalPadding).padding(end = 8.dp),
            )
        }
        content()
    }
}

/**
 * 原地更改（iOS「文件」里重命名、提醒事项改列表名那种）：文字原地变成输入框，位置、字号一点不动；
 * 后面淡入一块圆角底色，全选原文字，键盘弹出。
 * - 键盘上的「完成」或焦点离开：提交（前后空白去掉；空的不提交，恢复原名，除非 [allowEmpty]）
 * - 返回键：放弃修改，恢复原文字
 *
 * 编辑状态由调用方持有：点文字本身（[editOnTap]）、或别处的「重命名」按钮把 [editing] 设成 true 都行。
 * @param onValueChange 只在提交时调用，参数是新的文字
 */
@Composable
public fun MochiInlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MochiTheme.textStyles.body,
    color: Color = MochiTheme.colorScheme.onSurface,
    maxLines: Int = 1,
    editOnTap: Boolean = true,
    allowEmpty: Boolean = false,
    highlight: Color = MochiTheme.colorScheme.primary.copy(alpha = 0.12f),
    cursor: Color = MochiTheme.colorScheme.primary,
) {
    var draft by remember { mutableStateOf(TextFieldValue(value)) }
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val holder = remember { booleanArrayOf(false) }
    val currentValue by rememberUpdatedState(value)
    val p by animateFloatAsState(if (editing) 1f else 0f, MochiMotions.responseSpring(0.34f, 0.82f), label = "inlineEdit")
    val bg = remember { MochiRoundedRectangle(10.dp) }
    // 底色只包住文字本身（输入框可能比文字宽）
    var inkLeft by remember { mutableFloatStateOf(0f) }
    var inkRight by remember { mutableFloatStateOf(0f) }

    fun finish(commit: Boolean) {
        if (!holder[0] && !editing) return
        holder[0] = false
        val t = draft.text.trim()
        val accept = commit && (t.isNotEmpty() || allowEmpty)
        if (accept && t != currentValue) onValueChange(t)
        draft = TextFieldValue(if (accept) t else currentValue)
        onEditingChange(false)
        focus.clearFocus()
    }

    LaunchedEffect(editing) {
        if (editing) {
            draft = TextFieldValue(currentValue, TextRange(0, currentValue.length))
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    LaunchedEffect(value) { if (!editing) draft = TextFieldValue(value) }
    BackHandler(enabled = editing) { finish(false) }

    MochiSelectionColors(cursor) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = editing,
            singleLine = maxLines == 1,
            maxLines = maxLines,
            textStyle = style.copy(color = color),
            cursorBrush = SolidColor(cursor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish(true) }),
            onTextLayout = { t ->
                if (t.lineCount > 0) {
                    inkLeft = (0 until t.lineCount).minOf { t.getLineLeft(it) }
                    inkRight = (0 until t.lineCount).maxOf { t.getLineRight(it) }
                }
            },
            modifier = modifier
                .drawBehind {
                    if (p > 0.005f) {
                        // 底色画在文字外面：往外扩，不改变布局，文字一点不动
                        val padX = lerp(2.dp.toPx(), 8.dp.toPx(), p)
                        val padY = lerp(0f, 4.dp.toPx(), p)
                        // 空的时候也留一小块，看得出是可以输入的
                        val right = maxOf(inkRight, inkLeft + 24.dp.toPx()).coerceAtMost(size.width)
                        val s = Size(right - inkLeft + padX * 2, size.height + padY * 2)
                        translate(inkLeft - padX, -padY) {
                            drawOutline(bg.createOutline(s, layoutDirection, this), highlight, alpha = p.coerceIn(0f, 1f))
                        }
                    }
                }
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        holder[0] = true
                    } else if (holder[0]) {
                        finish(true)
                    }
                }
                .then(
                    if (!editing && editOnTap) {
                        Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { onEditingChange(true) }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/**
 * 对话框里的输入框（iOS 的「新建文件夹」「输入 Wi-Fi 密码」），放进 MochiDialog 的 content：
 * 胶囊输入框，左右往外伸到和对话框按钮对齐（离卡片边 [MochiDialogDefaults.ButtonInset]，和卡片同心），
 * 底色和对话框按钮一样；对话框出现时自动聚焦、弹出键盘（[autoFocus]），对话框会被键盘顶上去。
 */
@Composable
public fun MochiDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secure: Boolean = false,
    autoFocus: Boolean = true,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: MochiTextFieldColors = MochiTextFieldDefaults.colors(
        container = MochiTheme.colorScheme.run { if (isDark) surfaceContainerHighest else surfaceContainerHigh },
    ),
) {
    val requester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    // 对话框内容的左右边距是 24dp，按钮离卡片边 14dp：输入框两边各伸出 10dp，和按钮一样宽
    val outset = 24.dp - MochiDialogDefaults.ButtonInset
    MochiTextField(
        value,
        onValueChange,
        modifier
            .padding(top = 8.dp)
            .layout { measurable, constraints ->
                val o = outset.roundToPx()
                val p = measurable.measure(
                    constraints.copy(
                        minWidth = (constraints.minWidth + o * 2).coerceAtMost(constraints.maxWidth + o * 2),
                        maxWidth = constraints.maxWidth + o * 2,
                    ),
                )
                layout((p.width - o * 2).coerceAtLeast(0), p.height) { p.place(-o, 0) }
            }
            .focusRequester(requester),
        placeholder = placeholder,
        secure = secure,
        error = error,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = colors,
    )
}

/**
 * 验证码输入：一排圆角格子，一格一位。
 * - 当前要输的那格描主色边、光标闪烁；数字进来时模糊放大淡入，删掉时原路退回
 * - 输满 [length] 位调 [onComplete]；[error] 为 true 时格子带警示色，[shakeTrigger] 变化时整排抖一下
 * - 点任何一格都会聚焦、弹出数字键盘；支持系统的短信验证码自动填充（整段粘进来）
 */
@Composable
public fun MochiCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    enabled: Boolean = true,
    error: Boolean = false,
    shakeTrigger: Any? = null,
    onComplete: (String) -> Unit = {},
    colors: MochiTextFieldColors = MochiTextFieldDefaults.colors(),
) {
    var focused by remember { mutableStateOf(false) }
    val shake = rememberMochiShake(shakeTrigger)
    val bringer = rememberFieldBringer(focused)
    val currentOnComplete by rememberUpdatedState(onComplete)
    val cellShape = remember { MochiRoundedRectangle(14.dp) }
    val blink = rememberInfiniteTransition(label = "caret")
    val caret by blink.animateFloat(
        1f,
        0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0
                1f at 450
                0f at 550
                0f at 950
            },
            RepeatMode.Restart,
        ),
        label = "caretAlpha",
    )
    BasicTextField(
        value = TextFieldValue(value, TextRange(value.length)),
        onValueChange = { v ->
            val d = v.text.filter(Char::isDigit).take(length)
            if (d != value) {
                onValueChange(d)
                if (d.length == length) currentOnComplete(d)
            }
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = modifier
            .fillMaxWidth()
            .mochiFieldBringer(bringer)
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box {
                // 真正的输入框藏在格子下面：只负责接键盘和焦点
                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until length) {
                        val active = focused && enabled && (i == value.length || (value.length == length && i == length - 1))
                        val ring by animateFloatAsState(if (active) 1f else 0f, tween(160), label = "cellRing")
                        val fill = if (error) colors.errorContainer else colors.container
                        Box(
                            Modifier
                                .weight(1f)
                                .widthIn(max = 56.dp)
                                .heightIn(min = 56.dp)
                                .background(fill, cellShape)
                                .mochiBorder(2.dp, (if (error) colors.error else colors.cursor).copy(alpha = ring), cellShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            MochiBlurCrossfade(
                                value.getOrNull(i),
                                transition = ui.feice.mochi.effect.content.MochiBlurTransitions.Appear,
                            ) { c ->
                                if (c != null) {
                                    Text(
                                        c.toString(),
                                        style = MochiTheme.textStyles.body.copy(
                                            fontSize = 26.sp,
                                            lineHeight = 32.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                            fontFeatureSettings = "tnum",
                                        ),
                                        color = if (error) colors.error else colors.content,
                                    )
                                }
                            }
                            if (active && i == value.length) {
                                Box(
                                    Modifier
                                        .size(width = 2.dp, height = 26.dp)
                                        .graphicsLayer { this.alpha = caret }
                                        .background(colors.cursor, CircleShape),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/** 抖一下（iOS 密码输错）：trigger 每变一次（第一次组合不算、变成 null 不算）左右衰减着晃几下，并轻震 */
@Composable
internal fun rememberMochiShake(trigger: Any?): Animatable<Float, AnimationVector1D> {
    val offset = remember { Animatable(0f) }
    val haptics = rememberMochiHaptics()
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (trigger == null || trigger == false) return@LaunchedEffect
        haptics.threshold()
        offset.snapTo(0f)
        // 欠阻尼弹簧：初速度把它甩出去约 10dp，来回衰减四五下停住
        offset.animateTo(0f, spring(dampingRatio = 0.16f, stiffness = 1100f), initialVelocity = 360f)
    }
    return offset
}

/**
 * 聚焦时把整个输入框（连同下面的说明、字数）滚到键盘上方。只靠系统的话只保证光标那一行露出来，
 * 胶囊的下半截和说明会被键盘挡住；键盘每次动完（高度稳定下来）再确认一遍。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberFieldBringer(focused: Boolean): FieldBringer {
    val bringer = remember { FieldBringer() }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }.collectLatest {
            delay(80)
            val sz = bringer.size
            // 下面多留一点，不贴着键盘
            val margin = with(density) { 16.dp.toPx() }
            bringer.requester.bringIntoView(Rect(0f, 0f, sz.width.toFloat(), sz.height + margin))
        }
    }
    return bringer
}

@OptIn(ExperimentalFoundationApi::class)
internal class FieldBringer {
    val requester = BringIntoViewRequester()
    var size = IntSize.Zero
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.mochiFieldBringer(b: FieldBringer): Modifier = bringIntoViewRequester(b.requester).onSizeChanged { b.size = it }

/** 选中文字的底色和水滴把手用主色 */
@Composable
internal fun MochiSelectionColors(color: Color, content: @Composable () -> Unit) {
    val colors = remember(color) { TextSelectionColors(handleColor = color, backgroundColor = color.copy(alpha = 0.3f)) }
    CompositionLocalProvider(LocalTextSelectionColors provides colors, content = content)
}

/** 占位文字：开始输入时很快淡掉（不瞬切），清空时淡回来 */
@Composable
private fun FieldPlaceholder(text: String, visible: Boolean, style: TextStyle, color: Color) {
    if (text.isEmpty()) return
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 180 else 90), label = "placeholder")
    if (a > 0f) Text(text, style = style, color = color, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = a })
}

/** 输入框里图标 / 按钮的位置：单行高度那么高，内容居中（多行时落在最后一行） */
@Composable
private fun FieldSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.height(MochiTextFieldDefaults.Height), contentAlignment = Alignment.Center) { content() }
}

/**
 * 清除按钮的位置：占位一直在（文字不会因为它出现消失而左右挪），按钮本身原地模糊渐显、略微放大到位；
 * 看不见的时候点不到
 */
@Composable
private fun ClearSlot(visible: Boolean, onClear: () -> Unit, color: Color, hole: Color, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(if (visible) 1f else 0f, MochiMotions.responseSpring(0.3f, 1f), label = "clear")
    Box(
        modifier.then(
            if (visible) {
                Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onClear() }
                    .semantics { contentDescription = "清除" }
            } else {
                Modifier
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                mochiBlurAlpha(v.coerceIn(0f, 1f), mochiRevealBlur(v, 6.dp))
                val sc = 0.8f + 0.2f * v.coerceIn(0f, 1f)
                scaleX = sc
                scaleY = sc
            }.mochiBlur { mochiRevealBlur(v, 6.dp) },
        ) { ClearBadge(color, hole) }
    }
}

/** iOS 的清除按钮：灰色实心小圆，中间镂空的 ✕ */
@Composable
private fun ClearBadge(color: Color, hole: Color) {
    Box(Modifier.size(18.dp).background(color.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
        ClearGlyph(if (hole.alpha < 0.9f) Color.White else hole)
    }
}

/** 小眼睛：[slash] 0 = 睁眼，1 = 斜杠画满（密码正在显示，点了会藏起来） */
@Composable
private fun EyeGlyph(color: Color, slash: Float) {
    MochiIcon("eye", Modifier.size(22.dp), tint = color) { slot ->
        // 离屏画：斜杠旁边那道缝是真的挖空，透出输入框底色
        Canvas(slot.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val w = size.width
            val h = size.height
            val sw = w * 0.085f
            val eye = Path().apply {
                moveTo(w * 0.06f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.02f, w * 0.94f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.98f, w * 0.06f, h * 0.5f)
                close()
            }
            drawPath(eye, color, style = Stroke(sw))
            drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            if (slash > 0.01f) {
                val a = Offset(w * 0.16f, h * 0.14f)
                val b = Offset(w * 0.84f, h * 0.86f)
                val e = Offset(lerp(a.x, b.x, slash.coerceIn(0f, 1f)), lerp(a.y, b.y, slash.coerceIn(0f, 1f)))
                // 先挖一道缝，斜杠和眼睛之间留白（SF Symbols 的 eye.slash）
                drawLine(Color.Black, a, e, sw * 3f, StrokeCap.Round, blendMode = BlendMode.Clear)
                drawLine(color, a, e, sw, StrokeCap.Round)
            }
        }
    }
}

/** 截短文字后把选区收进新长度 */
private fun TextRange.clampTo(length: Int): TextRange = TextRange(start.coerceAtMost(length), end.coerceAtMost(length))
