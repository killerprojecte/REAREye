package hk.uwu.reareye.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class RearBadgePalette(
    val background: Color,
    val text: Color,
)

data class RearBadgeItem(
    val text: String,
    val emphasized: Boolean = false,
    val palette: RearBadgePalette? = null,
)

@Composable
fun rememberRearAccentBadgePalette(accent: Color): RearBadgePalette {
    val colorScheme = MiuixTheme.colorScheme
    val darkTheme = colorScheme.surface.luminance() < 0.5f
    val harmonizedAccent = if (darkTheme) {
        lerp(lerp(accent, Color.White, 0.28f), colorScheme.primary, 0.08f)
    } else {
        lerp(accent, colorScheme.primary, 0.18f)
    }
    return remember(
        accent,
        darkTheme,
        colorScheme.surface,
        colorScheme.onSurface,
        harmonizedAccent,
    ) {
        if (darkTheme) {
            RearBadgePalette(
                background = lerp(colorScheme.surface, harmonizedAccent, 0.46f),
                text = lerp(Color.White, harmonizedAccent, 0.34f),
            )
        } else {
            RearBadgePalette(
                background = lerp(colorScheme.surface, harmonizedAccent, 0.24f),
                text = lerp(colorScheme.onSurface, harmonizedAccent, 0.72f),
            )
        }
    }
}

@Composable
private fun rememberRearDefaultBadgePalette(emphasized: Boolean): RearBadgePalette {
    val colorScheme = MiuixTheme.colorScheme
    val darkTheme = colorScheme.surface.luminance() < 0.5f
    return remember(
        emphasized,
        darkTheme,
        colorScheme.surface,
        colorScheme.onSurface,
        colorScheme.primary,
        colorScheme.secondaryContainer,
    ) {
        if (emphasized) {
            RearBadgePalette(
                background = if (darkTheme) {
                    lerp(colorScheme.surface, colorScheme.primary, 0.48f)
                } else {
                    colorScheme.primary.copy(alpha = 0.18f)
                },
                text = if (darkTheme) {
                    lerp(Color.White, colorScheme.primary, 0.30f)
                } else {
                    colorScheme.primary
                },
            )
        } else {
            RearBadgePalette(
                background = if (darkTheme) {
                    lerp(
                        colorScheme.surface,
                        lerp(colorScheme.secondaryContainer, Color.White, 0.16f),
                        0.72f,
                    )
                } else {
                    colorScheme.secondaryContainer.copy(alpha = 0.8f)
                },
                text = colorScheme.onSurface.copy(alpha = if (darkTheme) 0.90f else 0.82f),
            )
        }
    }
}

@Composable
fun RearBadgePill(
    text: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    palette: RearBadgePalette? = null,
    singleLine: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val pillFontSize = (13f / fontScale).sp
    val horizontalPadding = (10f / fontScale).dp.coerceAtLeast(7.dp)
    val verticalPadding = (7f / fontScale).dp.coerceAtLeast(5.dp)
    val defaultPalette = rememberRearDefaultBadgePalette(emphasized)
    val resolvedPalette = palette ?: defaultPalette

    Surface(
        modifier = modifier,
        color = resolvedPalette.background,
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            color = resolvedPalette.text,
            fontSize = pillFontSize,
            fontWeight = FontWeight.Medium,
            maxLines = if (singleLine) 1 else 2,
            softWrap = !singleLine,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RearBadgeGroup(
    badges: List<RearBadgeItem>,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty()) return

    val spacing = 6.dp
    Layout(
        modifier = modifier,
        content = {
            badges.forEach { badge ->
                RearBadgePill(
                    text = badge.text,
                    emphasized = badge.emphasized,
                    palette = badge.palette,
                    singleLine = true,
                )
            }
        },
    ) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        val badgeConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(badgeConstraints) }

        data class BadgePosition(
            val x: Int,
            val y: Int,
            val placeableIndex: Int,
        )

        val maxRowWidth = constraints.maxWidth
        val positions = mutableListOf<BadgePosition>()
        var currentX = 0
        var currentY = 0
        var currentLineHeight = 0
        var maxUsedWidth = 0

        placeables.forEachIndexed { index, placeable ->
            val proposedX = if (currentX == 0) 0 else currentX + spacingPx
            if (currentX > 0 && proposedX + placeable.width > maxRowWidth) {
                maxUsedWidth = maxOf(maxUsedWidth, currentX)
                currentY += currentLineHeight + spacingPx
                currentX = 0
                currentLineHeight = 0
            }

            val placeX = if (currentX == 0) 0 else currentX + spacingPx
            positions += BadgePosition(
                x = placeX,
                y = currentY,
                placeableIndex = index,
            )
            currentX = placeX + placeable.width
            currentLineHeight = maxOf(currentLineHeight, placeable.height)
        }

        maxUsedWidth = maxOf(maxUsedWidth, currentX)
        val contentHeight = if (placeables.isEmpty()) 0 else currentY + currentLineHeight
        val layoutWidth = maxUsedWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            positions.forEach { position ->
                placeables[position.placeableIndex].placeRelative(position.x, position.y)
            }
        }
    }
}
