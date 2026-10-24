package hk.uwu.reareye.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val MARY_TOOL_MOTION_MILLIS = 220

internal fun maryToolExpand() = expandVertically(
    animationSpec = tween(MARY_TOOL_MOTION_MILLIS, easing = FastOutSlowInEasing),
    expandFrom = Alignment.Top,
) + fadeIn(tween(MARY_TOOL_MOTION_MILLIS, easing = FastOutSlowInEasing))

internal fun maryToolCollapse() = shrinkVertically(
    animationSpec = tween(MARY_TOOL_MOTION_MILLIS, easing = FastOutSlowInEasing),
    shrinkTowards = Alignment.Top,
) + fadeOut(tween(MARY_TOOL_MOTION_MILLIS, easing = FastOutSlowInEasing))

/** Fixed compact confirmation frame. Content and trailing actions are reusable slots. */
@Composable
internal fun MaryConfirmationCard(
    key: String,
    category: String,
    icon: ImageVector,
    expanded: Boolean,
    status: String,
    toggleLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    isError: Boolean = false,
    isDanger: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surfaceContainerHigh.luminance() < 0.5f
    val errorColors = maryErrorColors(dark)
    val container =
        if (isError) errorColors.container else if (dark) Color(0xff2c2c2c) else scheme.surfaceContainerHigh
    val accent = if (isError || isDanger) errorColors.content else scheme.primary
    val secondary = if (isError) errorColors.content else scheme.onSurfaceVariantSummary
    val border =
        if (isError) errorColors.border else if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(
            alpha = 0.10f
        )
    val shape = RoundedCornerShape(20.dp)
    // The summary and body must trade their heights on the same clock. Removing the
    // summary with `if` first caused a one-frame shrink before the body started expanding.
    val transition = updateTransition(expanded, label = "confirmation-expansion")
    val rotation by transition.animateFloat(
        transitionSpec = { tween(MARY_TOOL_MOTION_MILLIS, easing = FastOutSlowInEasing) },
        label = "confirmation-arrow",
    ) { if (it) 180f else 0f }
    Column(
        modifier
            .fillMaxWidth()
            .testTag("mary-confirmation-$key")
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle
                )
                .semantics { stateDescription = status }
                .testTag("mary-tool-header-$key")
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .size(15.dp)
                        .testTag("mary-confirmation-icon-$key")
                )
                Text(category, modifier = Modifier.weight(1f), fontSize = 12.sp, color = accent)
                Icon(
                    Icons.Outlined.KeyboardArrowDown, contentDescription = null,
                    tint = secondary, modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation)
                )
            }
            transition.AnimatedVisibility(
                visible = { !it }, enter = maryToolExpand(), exit = maryToolCollapse(),
            ) {
                Text(summary?.takeIf { it.isNotBlank() } ?: status,
                    fontSize = 12.sp, color = secondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        transition.AnimatedVisibility(
            visible = { it }, enter = maryToolExpand(), exit = maryToolCollapse(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
                if (actions != null) MaryConfirmationActions(key, content = actions)
            }
        }
    }
}

@Composable
internal fun MaryConfirmationActions(
    key: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .testTag("mary-confirmation-actions-$key"),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

/** A 29dp visual pill inside a minimum 40dp click target, shared by every confirmation. */
@Composable
internal fun MaryConfirmationActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surfaceContainerHigh.luminance() < 0.5f
    val foreground = when {
        !enabled -> scheme.onSurfaceVariantSummary.copy(alpha = 0.55f)
        primary && dark -> Color(0xff202020)
        primary -> Color.White
        else -> scheme.onSurfaceContainer
    }
    val fill = when {
        !enabled -> scheme.onSurfaceVariantSummary.copy(alpha = 0.16f)
        primary && dark -> Color.White
        primary -> Color(0xff262626)
        else -> if (dark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.025f)
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .heightIn(min = 29.dp)
                .clip(shape)
                .background(fill)
                .then(
                    if (!primary && enabled) Modifier.border(
                        1.dp,
                        if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.14f),
                        shape
                    ) else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 4.dp), contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = foreground
            )
        }
    }
}
