package hk.uwu.reareye.ui.components.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearstore.RearStoreInstalledWallpaper
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperInfo
import hk.uwu.reareye.ui.components.RearBadgeItem
import hk.uwu.reareye.ui.components.rememberRearAccentBadgePalette

private enum class RearWallpaperBadgeKind {
    Current,
    CurrentName,
    Imported,
    Count,
    ScheduleOn,
    ScheduleOff,
    Interval,
    InSchedule,
    Unavailable,
    SubType,
    Editable,
    ThirdParty,
    SupportAon,
}

@Composable
private fun rememberRearWallpaperBadgePalette(kind: RearWallpaperBadgeKind) =
    rememberRearAccentBadgePalette(
        when (kind) {
            RearWallpaperBadgeKind.Current -> Color(0xFFEC4899)
            RearWallpaperBadgeKind.CurrentName -> Color(0xFFD946EF)
            RearWallpaperBadgeKind.Imported -> Color(0xFF3B82F6)
            RearWallpaperBadgeKind.Count -> Color(0xFF6366F1)
            RearWallpaperBadgeKind.ScheduleOn -> Color(0xFF10B981)
            RearWallpaperBadgeKind.ScheduleOff -> Color(0xFF64748B)
            RearWallpaperBadgeKind.Interval -> Color(0xFFF59E0B)
            RearWallpaperBadgeKind.InSchedule -> Color(0xFF8B5CF6)
            RearWallpaperBadgeKind.Unavailable -> Color(0xFFEF4444)
            RearWallpaperBadgeKind.SubType -> Color(0xFF06B6D4)
            RearWallpaperBadgeKind.Editable -> Color(0xFF14B8A6)
            RearWallpaperBadgeKind.ThirdParty -> Color(0xFF7C3AED)
            RearWallpaperBadgeKind.SupportAon -> Color(0xFF0EA5E9)
        }
    )

@Composable
internal fun rearWallpaperStatusBadges(
    currentWallpaperName: String,
    wallpaperCount: Int,
    scheduleEnabled: Boolean,
): List<RearBadgeItem> {
    return buildList {
        add(rearWallpaperCurrentNameBadge(currentWallpaperName))
        add(rearWallpaperCountBadge(wallpaperCount))
        add(rearWallpaperScheduleBadge(scheduleEnabled))
    }
}

@Composable
internal fun rearWallpaperManagementOverviewBadges(
    currentWallpaperName: String,
    wallpaperCount: Int,
): List<RearBadgeItem> {
    return buildList {
        add(rearWallpaperCurrentNameBadge(currentWallpaperName))
        add(rearWallpaperCountBadge(wallpaperCount))
    }
}

@Composable
internal fun rearWallpaperManagementBadges(
    wallpaper: RearWallpaperInfo,
    storeSource: RearStoreInstalledWallpaper?,
    isCurrent: Boolean,
    inSchedule: Boolean = false,
    intervalLabel: String? = null,
): List<RearBadgeItem> {
    return buildList {
        if (inSchedule) {
            add(rearWallpaperRotationBadge())
            intervalLabel?.let { add(rearWallpaperIntervalBadge(it)) }
        }
        addAll(
            rearWidgetSourceBadges(
                downloadedFromStore = storeSource != null,
                storeWidgetId = storeSource?.widgetId,
            )
        )
        if (wallpaper.imported) add(rearWallpaperImportedBadge())
        if (isCurrent) add(rearWallpaperCurrentBadge())
        wallpaper.resSubType.trim().takeIf { it.isNotBlank() }?.let {
            add(rearWallpaperSubTypeBadge(it))
        }
        if (wallpaper.editable) add(rearWallpaperEditableBadge())
        if (wallpaper.templateConfigAvailable || wallpaper.templateConfigCustomized) {
            add(rearWidgetTemplateStatusBadge(wallpaper.templateConfigCustomized))
        }
        if (wallpaper.thirdParties) add(rearWallpaperThirdPartyBadge())
        if (wallpaper.supportAon) add(rearWallpaperSupportAonBadge())
    }
}

@Composable
internal fun rearWallpaperScheduleItemBadges(
    wallpaper: RearWallpaperInfo?,
    intervalLabel: String,
    isCurrent: Boolean,
): List<RearBadgeItem> {
    return buildList {
        add(rearWallpaperRotationBadge())
        add(rearWallpaperIntervalBadge(intervalLabel))
        if (wallpaper == null) {
            add(rearWallpaperUnavailableBadge())
        } else {
            if (wallpaper.imported) add(rearWallpaperImportedBadge())
            wallpaper.resSubType.trim().takeIf { it.isNotBlank() }?.let {
                add(rearWallpaperSubTypeBadge(it))
            }
        }
        if (isCurrent) add(rearWallpaperCurrentBadge())
    }
}

@Composable
internal fun rearWallpaperPickerBadges(
    wallpaper: RearWallpaperInfo,
    isCurrent: Boolean,
    inSchedule: Boolean,
): List<RearBadgeItem> {
    return buildList {
        if (wallpaper.imported) add(rearWallpaperImportedBadge())
        if (isCurrent) add(rearWallpaperCurrentBadge())
        if (inSchedule) add(rearWallpaperInScheduleBadge())
        wallpaper.resSubType.trim().takeIf { it.isNotBlank() }?.let {
            add(rearWallpaperSubTypeBadge(it))
        }
    }
}

@Composable
internal fun rearWallpaperCurrentBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_current),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.Current),
    )
}

@Composable
internal fun rearWallpaperCurrentNameBadge(currentWallpaperName: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_current_name, currentWallpaperName),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.CurrentName),
    )
}

@Composable
internal fun rearWallpaperImportedBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_imported_badge),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.Imported),
    )
}

@Composable
internal fun rearWallpaperCountBadge(wallpaperCount: Int): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_count, wallpaperCount),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.Count),
    )
}

@Composable
internal fun rearWallpaperScheduleBadge(scheduleEnabled: Boolean): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(
            if (scheduleEnabled) {
                R.string.rear_wallpaper_badge_schedule_on
            } else {
                R.string.rear_wallpaper_badge_schedule_off
            }
        ),
        palette = rememberRearWallpaperBadgePalette(
            if (scheduleEnabled) {
                RearWallpaperBadgeKind.ScheduleOn
            } else {
                RearWallpaperBadgeKind.ScheduleOff
            }
        ),
    )
}

@Composable
internal fun rearWallpaperRotationBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_rotation),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.ScheduleOn),
    )
}

@Composable
internal fun rearWallpaperIntervalBadge(intervalLabel: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_interval, intervalLabel),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.Interval),
    )
}

@Composable
internal fun rearWallpaperInScheduleBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_in_schedule),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.InSchedule),
    )
}

@Composable
internal fun rearWallpaperUnavailableBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_unavailable),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.Unavailable),
    )
}

@Composable
internal fun rearWallpaperSubTypeBadge(subType: String): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_sub_type, subType),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.SubType),
    )
}

@Composable
internal fun rearWallpaperEditableBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_editable),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.Editable),
    )
}

@Composable
internal fun rearWallpaperThirdPartyBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_third_parties),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.ThirdParty),
    )
}

@Composable
internal fun rearWallpaperSupportAonBadge(): RearBadgeItem {
    return RearBadgeItem(
        text = stringResource(R.string.rear_wallpaper_badge_support_aon),
        palette = rememberRearWallpaperBadgePalette(RearWallpaperBadgeKind.SupportAon),
    )
}
