package hk.uwu.mixui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * SmallTopAppBar's public geometry/typography without its private clipToBounds.
 * The compact scene owns the progressive material; only the fixed controls live
 * here so native GlassIconButton shadows may draw below the 52dp layout bounds.
 * Keep measured height and the native 25dp vertical center unchanged.
 */
@Composable
internal fun UnclippedMiuixCompactBar(
    title: String,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    SubcomposeLayout(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(horizontalBarInsets())
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .height(TopAppBarDefaults.CollapsedHeight)
            .pointerInput(Unit) { detectTapGestures { /* Consume empty bar taps. */ } },
    ) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nav = subcompose("navigation") {
            Box(Modifier.padding(start = TopAppBarDefaults.NavigationIconPadding)) { navigationIcon() }
        }.single().measure(loose)
        val action = subcompose("actions") {
            Row(Modifier.padding(end = TopAppBarDefaults.ActionIconPadding),
                horizontalArrangement = Arrangement.spacedBy(MiuixTopBarActionSpacing, Alignment.End), verticalAlignment = Alignment.CenterVertically,
                content = actions)
        }.single().measure(loose.copy(maxWidth = (constraints.maxWidth - nav.width).coerceAtLeast(0)))
        val gap = 8.dp.roundToPx()
        val start = nav.width + gap
        val end = constraints.maxWidth - action.width - gap
        val available = (end - start).coerceAtLeast(0)
        val text = subcompose("title") {
            Text(title, color = MiuixTheme.colorScheme.onSurface,
                fontSize = MiuixTheme.textStyles.title3.fontSize,
                fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, softWrap = false)
        }.single().measure(loose.copy(maxWidth = available))
        val center = TopAppBarDefaults.SmallTopAppBarCenterHeight.roundToPx() / 2
        layout(constraints.maxWidth, constraints.maxHeight) {
            nav.placeRelative(0, center - nav.height / 2)
            action.placeRelative(constraints.maxWidth - action.width, center - action.height / 2)
            text.placeRelative(start + (available - text.width) / 2, center - text.height / 2)
        }
    }
}
