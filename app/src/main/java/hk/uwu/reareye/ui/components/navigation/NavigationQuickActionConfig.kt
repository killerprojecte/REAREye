package hk.uwu.reareye.ui.components.navigation

const val MaxNavigationQuickActions = 5

const val NavigationQuickActionComponentManagerId = "config_manager_business"
const val NavigationQuickActionCardManagerId = "config_manager_card"
const val NavigationQuickActionWallpaperManagerId = "config_manager_wallpaper"
const val NavigationQuickActionSceneRouteManagerId = "config_manager_scene_route"
const val NavigationQuickActionBoundsManagerId = "config_manager_bounds"
const val NavigationQuickActionLyricsManagerId = "config_manager_lyrics"

val DefaultNavigationQuickActionIds = listOf(
    NavigationQuickActionComponentManagerId,
    NavigationQuickActionCardManagerId,
)

val AvailableNavigationQuickActionIds = listOf(
    NavigationQuickActionComponentManagerId,
    NavigationQuickActionCardManagerId,
    NavigationQuickActionWallpaperManagerId,
    NavigationQuickActionSceneRouteManagerId,
    NavigationQuickActionBoundsManagerId,
    NavigationQuickActionLyricsManagerId,
)

fun parseNavigationQuickActionIds(value: String): List<String> {
    val availableLookup = AvailableNavigationQuickActionIds.toSet()
    val parsed = value
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it in availableLookup }
        .distinct()
        .take(MaxNavigationQuickActions)

    return parsed.ifEmpty { DefaultNavigationQuickActionIds }
}

fun encodeNavigationQuickActionIds(ids: List<String>): String {
    val availableLookup = AvailableNavigationQuickActionIds.toSet()
    return ids
        .filter { it in availableLookup }
        .distinct()
        .take(MaxNavigationQuickActions)
        .joinToString("|")
}
