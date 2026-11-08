package hk.uwu.mixui.backend

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import hk.uwu.mixui.components.ButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hk.uwu.mixui.motion.mixPressFeedback

/** Internal adapter contract: public APIs never expose vendor-specific types. */
internal interface MixUiRenderer {
    @Composable fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean)
    @Composable fun Button(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, cornerRadius: Dp, minWidth: Dp, minHeight: Dp, colors: ButtonColors, insideMargin: PaddingValues, content: @Composable RowScope.() -> Unit)
}

internal object MiuixBackend : MixUiRenderer {
    @Composable override fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) =
        top.yukonga.miuix.kmp.basic.Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    @Composable override fun Button(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, cornerRadius: Dp, minWidth: Dp, minHeight: Dp, colors: ButtonColors, insideMargin: PaddingValues, content: @Composable RowScope.() -> Unit) =
        top.yukonga.miuix.kmp.basic.Button(onClick = onClick, modifier = modifier.mixPressFeedback(enabled), enabled = enabled, cornerRadius = cornerRadius, minWidth = minWidth, minHeight = minHeight,
            colors = top.yukonga.miuix.kmp.basic.ButtonColors(colors.color, colors.disabledColor, colors.contentColor, colors.disabledContentColor),
            insideMargin = insideMargin, content = content)
}

internal object MochiBackend : MixUiRenderer {
    @Composable override fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) =
        ui.feice.mochi.basic.MochiSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    @Composable override fun Button(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, cornerRadius: Dp, minWidth: Dp, minHeight: Dp, colors: ButtonColors, insideMargin: PaddingValues, content: @Composable RowScope.() -> Unit) {
        val vendorColors = ui.feice.mochi.basic.MochiButtonDefaults.colors().copy(
            container = colors.color, disabledContainer = colors.disabledColor,
            content = colors.contentColor, disabledContent = colors.disabledContentColor,
        )
        // Legacy children still read MIUIX's content color during staged migration.
        CompositionLocalProvider(top.yukonga.miuix.kmp.theme.LocalContentColor provides
            if (enabled) colors.contentColor else colors.disabledContentColor) {
            ui.feice.mochi.basic.MochiButton(onClick = onClick,
                modifier = modifier.defaultMinSize(minWidth = minWidth), enabled = enabled,
                cornerRadius = cornerRadius, minHeight = minHeight, colors = vendorColors,
                contentPadding = insideMargin, content = content)
        }
    }
}
