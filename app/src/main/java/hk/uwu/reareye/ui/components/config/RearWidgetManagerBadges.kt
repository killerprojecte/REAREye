package hk.uwu.reareye.ui.components.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.RearBadgeItem
import hk.uwu.reareye.ui.components.rememberRearAccentBadgePalette

private enum class RearWidgetManagerBadgeKind {
    SourceLocal,
    SourceStore,
    RepoId,
    PackageName,
    Business,
    Priority,
    Sticky,
    Rule,
    Count,
    Locked,
    TemplateDefault,
    TemplateCustom,
    AppCard,
    RearEye,
    External,
    ReadOnly,
    Component,
}

@Composable
private fun rememberRearWidgetBadgePalette(kind: RearWidgetManagerBadgeKind) =
    rememberRearAccentBadgePalette(
        when (kind) {
            RearWidgetManagerBadgeKind.SourceLocal -> Color(0xFF64748B)
            RearWidgetManagerBadgeKind.SourceStore -> Color(0xFF3B82F6)
            RearWidgetManagerBadgeKind.RepoId -> Color(0xFF06B6D4)
            RearWidgetManagerBadgeKind.PackageName -> Color(0xFF6366F1)
            RearWidgetManagerBadgeKind.Business -> Color(0xFF10B981)
            RearWidgetManagerBadgeKind.Priority -> Color(0xFFF59E0B)
            RearWidgetManagerBadgeKind.Sticky -> Color(0xFFEC4899)
            RearWidgetManagerBadgeKind.Rule -> Color(0xFF8B5CF6)
            RearWidgetManagerBadgeKind.Count -> Color(0xFF6366F1)
            RearWidgetManagerBadgeKind.Locked -> Color(0xFF475569)
            RearWidgetManagerBadgeKind.TemplateDefault -> Color(0xFF0EA5E9)
            RearWidgetManagerBadgeKind.TemplateCustom -> Color(0xFF14B8A6)
            RearWidgetManagerBadgeKind.AppCard -> Color(0xFF8B5CF6)
            RearWidgetManagerBadgeKind.RearEye -> Color(0xFF2563EB)
            RearWidgetManagerBadgeKind.External -> Color(0xFF64748B)
            RearWidgetManagerBadgeKind.ReadOnly -> Color(0xFF475569)
            RearWidgetManagerBadgeKind.Component -> Color(0xFF10B981)
        }
    )

@Composable
internal fun rearWidgetSourceBadges(
    downloadedFromStore: Boolean,
    storeWidgetId: String?,
): List<RearBadgeItem> {
    return buildList {
        add(rearWidgetSourceBadge(downloadedFromStore))
        storeWidgetId?.trim()?.takeIf { it.isNotBlank() }?.let {
            add(rearWidgetRepoIdBadge(it))
        }
    }
}

@Composable
internal fun rearWidgetSourceBadge(downloadedFromStore: Boolean): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(
            if (downloadedFromStore) {
                R.string.rear_widget_badge_source_store
            } else {
                R.string.rear_widget_badge_source_local
            }
        ),
        palette = rememberRearWidgetBadgePalette(
            if (downloadedFromStore) {
                RearWidgetManagerBadgeKind.SourceStore
            } else {
                RearWidgetManagerBadgeKind.SourceLocal
            }
        ),
    )
}

@Composable
internal fun rearWidgetRepoIdBadge(storeWidgetId: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_repo_id, storeWidgetId),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.RepoId),
    )
}

@Composable
internal fun rearWidgetPackageBadge(packageName: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_package, packageName),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.PackageName),
    )
}

@Composable
internal fun rearWidgetBusinessBadge(business: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_business, business),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Business),
    )
}

@Composable
internal fun rearWidgetPriorityBadge(priority: Int): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_priority, priority),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Priority),
    )
}

@Composable
internal fun rearWidgetStickyBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_sticky),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Sticky),
    )
}

@Composable
internal fun rearWidgetRuleBadge(rule: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_rule, rule),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Rule),
    )
}

@Composable
internal fun rearWidgetComponentCountBadge(count: Int): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_widget_count, count),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Count),
    )
}

@Composable
internal fun rearWidgetCardCountBadge(count: Int): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_card_count, count),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Count),
    )
}

@Composable
internal fun rearWidgetSceneRouteCountBadge(count: Int): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_scene_route_count, count),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Count),
    )
}

@Composable
internal fun rearWidgetLockedBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_locked),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Locked),
    )
}

@Composable
internal fun rearWidgetTemplateStatusBadge(hasCustomConfig: Boolean): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(
            if (hasCustomConfig) {
                R.string.rear_widget_badge_template_custom
            } else {
                R.string.rear_widget_badge_template_default
            }
        ),
        palette = rememberRearWidgetBadgePalette(
            if (hasCustomConfig) {
                RearWidgetManagerBadgeKind.TemplateCustom
            } else {
                RearWidgetManagerBadgeKind.TemplateDefault
            }
        ),
    )
}

@Composable
internal fun rearWidgetAppCardBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_app),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.AppCard),
    )
}

@Composable
internal fun rearWidgetRearEyeBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_reareye),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.RearEye),
    )
}

@Composable
internal fun rearWidgetExternalBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_external),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.External),
    )
}

@Composable
internal fun rearWidgetReadOnlyBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_read_only),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.ReadOnly),
    )
}

@Composable
internal fun rearWidgetComponentBadge(component: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_widget_badge_component, component),
        palette = rememberRearWidgetBadgePalette(RearWidgetManagerBadgeKind.Component),
    )
}
