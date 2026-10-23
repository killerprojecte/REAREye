package hk.uwu.reareye.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.version.AppVersionViolation
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil

/** How long the gate stays on screen before it closes the app on its own. */
const val TARGET_APP_VERSION_DIALOG_TIMEOUT_MILLIS = 15_000L

/**
 * Blocking reminder shown when at least one target app is older than
 * [hk.uwu.reareye.version.RequiredAppVersions] allows.
 *
 * The dialog intentionally offers a single action: it is not dismissible by tapping outside or by
 * pressing back, and it closes the app after [autoCloseMillis] so a stale target app can never
 * leave the module half working.
 */
@Composable
fun TargetAppVersionDialog(
    violations: List<AppVersionViolation>,
    onClose: () -> Unit,
    autoCloseMillis: Long = TARGET_APP_VERSION_DIALOG_TIMEOUT_MILLIS,
) {
    val currentOnClose by rememberUpdatedState(onClose)
    var remainingMillis by remember(autoCloseMillis) { mutableLongStateOf(autoCloseMillis) }

    LaunchedEffect(autoCloseMillis) {
        val deadline = autoCloseMillis
        var elapsed = 0L
        while (elapsed < deadline) {
            delay(COUNTDOWN_TICK_MILLIS)
            elapsed = (elapsed + COUNTDOWN_TICK_MILLIS).coerceAtMost(deadline)
            remainingMillis = deadline - elapsed
        }
        currentOnClose()
    }

    val remainingSeconds = ceil(remainingMillis / 1000.0).toInt().coerceAtLeast(0)

    OverlayDialog(
        show = true,
        title = stringResource(R.string.target_app_version_dialog_title),
        summary = stringResource(R.string.target_app_version_dialog_summary),
        // Only the button below may close this dialog.
        onDismissRequest = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            violations.forEach { violation ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = violation.label,
                        fontWeight = FontWeight.SemiBold,
                    )
                    VersionDetailRow(
                        label = stringResource(R.string.target_app_version_dialog_current),
                        value = violation.currentVersionText,
                        emphasize = true,
                    )
                    VersionDetailRow(
                        label = stringResource(R.string.target_app_version_dialog_required),
                        value = violation.requiredVersionText,
                        emphasize = false,
                    )
                    Text(
                        text = violation.packageName,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.target_app_version_dialog_countdown,
                    remainingSeconds,
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Button(
                onClick = { currentOnClose() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = stringResource(R.string.target_app_version_dialog_close))
            }
        }
    }
}

@Composable
private fun VersionDetailRow(
    label: String,
    value: String,
    emphasize: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(112.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            color = if (emphasize) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
    }
}

private const val COUNTDOWN_TICK_MILLIS = 250L
