package hk.uwu.reareye.ui.components.config.template

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import hk.uwu.reareye.R
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.ButtonDefaults
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.InfiniteProgressIndicator
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.pageScrollFeedback
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.TopBarIconButton
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.theme.MixTheme

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
    val listState = rememberLazyListState()
    val direction = LocalLayoutDirection.current

    BackHandler(onBack = onBack)

    val screenContent: @Composable (PaddingValues) -> Unit = { paddingValues ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(modifier)
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
                        .then(modifier)
                        .padding(paddingValues)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = unavailableText,
                        color = MixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(modifier)
                        .imePadding()
                        .pageScrollFeedback()
                        .padding(
                            start = paddingValues.calculateStartPadding(direction) + 12.dp,
                            end = paddingValues.calculateEndPadding(direction) + 12.dp,
                        ),
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
                                    color = MixTheme.colorScheme.error,
                                    contentColor = MixTheme.colorScheme.onPrimary,
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
        // No independent bar/nested-scroll consumer in an embedded editor.
        GlassPreferenceScene { screenContent(contentPadding) }
    } else {
        GlassTopBarScaffold(
            title = title,
            isContentScrolled = listState.canScrollBackward,
            contentListState = listState,
            navigationIcon = {
                TopBarIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.scene_route_navigate_back))
                }
            },
            content = { hostPadding ->
                screenContent(PaddingValues(
                    start = maxOf(hostPadding.calculateStartPadding(direction), contentPadding.calculateStartPadding(direction)),
                    top = maxOf(hostPadding.calculateTopPadding(), contentPadding.calculateTopPadding()),
                    end = maxOf(hostPadding.calculateEndPadding(direction), contentPadding.calculateEndPadding(direction)),
                    bottom = maxOf(hostPadding.calculateBottomPadding(), contentPadding.calculateBottomPadding()),
                ))
            },
        )
    }
}
