package hk.uwu.reareye.ui.components

import hk.uwu.mixui.components.GlassOverlayDialog
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.utils.SubscreenNotificationAccess
import hk.uwu.reareye.utils.SubscreenNotificationAccessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.ButtonDefaults
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.OverlayDialogDefaults
import hk.uwu.mixui.theme.MixTheme

@Composable
internal fun SubscreenNotificationAccessPrompt(enabled: Boolean) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val access = remember { SubscreenNotificationAccess() }
    // Match this app's Android user rather than silently granting access for owner user 0.
    val userId = remember { Process.myUid() / 100000 }
    val scope = rememberCoroutineScope()
    var checked by rememberSaveable { mutableStateOf(false) }
    var show by rememberSaveable { mutableStateOf(false) }
    var granting by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled || checked) return@LaunchedEffect
        val state = withContext(Dispatchers.IO) {
            access.check(userId, listenerAvailable(applicationContext))
        }
        checked = true
        show = state == SubscreenNotificationAccessState.MISSING
    }

    SubscreenNotificationAccessDialog(
        show = enabled && show,
        granting = granting,
        error = error,
        onGrant = {
            if (!granting) {
                granting = true
                error = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            access.grant(userId, listenerAvailable(applicationContext))
                        }
                        if (result.granted) {
                            show = false
                            Toast.makeText(
                                applicationContext,
                                R.string.subscreen_notification_access_success,
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            error = applicationContext.getString(
                                R.string.subscreen_notification_access_failed,
                            ) + result.detail.takeIf { it.isNotBlank() }
                                ?.let { "\n$it" }.orEmpty()
                        }
                    } finally {
                        granting = false
                    }
                }
            }
        },
        // Grant starts synchronously. Guard again if Back/Later arrives before recomposition.
        onDismiss = { if (!granting) show = false },
    )
}

/**
 * Presentation only: tests and previews supply inert callbacks, never permission services.
 * The owning prompt retains all checking/granting state and side effects.
 */
@Composable
internal fun SubscreenNotificationAccessDialog(
    show: Boolean,
    granting: Boolean,
    error: String?,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassOverlayDialog(
        show = show,
        title = stringResource(R.string.subscreen_notification_access_title),
        onDismissRequest = if (granting) null else onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                stringResource(R.string.subscreen_notification_access_summary)
                    .lineSequence().forEach { sentence ->
                        Text(
                            text = sentence,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = MixTheme.textStyles.body1.fontSize,
                            textAlign = TextAlign.Start,
                            color = OverlayDialogDefaults.summaryColor(),
                        )
                    }
            }
            error?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    color = MixTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onGrant,
                enabled = !granting,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (granting) R.string.subscreen_notification_access_granting
                        else R.string.subscreen_notification_access_confirm,
                    ),
                )
            }
            Button(
                onClick = onDismiss,
                enabled = !granting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.subscreen_notification_access_later))
            }
        }
    }
}

private fun listenerAvailable(context: Context): Boolean = runCatching {
    val info = context.packageManager.getServiceInfo(
        ComponentName(SubscreenNotificationAccess.PACKAGE, SubscreenNotificationAccess.SERVICE),
        0,
    )
    info.enabled && info.applicationInfo.enabled && info.exported &&
            info.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
}.getOrDefault(false)
