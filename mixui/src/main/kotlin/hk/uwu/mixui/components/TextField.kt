package hk.uwu.mixui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
    if (nativeMochi) {
        ui.feice.mochi.basic.MochiTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            placeholder = label,
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
            modifier = modifier,
            label = label,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle ?: top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main,
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
