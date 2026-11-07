package hk.uwu.mixui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hk.uwu.mixui.LocalMixUiRenderer

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    LocalMixUiRenderer.current.Switch(checked, onCheckedChange, modifier, enabled)

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    LocalMixUiRenderer.current.Button(onClick, modifier, enabled, content)
