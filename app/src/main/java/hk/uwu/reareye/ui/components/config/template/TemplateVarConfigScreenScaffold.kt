package hk.uwu.reareye.ui.components.config.template

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun <TSchema, TConfig> TemplateVarConfigScreenScaffold(
    modifier: Modifier = Modifier,
    title: String,
    embedded: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    loading: Boolean,
    schema: TSchema?,
    config: TConfig?,
    hasEditableItems: Boolean,
    loadingText: String,
    unavailableText: String,
    confirmText: String,
    resetText: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    editorItems: LazyListScope.(TSchema, TConfig) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val contentSourceModifier = if (embedded) {
        modifier
    } else {
        modifier.rearAcrylicSource(hazeState)
    }

    BackHandler(onBack = onBack)

    val screenContent: @Composable (PaddingValues) -> Unit = { paddingValues ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(contentSourceModifier)
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfiniteProgressIndicator()
                        Text(text = loadingText)
                    }
                }
            }

            schema == null || config == null || !hasEditableItems -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(contentSourceModifier)
                        .padding(paddingValues)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = unavailableText,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(contentSourceModifier)
                        .imePadding()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding() + 12.dp,
                        bottom = paddingValues.calculateBottomPadding() + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    overscrollEffect = null,
                ) {
                    editorItems(schema, config)
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onConfirm,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text(text = confirmText)
                            }
                            Button(
                                onClick = onReset,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    color = MiuixTheme.colorScheme.error,
                                    contentColor = MiuixTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Text(text = resetText)
                            }
                        }
                    }
                }
            }
        }
    }

    if (embedded) {
        screenContent(contentPadding)
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                    color = Color.Transparent,
                    title = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                )
            },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.systemBars,
            content = screenContent,
        )
    }
}
