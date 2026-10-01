package hk.uwu.reareye.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class FeatureGuideStep(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val anchorKey: String,
)

internal val featureGuideSteps = listOf(
    FeatureGuideStep(R.string.guide_home_title, R.string.guide_home_summary, "home_status"),
    FeatureGuideStep(R.string.guide_nav_title, R.string.guide_nav_summary, "bottom_navigation"),
    FeatureGuideStep(
        R.string.guide_config_title,
        R.string.guide_config_summary,
        "config_dashboard"
    ),
    FeatureGuideStep(R.string.guide_cards_title, R.string.guide_cards_summary, "config_dashboard"),
    FeatureGuideStep(
        R.string.guide_components_title,
        R.string.guide_components_summary,
        "config_dashboard"
    ),
    FeatureGuideStep(R.string.guide_more_title, R.string.guide_more_summary, "config_dashboard"),
)

@Composable
internal fun FeatureGuideOverlay(
    stepIndex: Int,
    anchors: Map<String, Rect>,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val step = featureGuideSteps[stepIndex.coerceIn(featureGuideSteps.indices)]
    val anchor = anchors[step.anchorKey]
    val accent = MiuixTheme.colorScheme.primary
    val density = LocalDensity.current
    val focusPadding = with(density) { 8.dp.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val focus = anchor?.let { rect ->
            Rect(
                left = (rect.left - focusPadding).coerceAtLeast(0f),
                top = (rect.top - focusPadding).coerceAtLeast(0f),
                right = (rect.right + focusPadding).coerceAtMost(constraints.maxWidth.toFloat()),
                bottom = (rect.bottom + focusPadding).coerceAtMost(constraints.maxHeight.toFloat()),
            )
        }
        val anchorNearBottom = focus?.center?.y?.let { it > constraints.maxHeight * 0.56f } ?: false

        Canvas(modifier = Modifier.fillMaxSize()) {
            val scrim = Color.Black.copy(alpha = 0.46f)
            if (focus == null) {
                drawRect(scrim)
            } else {
                drawRect(scrim, size = androidx.compose.ui.geometry.Size(size.width, focus.top))
                drawRect(
                    scrim,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, focus.bottom),
                    size = androidx.compose.ui.geometry.Size(
                        size.width,
                        size.height - focus.bottom
                    ),
                )
                drawRect(
                    scrim,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, focus.top),
                    size = androidx.compose.ui.geometry.Size(focus.left, focus.height),
                )
                drawRect(
                    scrim,
                    topLeft = androidx.compose.ui.geometry.Offset(focus.right, focus.top),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - focus.right,
                        focus.height
                    ),
                )
                drawRoundRect(
                    color = accent,
                    topLeft = androidx.compose.ui.geometry.Offset(focus.left, focus.top),
                    size = androidx.compose.ui.geometry.Size(focus.width, focus.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        14.dp.toPx(),
                        14.dp.toPx()
                    ),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(if (anchorNearBottom) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth(0.92f)
                .padding(
                    top = if (anchorNearBottom) 24.dp else 0.dp,
                    bottom = if (anchorNearBottom) 0.dp else 24.dp,
                )
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(step.titleRes),
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(step.summaryRes),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(stringResource(R.string.guide_skip))
                }
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (stepIndex == featureGuideSteps.lastIndex) R.string.guide_done else R.string.guide_next))
                    Icon(
                        Icons.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
