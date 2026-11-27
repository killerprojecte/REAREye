package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import kotlinx.coroutines.CompletableDeferred

/**
 * Editor/form field with a native inline label in MIUIX modals, an external label elsewhere.
 *
 * MIUIX keeps its native focus border, padding and touch target. Sheet fields use an opaque
 * content fill; dialogs still let the panel show through. Mochi keeps its native material.
 * [modifier] belongs to the actual
 * editable field, not the label's layout, preserving focus and test-tag semantics.
 *
 * [autoFocus] requests focus once per [focusSessionKey] (or false-to-true activation),
 * after placement and the following frame. A host can invalidate an exiting session by
 * setting [autoFocus] to false; text changes never restart or reassert this request.
 */
@Composable
fun EditorTextField(
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
    autoFocus: Boolean = false,
    focusSessionKey: Any? = Unit,
    selectAllOnFocus: Boolean = false,
    backgroundColor: Color? = null,
) {
    val inlineLabel = LocalMixUiBackend.current == MixUiBackend.Miuix &&
        LocalFormPresentation.current != FormPresentation.Page
    val focusRequester = remember { FocusRequester() }
    val placed = remember { CompletableDeferred<Unit>() }
    val currentAutoFocus by rememberUpdatedState(autoFocus)
    val currentCanEdit by rememberUpdatedState(enabled && !readOnly)
    val currentSessionKey by rememberUpdatedState(focusSessionKey)
    val currentKeyboard by rememberUpdatedState(LocalSoftwareKeyboardController.current)
    val nativeMochi = LocalMixUiBackend.current == MixUiBackend.Mochi &&
        supportsNativeMochiTextField(readOnly, minLines, visualTransformation, enabled)

    LaunchedEffect(autoFocus, focusSessionKey) {
        if (!autoFocus) return@LaunchedEffect
        placed.await()
        // Dialog hosts clear page/modal focus in their initial effects. Waiting for
        // placement plus the next frame lets those effects finish before this request.
        // There is no timer, value dependency, polling or repeated focus stealing.
        withFrameNanos { }
        if (currentAutoFocus && currentCanEdit && currentSessionKey == focusSessionKey) {
            if (focusRequester.requestFocus()) currentKeyboard?.show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotEmpty() && !inlineLabel) {
            Text(
                text = label,
                modifier = Modifier.padding(start = 4.dp),
                color = if (enabled || LocalFormPresentation.current != FormPresentation.Sheet)
                    MixTheme.colorScheme.onSurface
                else if (LocalMixUiBackend.current == MixUiBackend.Mochi)
                    MixTheme.colorScheme.onSurface.let { it.copy(alpha = it.alpha * ui.feice.mochi.theme.MochiDefaults.DisabledAlpha) }
                else MixTheme.colorScheme.disabledOnSurface,
                style = MixTheme.textStyles.footnote1,
            )
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .focusRequester(focusRequester)
                .onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0) {
                        placed.complete(Unit)
                    }
                },
            label = if (inlineLabel) label else "",
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
            selectAllOnFocus = selectAllOnFocus,
            backgroundColor = backgroundColor ?: if (nativeMochi ||
                (inlineLabel && LocalFormPresentation.current == FormPresentation.Sheet)) null else Color.Transparent,
        )
    }
}
