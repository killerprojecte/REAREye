package hk.uwu.reareye.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.Surface
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.icons.Icons as UiIcons
import hk.uwu.mixui.theme.MixTheme

/** The compact asset action shared by Rear Store and preset-resource releases. */
@Composable
internal fun StoreDownloadBadge(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector = UiIcons.Download,
) {
    Surface(
        modifier = modifier.alpha(if (enabled) 1f else 0.38f),
        color = MixTheme.colorScheme.primary.copy(alpha = 0.18f),
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MixTheme.colorScheme.primary, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(icon, contentDescription = null, tint = MixTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp))
        }
    }
}

/** Rear Store's 16dp progress ring; a preset transfer may additionally offer pause. */
@Composable
internal fun StoreProgressChip(
    text: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier.alpha(if (enabled) 1f else 0.38f),
        color = MixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (progress != null) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(16.dp),
                    strokeWidth = 2.2.dp, color = MixTheme.colorScheme.primary,
                    trackColor = MixTheme.colorScheme.primary.copy(alpha = 0.16f))
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), strokeWidth = 2.2.dp,
                    color = MixTheme.colorScheme.primary,
                    trackColor = MixTheme.colorScheme.primary.copy(alpha = 0.16f))
            }
            Text(text, color = MixTheme.colorScheme.primary, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onClick != null) Icon(Icons.Outlined.Pause, contentDescription = null,
                tint = MixTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        }
    }
}
