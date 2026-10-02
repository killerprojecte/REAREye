package hk.uwu.reareye.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.ModuleActivationState
import hk.uwu.reareye.hook.support.XposedModuleStatus
import hk.uwu.reareye.utils.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class PermissionSnapshot(
    val lsp: ModuleActivationState,
    val root: Boolean?,
)

@Composable
fun OnboardingScreen(
    onFinished: (startFeatureGuide: Boolean) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    var permissionSnapshot by remember {
        mutableStateOf(PermissionSnapshot(XposedModuleStatus.current(), null))
    }
    val pagerState = rememberPagerState(initialPage = page, pageCount = { 3 })
    val scope = rememberCoroutineScope()

    LaunchedEffect(page) {
        if (pagerState.currentPage != page) {
            pagerState.animateScrollToPage(page)
        }
    }

    BackHandler(enabled = page > 0) {
        if (!pagerState.isScrollInProgress) page -= 1
    }

    fun refreshPermissions() {
        XposedModuleStatus.refresh()
        scope.launch {
            val root = withContext(Dispatchers.IO) { RootHelper.hasRootAccess() }
            permissionSnapshot = permissionSnapshot.copy(
                lsp = XposedModuleStatus.current(),
                root = root,
            )
        }
    }

    DisposableEffect(Unit) {
        val observer: (ModuleActivationState) -> Unit = { state ->
            scope.launch { permissionSnapshot = permissionSnapshot.copy(lsp = state) }
        }
        XposedModuleStatus.observe(observer)
        onDispose { XposedModuleStatus.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshPermissions()
            delay(2_000)
        }
    }

    val lspScopeReady = permissionSnapshot.lsp == ModuleActivationState.ACTIVE ||
            permissionSnapshot.lsp == ModuleActivationState.NO_RUNNING_TARGET
    val pageSettled = !pagerState.isScrollInProgress && pagerState.currentPage == page
    val canContinue =
        pageSettled && (page != 2 || (lspScopeReady && permissionSnapshot.root == true))
    val accent = MiuixTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(28.dp))

            Spacer(modifier = Modifier.height(10.dp))

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                beyondViewportPageCount = 0,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer { clip = true },
            ) { index ->
                OobePage(
                    page = index,
                    permissionSnapshot = permissionSnapshot,
                    onRefreshPermissions = ::refreshPermissions,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (index == page) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (index == page) accent else accent.copy(alpha = 0.22f)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (page == 0) {
                    Button(
                        onClick = { onFinished(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.oobe_skip))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (page > 0) {
                        Button(
                            onClick = { page -= 1 },
                            enabled = pageSettled,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.oobe_previous)) }
                    }
                    Button(
                        onClick = { if (page == 2) onFinished(true) else page += 1 },
                        enabled = canContinue,
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(if (page == 2) R.string.oobe_continue else R.string.oobe_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun OobePage(
    page: Int,
    permissionSnapshot: PermissionSnapshot,
    onRefreshPermissions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (page) {
            0 -> {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .size(216.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = stringResource(R.string.app_name),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(216.dp)
                            .clip(CircleShape),
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.oobe_welcome_title),
                    style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.oobe_welcome_summary),
                    style = MiuixTheme.textStyles.body1,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            1 -> {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    stringResource(R.string.oobe_disclaimer_title),
                    style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    stringResource(R.string.oobe_disclaimer_summary),
                    style = MiuixTheme.textStyles.body1,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(24.dp))
                DisclaimerCard()
            }

            else -> {
                Icon(
                    Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    stringResource(R.string.oobe_permission_title),
                    style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    stringResource(R.string.oobe_permission_summary),
                    style = MiuixTheme.textStyles.body1,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(24.dp))
                PermissionCard(permissionSnapshot, onRefreshPermissions)
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    stringResource(R.string.oobe_disclaimer_heading),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                stringResource(R.string.oobe_disclaimer_point_one),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                stringResource(R.string.oobe_disclaimer_point_two),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                stringResource(R.string.oobe_disclaimer_point_three),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun PermissionCard(snapshot: PermissionSnapshot, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PermissionRow(
                title = stringResource(R.string.oobe_lsp_title),
                summary = when (snapshot.lsp) {
                    ModuleActivationState.ACTIVE -> stringResource(R.string.oobe_lsp_active)
                    ModuleActivationState.NO_RUNNING_TARGET -> stringResource(R.string.oobe_lsp_scope_ready)
                    ModuleActivationState.SCOPE_NOT_AUTHORIZED -> stringResource(R.string.oobe_lsp_missing_scope)
                    ModuleActivationState.SERVICE_UNAVAILABLE -> stringResource(R.string.oobe_lsp_unavailable)
                },
                ready = snapshot.lsp == ModuleActivationState.ACTIVE || snapshot.lsp == ModuleActivationState.NO_RUNNING_TARGET,
            )
            PermissionRow(
                title = stringResource(R.string.oobe_root_title),
                summary = when (snapshot.root) {
                    true -> stringResource(R.string.oobe_root_ready)
                    false -> stringResource(R.string.oobe_root_missing)
                    null -> stringResource(R.string.oobe_checking)
                },
                ready = snapshot.root == true,
            )
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.oobe_refresh))
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, summary: String, ready: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
            contentDescription = null,
            tint = if (ready) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
