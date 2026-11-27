package hk.uwu.mixui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import hk.uwu.mixui.theme.MixTheme

@Composable
fun PopupHost() = top.yukonga.miuix.kmp.utils.MiuixPopupUtils.MiuixPopupHost()

enum class FabPosition { Start, Center, End, EndOverlay }
enum class ToolbarPosition { TopStart, CenterStart, BottomStart, TopEnd, CenterEnd, BottomEnd, TopCenter, BottomCenter }

/**
 * Shared slot/inset and overlay lifetime contract. The internal layout host is
 * common to both renderers; its slots and neutral colors select the visible style.
 */
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    snackbarHost: @Composable () -> Unit = {},
    popupHost: @Composable () -> Unit = { PopupHost() },
    containerColor: Color = MixTheme.colorScheme.surface,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) = top.yukonga.miuix.kmp.basic.Scaffold(
    modifier, topBar, bottomBar, floatingActionButton,
    when (floatingActionButtonPosition) {
        FabPosition.Start -> top.yukonga.miuix.kmp.basic.FabPosition.Start
        FabPosition.Center -> top.yukonga.miuix.kmp.basic.FabPosition.Center
        FabPosition.End -> top.yukonga.miuix.kmp.basic.FabPosition.End
        FabPosition.EndOverlay -> top.yukonga.miuix.kmp.basic.FabPosition.EndOverlay
    },
    floatingToolbar,
    when (floatingToolbarPosition) {
        ToolbarPosition.TopStart -> top.yukonga.miuix.kmp.basic.ToolbarPosition.TopStart
        ToolbarPosition.CenterStart -> top.yukonga.miuix.kmp.basic.ToolbarPosition.CenterStart
        ToolbarPosition.BottomStart -> top.yukonga.miuix.kmp.basic.ToolbarPosition.BottomStart
        ToolbarPosition.TopEnd -> top.yukonga.miuix.kmp.basic.ToolbarPosition.TopEnd
        ToolbarPosition.CenterEnd -> top.yukonga.miuix.kmp.basic.ToolbarPosition.CenterEnd
        ToolbarPosition.BottomEnd -> top.yukonga.miuix.kmp.basic.ToolbarPosition.BottomEnd
        ToolbarPosition.TopCenter -> top.yukonga.miuix.kmp.basic.ToolbarPosition.TopCenter
        ToolbarPosition.BottomCenter -> top.yukonga.miuix.kmp.basic.ToolbarPosition.BottomCenter
    },
    snackbarHost, popupHost, containerColor, contentWindowInsets, content,
)
