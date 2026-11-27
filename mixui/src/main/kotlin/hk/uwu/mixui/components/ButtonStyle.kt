package hk.uwu.mixui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.theme.MixTheme

@Immutable
data class ButtonColors(val color: Color, val disabledColor: Color, val contentColor: Color, val disabledContentColor: Color)

object ButtonDefaults {
    val CornerRadius = 16.dp
    val MinWidth = 58.dp
    val MinHeight = 40.dp
    val InsideMargin = PaddingValues(horizontal = 16.dp, vertical = 13.dp)

    @Composable
    fun buttonColors(
        color: Color = MixTheme.colorScheme.secondaryVariant,
        disabledColor: Color = MixTheme.colorScheme.disabledSecondaryVariant,
        contentColor: Color = MixTheme.colorScheme.onSecondaryVariant,
        disabledContentColor: Color = MixTheme.colorScheme.disabledOnSecondaryVariant,
    ) = ButtonColors(color, disabledColor, contentColor, disabledContentColor)

    @Composable
    fun buttonColorsPrimary(
        color: Color = MixTheme.colorScheme.primary,
        disabledColor: Color = MixTheme.colorScheme.disabledPrimaryButton,
        contentColor: Color = MixTheme.colorScheme.onPrimary,
        disabledContentColor: Color = MixTheme.colorScheme.disabledOnPrimaryButton,
    ) = ButtonColors(color, disabledColor, contentColor, disabledContentColor)

    @Composable
    fun buttonColorsDanger(
        color: Color = MixTheme.colorScheme.error,
        disabledColor: Color = MixTheme.colorScheme.error.copy(alpha = 0.36f),
        contentColor: Color = MixTheme.colorScheme.onError,
        disabledContentColor: Color = MixTheme.colorScheme.onError.copy(alpha = 0.6f),
    ) = ButtonColors(color, disabledColor, contentColor, disabledContentColor)
}
