package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.isUnspecified
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/**
 * Neutral String input boundary. Each backend retains its own label, focus and motion design.
 *
 * Mochi 0.2 does not expose readOnly, minLines or arbitrary visual transformations. Those
 * requests explicitly use the MIUIX compatibility renderer, never silently drop behavior.
 * A disabled field can use native Mochi even when also marked readOnly: neither backend
 * allows focus, selection or keyboard input while disabled.
 * [selectAllOnFocus] is native on Mochi and bridges the official TextFieldValue overload on
 * MIUIX. [backgroundColor] is an explicit fill override; null retains each renderer's default.
 * TextFieldValue/state, custom selection, input/output transformations, layout callbacks and
 * interaction sources are intentionally not exposed until both implementations can honor them.
 */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    selectAllOnFocus: Boolean = false,
    backgroundColor: Color? = null,
) {
    require(minLines > 0 && maxLines >= minLines) { "TextField requires 1 <= minLines <= maxLines" }
    val nativeMochi = LocalMixUiBackend.current == MixUiBackend.Mochi &&
        supportsNativeMochiTextField(readOnly, minLines, visualTransformation, enabled)
    val canEdit by rememberUpdatedState(enabled && !readOnly)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val guardedOnValueChange: (String) -> Unit = { if (canEdit) currentOnValueChange(it) }
    var ownsFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(enabled) {
        // Do not clear another field's focus when an unfocused control becomes disabled.
        if (!enabled && ownsFocus) focusManager.clearFocus(force = true)
    }
    val fieldModifier = modifier.onFocusChanged { ownsFocus = it.hasFocus }
    val guardedLeading = guardedTextFieldSlot(enabled, leadingIcon)
    val guardedTrailing = guardedTextFieldSlot(enabled, trailingIcon)
    if (nativeMochi) {
        val nativeColors = if (LocalGlassFormControlContrast.current) {
            ui.feice.mochi.basic.MochiTextFieldDefaults.colors(
                placeholder = ui.feice.mochi.theme.MochiTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        } else {
            ui.feice.mochi.basic.MochiTextFieldDefaults.colors()
        }
        val defaultTextStyle = ui.feice.mochi.theme.MochiTheme.textStyles.body
        // Native Mochi derives its padding with fontSize.toDp() if lineHeight is
        // unspecified. A valid Compose colour/weight-only override has neither
        // metric, so resolve only that previously-crashing case against its native
        // typography. Keep already-resolved caller metrics and geometry unchanged.
        val nativeTextStyle = when {
            textStyle == null -> defaultTextStyle
            textStyle.fontSize.isUnspecified && textStyle.lineHeight.isUnspecified -> defaultTextStyle.merge(textStyle)
            else -> textStyle
        }
        ui.feice.mochi.basic.MochiTextField(
            value = value,
            onValueChange = guardedOnValueChange,
            modifier = fieldModifier,
            placeholder = label,
            // Preserve native material unless the caller explicitly overrides its fill.
            colors = if (backgroundColor == null) nativeColors else nativeColors.copy(container = backgroundColor),
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            selectAllOnFocus = selectAllOnFocus,
            secure = visualTransformation is PasswordVisualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = nativeTextStyle,
            leading = guardedLeading,
            trailing = guardedTrailing,
            // Retain Mochi's native clear control, secure eye, height and placeholder animations.
        )
    } else {
        val sheetFill = miuixSheetControlFill(enabled)
        val disabledSheet = !enabled && sheetFill != null
        val palette = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
        val fill = backgroundColor ?: sheetFill
        val nativeColors = top.yukonga.miuix.kmp.basic.TextFieldDefaults.textFieldColors()
        val colors = if (fill == null) nativeColors else nativeColors.copy(
            backgroundColor = fill,
            // Explicit fill remains authoritative; disabled foreground never inherits
            // an enabled custom text/label colour over that fill.
            labelColor = when {
                disabledSheet -> palette.disabledOnSurface
                backgroundColor == null && sheetFill != null -> palette.onSurfaceVariantSummary
                else -> nativeColors.labelColor
            },
        )
        val nativeTextStyle = textStyle ?: top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main
        val resolvedTextStyle = if (disabledSheet) nativeTextStyle.copy(color = palette.disabledOnSurface)
            else nativeTextStyle
        var editorValue by remember { mutableStateOf(TextFieldValue(value)) }
        // The native sheet can recompose with the previous String before the caller's
        // edit reaches it. Display the caller's text, but don't commit that temporary
        // mismatch: clamping against the shorter String would lose the new caret.
        val presentedValue = editorValue.withText(value)
        LaunchedEffect(value) {
            // An actual change of the caller's text accepts the IME value or replaces
            // it. A replacement cannot keep a composing range from another String.
            editorValue = editorValue.withText(value)
        }
        var lastDispatchedText = value
        var isFocused by remember { mutableStateOf(false) }
        LaunchedEffect(isFocused) {
            if (isFocused && selectAllOnFocus) {
                // Apply after the focus-causing tap's cursor update, not on edits.
                editorValue = editorValue.copy(selection = TextRange(0, editorValue.text.length))
            }
        }
        top.yukonga.miuix.kmp.basic.TextField(
            value = presentedValue,
            onValueChange = { next ->
                // Read-only inputs still allow selection/copy; disabled inputs do not.
                if (enabled) {
                    editorValue = next
                    if (next.text != lastDispatchedText) {
                        lastDispatchedText = next.text
                        guardedOnValueChange(next.text)
                    }
                }
            },
            modifier = fieldModifier.onFocusChanged { isFocused = it.isFocused },
            label = label,
            colors = colors,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = resolvedTextStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            leadingIcon = guardedLeading,
            trailingIcon = guardedTrailing,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
        )
    }
}

private fun TextFieldValue.withText(text: String): TextFieldValue =
    if (this.text == text) this else copy(text = text, composition = null)

/** Disabled input decorations must not retain independent click/accessibility edit actions. */
@Composable
private fun guardedTextFieldSlot(
    enabled: Boolean,
    content: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? {
    if (content == null || enabled) return content
    val disabledColor = if (LocalMixUiBackend.current == MixUiBackend.Miuix &&
        LocalFormPresentation.current == FormPresentation.Sheet)
        top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.disabledOnSurface else null
    return {
        Box(Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }) {
            if (disabledColor == null) content() else CompositionLocalProvider(
                top.yukonga.miuix.kmp.theme.LocalContentColor provides disabledColor,
            ) { content() }
        }
    }
}

internal fun supportsNativeMochiTextField(
    readOnly: Boolean,
    minLines: Int,
    visualTransformation: VisualTransformation,
    enabled: Boolean = true,
): Boolean = (!readOnly || !enabled) && minLines == 1 &&
    (visualTransformation === VisualTransformation.None ||
        (visualTransformation is PasswordVisualTransformation &&
            visualTransformation.filter(AnnotatedString("x")).text.text == "\u2022"))
