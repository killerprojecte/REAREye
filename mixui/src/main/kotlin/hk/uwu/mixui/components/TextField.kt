package hk.uwu.mixui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/**
 * Neutral String input boundary. Each backend retains its own label, focus and motion design.
 *
 * Mochi 0.2 does not expose readOnly, minLines or arbitrary visual transformations. Those
 * requests explicitly use the MIUIX compatibility renderer, never silently drop behavior.
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
) {
    require(minLines > 0 && maxLines >= minLines) { "TextField requires 1 <= minLines <= maxLines" }
    val nativeMochi = LocalMixUiBackend.current == MixUiBackend.Mochi &&
        supportsNativeMochiTextField(readOnly, minLines, visualTransformation)
    val form = LocalGlassFormStyle.current
    var focused by remember { mutableStateOf(false) }
    val fieldModifier = if (form == null) modifier else {
        val radius = if (nativeMochi) ui.feice.mochi.basic.MochiTextFieldDefaults.Height / 2
            else top.yukonga.miuix.kmp.basic.TextFieldDefaults.CornerRadius
        // Preserve MIUIX's native primary focus border; Mochi retains its native cursor feedback.
        val focusModifier = modifier.onFocusChanged { focused = it.hasFocus }
        if (focused && !nativeMochi) focusModifier else
            focusModifier.glassFormOutline(form.outline.copy(alpha = if (enabled) 1f else 0.38f), radius, mochi = nativeMochi)
    }
    if (nativeMochi) {
        ui.feice.mochi.basic.MochiTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier,
            placeholder = label,
            colors = if (form == null) ui.feice.mochi.basic.MochiTextFieldDefaults.colors()
                else ui.feice.mochi.basic.MochiTextFieldDefaults.colors(
                    container = form.fill, content = form.content, placeholder = form.label,
                ),
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            secure = visualTransformation is PasswordVisualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = textStyle ?: ui.feice.mochi.theme.MochiTheme.textStyles.body,
            leading = leadingIcon,
            trailing = trailingIcon,
            // Retain Mochi's native clear control, secure eye, height and placeholder animations.
        )
    } else {
        top.yukonga.miuix.kmp.basic.TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier,
            label = label,
            colors = if (form == null) top.yukonga.miuix.kmp.basic.TextFieldDefaults.textFieldColors()
                else top.yukonga.miuix.kmp.basic.TextFieldDefaults.textFieldColors(
                    backgroundColor = form.fill, labelColor = form.label,
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle ?: top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main.let {
                if (form == null) it else it.copy(color = form.content)
            },
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
        )
    }
}

internal fun supportsNativeMochiTextField(
    readOnly: Boolean,
    minLines: Int,
    visualTransformation: VisualTransformation,
): Boolean = !readOnly && minLines == 1 &&
    (visualTransformation === VisualTransformation.None ||
        (visualTransformation is PasswordVisualTransformation &&
            visualTransformation.filter(AnnotatedString("x")).text.text == "\u2022"))
