package hk.uwu.reareye.ui.components.card

import android.annotation.SuppressLint
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.ui.components.RearBadgeGroup
import hk.uwu.reareye.ui.components.RearBadgeItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModuleStyleManagerCard(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 12.dp,
    title: String,
    summaryLines: List<String>,
    badges: List<RearBadgeItem> = emptyList(),
    detailsBelowHeader: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    headerVerticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    showActions: Boolean = true,
    scrollActionsHorizontally: Boolean = false,
    backgroundColor: Color? = null,
    contentColor: Color? = null,
    titleColor: Color = contentColor ?: MiuixTheme.colorScheme.onSurface,
    summaryColor: Color = contentColor ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
    dividerColor: Color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    onCardClick: (() -> Unit)? = null,
    leftAction: @Composable () -> Unit,
    rightAction: @Composable () -> Unit,
) {
    // Keep the inter-card gap outside the shadowed surface while dragging.
    val cardModifier = Modifier
        .padding(bottom = bottomPadding)
        .then(modifier)
    val actionScrollState = rememberScrollState()

    val headerContent: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = headerVerticalAlignment,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight(550),
                        color = titleColor,
                    )
                    if (!detailsBelowHeader) {
                        if (badges.isNotEmpty()) {
                            RearBadgeGroup(
                                badges = badges,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        summaryLines.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 12.sp,
                                color = summaryColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                trailing?.invoke()
            }

            if (detailsBelowHeader) {
                if (badges.isNotEmpty()) {
                    RearBadgeGroup(badges = badges)
                }
                summaryLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        color = summaryColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    val actionsContent: @Composable () -> Unit = {
        if (scrollActionsHorizontally) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(actionScrollState),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leftAction()
                rightAction()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leftAction()
                Spacer(Modifier.weight(1f))
                rightAction()
            }
        }
    }

    if (onCardClick != null) {
        Card(
            modifier = cardModifier,
            insideMargin = PaddingValues(16.dp),
            colors = backgroundColor?.let {
                CardDefaults.defaultColors(
                    color = it,
                    contentColor = contentColor ?: MiuixTheme.colorScheme.onSurface,
                )
            } ?: CardDefaults.defaultColors(),
            onClick = onCardClick,
        ) {
            headerContent()

            if (showActions) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    thickness = 0.5.dp,
                    color = dividerColor,
                )

                actionsContent()
            }
        }
    } else {
        Card(
            modifier = cardModifier,
            insideMargin = PaddingValues(16.dp),
            colors = backgroundColor?.let {
                CardDefaults.defaultColors(
                    color = it,
                    contentColor = contentColor ?: MiuixTheme.colorScheme.onSurface,
                )
            } ?: CardDefaults.defaultColors(),
        ) {
            headerContent()

            if (showActions) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    thickness = 0.5.dp,
                    color = dividerColor,
                )

                actionsContent()
            }
        }
    }
}

@Composable
fun ModuleStyleIconAction(
    @SuppressLint("ModifierParameter")
    modifier: Modifier = Modifier.size(20.dp),
    icon: ImageVector,
    contentDescription: String? = null,
    backgroundColor: Color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
    contentColor: Color? = null,
    onClick: () -> Unit,
) {
    val actionIconAlpha = if (backgroundColor.luminance() < 0.5f) 0.7f else 0.9f
    val actionIconTint =
        (contentColor ?: MiuixTheme.colorScheme.onSurface).copy(alpha = actionIconAlpha)
    IconButton(
        minHeight = 35.dp,
        minWidth = 35.dp,
        onClick = onClick,
        backgroundColor = backgroundColor,
    ) {
        Icon(
            imageVector = icon,
            tint = actionIconTint,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}

@Composable
fun ModuleStyleTextAction(
    icon: ImageVector,
    text: String,
    enabled: Boolean = true,
    backgroundColor: Color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val actionIconAlpha = if (backgroundColor.luminance() < 0.5f) 0.7f else 0.9f
    val actionIconTint =
        contentColor.copy(alpha = actionIconAlpha * if (enabled) 1f else 0.45f)
    IconButton(
        modifier = modifier,
        minHeight = 35.dp,
        minWidth = 35.dp,
        onClick = if (enabled) onClick else ({}),
        backgroundColor = backgroundColor.copy(alpha = if (enabled) backgroundColor.alpha else 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                tint = actionIconTint,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                modifier = Modifier.padding(start = 4.dp, end = 3.dp),
                text = text,
                color = actionIconTint,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
fun ModuleStyleDeleteAction(
    icon: ImageVector,
    text: String,
    enabled: Boolean = true,
    backgroundColor: Color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    ModuleStyleTextAction(
        icon = icon,
        text = text,
        enabled = enabled,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        onClick = onClick,
    )
}
