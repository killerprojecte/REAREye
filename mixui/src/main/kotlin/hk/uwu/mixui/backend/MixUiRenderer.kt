package hk.uwu.mixui.backend

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hk.uwu.mixui.motion.mixPressFeedback

/** Internal adapter contract: public APIs never expose vendor-specific types. */
internal interface MixUiRenderer {
    @Composable fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean)
    @Composable fun Button(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable RowScope.() -> Unit)
}

internal object MiuixBackend : MixUiRenderer {
    @Composable override fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) =
        top.yukonga.miuix.kmp.basic.Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    @Composable override fun Button(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable RowScope.() -> Unit) =
        top.yukonga.miuix.kmp.basic.Button(onClick = onClick, modifier = modifier.mixPressFeedback(enabled), enabled = enabled, content = content)
}

internal object MochiBackend : MixUiRenderer {
    @Composable override fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) =
        ui.feice.mochi.basic.MochiSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    @Composable override fun Button(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable RowScope.() -> Unit) =
        ui.feice.mochi.basic.MochiButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
}
