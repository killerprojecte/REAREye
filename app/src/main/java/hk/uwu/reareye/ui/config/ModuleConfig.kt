package hk.uwu.reareye.ui.config

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import hk.uwu.reareye.R
import hk.uwu.reareye.lyrics.LyricParser
import hk.uwu.reareye.ui.theme.AppThemeMode
import kotlin.math.roundToInt

object ConfigKeys {
    const val MODULE_HIDE_LAUNCHER_ENTRY = "module_hide_launcher_entry"
    const val MODULE_THEME_MODE = "module_theme_mode"
    const val MODULE_NAVIGATION_BAR_MODE = "module_navigation_bar_mode"
    const val MODULE_SEARCH_BAR_STYLE = "module_search_bar_style"
    const val MODULE_STORE_API_PROVIDER = "module_store_api_provider"
    const val MODULE_STORE_API_CUSTOM_DOMAIN = "module_store_api_custom_domain"
    const val MODULE_STORE_WEBVIEW_HARDWARE_ACCELERATION =
        "module_store_webview_hardware_acceleration"
    const val REAR_STORE_WALLPAPER_INSTALL_DATA = "rear_store_wallpaper_install_data"

    const val HOOK_ACTIVITIES_WHITELIST = "enable_activities_whitelist_hook"
    const val ACTIVITIES_WHITELIST_APPS = "activities_whitelist_apps"
    const val ALLOW_ALL_ACTIVITIES = "allow_all_activities"
    const val HOOK_SKIP_LOCK_BACK_HOME = "enable_skip_lock_back_home"
    const val CFG_CUSTOM_BOUNDS_COMPAT_MANAGER = "cfg_custom_bounds_compat_manager"
    const val CUSTOM_BOUNDS_COMPAT_APPS = "custom_bounds_compat_apps"
    const val CUSTOM_BOUNDS_COMPAT_CONFIG_DATA = "custom_bounds_compat_config_data"

    const val HOOK_MUSIC_CONTROLS_WHITELIST = "enable_music_controls_whitelist_hook"
    const val MUSIC_CONTROLS_WHITELIST_APPS = "music_controls_whitelist_apps"
    const val SUBSCREEN_LOCK_BACK_HOME_WHITELIST_APPS =
        "subscreen_lock_back_home_whitelist_apps"
    const val HOOK_MUSIC_CONTROLS_FORCE_UPDATE = "enable_music_controls_force_update"
    const val HOOK_VIDEO_LOOPING = "enable_video_looping"
    const val HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS =
        "enable_video_wallpaper_resume_progress"

    const val CFG_REAR_WALLPAPER_MANAGER = "cfg_rear_wallpaper_manager"
    const val REAR_WALLPAPER_SCHEDULE_ENABLED = "rear_wallpaper_schedule_enabled"
    const val REAR_WALLPAPER_SCHEDULE_DATA = "rear_wallpaper_schedule_data"
    const val REAR_WALLPAPER_SCHEDULE_NEXT_AT = "rear_wallpaper_schedule_next_at"

    const val CFG_REAR_WIDGET_BUSINESS_MANAGER = "cfg_rear_widget_business_manager"
    const val CFG_REAR_WIDGET_SCENE_ROUTE_MANAGER = "cfg_rear_widget_scene_route_manager"
    const val CFG_REAR_WIDGET_CARD_MANAGER = "cfg_rear_widget_card_manager"
    const val CFG_REAR_WIDGET_BUSINESS_EXTRA_MANAGER = "cfg_rear_widget_business_extra_manager"
    const val REAR_WIDGET_BUSINESS_DATA = "rear_widget_business_data"
    const val REAR_WIDGET_SCENE_ROUTE_DATA = "rear_widget_scene_route_data"
    const val REAR_WIDGET_CARD_DATA = "rear_widget_card_data"
    const val REAR_WIDGET_BUSINESS_EXTRA_CONFIG_DATA = "rear_widget_business_extra_config_data"
    const val HOOK_ALLOW_REAR_FOCUS_NOTICES = "enable_allow_rear_focus_notices"
    const val HOOK_DYNAMIC_ISLAND_AUTH_WHITELIST =
        "enable_dynamic_island_auth_whitelist_hook"
    const val DYNAMIC_ISLAND_AUTH_WHITELIST_APPS =
        "dynamic_island_auth_whitelist_apps"

    const val HOOK_BACKGROUND_WHITELIST = "enable_background_whitelist_hook"
    const val BACKGROUND_WHITELIST_APPS = "background_whitelist_apps"
    const val BACKGROUND_LOCK_APPS = "background_lock_apps"

    const val MISC_HOOK_GMS_UNLOCK = "enable_misc_gms_unlock"

    const val HOOK_UNLOCK_VIDEO_RESTRICTIONS = "enable_unlock_video_restrictions"
    const val HOOK_UNLOCK_TEMPLATE_MAXIMUM_LIMIT = "enable_unlock_template_maximum_limit"
    const val HOOK_UNMUTE_VIDEO_WALLPAPER = "enable_unmute_video_wallpaper"
    const val VIDEO_WALLPAPER_VOLUME = "unmute_video_wallpaper_volume"
    const val VIDEO_WALLPAPER_VOLUME_DEFAULT = 0.0f

    const val LYRIC_DISPLAY_MODE = "lyric_display_mode"
    val LYRIC_DISPLAY_MODE_DEFAULT = LyricParser.DisplayMode.ORIGINAL.mask or
            LyricParser.DisplayMode.TRANSLATION.mask or
            LyricParser.DisplayMode.ROMANIZATION.mask
    const val LYRIC_SHOW_ARTIST_BEFORE_FIRST_LINE = "lyric_show_artist_before_first_line"

    const val LYRIC_PROVIDER = "lyric_provider"
    val LYRIC_PROVIDER_DEFAULT = LyricProvider.LYRICON.value

    const val SUPER_LYRIC_DISPLAY_MODE = "super_lyric_display_mode"
    val SUPER_LYRIC_DISPLAY_MODE_DEFAULT = LyricParser.DisplayMode.ORIGINAL.mask
    const val HOOK_REMOVE_NATIVE_LYRIC_SUPPORT = "enable_remove_native_lyric_support"
    const val HOOK_SKIP_UNCHANGED_MEDIA_TITLE_UPDATE = "enable_skip_unchanged_media_title_update"
    const val HOOK_TAKE_OVER_BUILTIN_LYRIC_HANDLING =
        "enable_take_over_builtin_lyric_handling"

    const val HOOK_DISABLE_REAR_SCREEN_COVER = "enable_hook_rear_screen_cover"
    const val SUBSCREEN_DOUBLE_TAP_SLEEP_DISABLED_APPS =
        "subscreen_double_tap_sleep_disabled_apps"
    const val SUBSCREEN_DOUBLE_TAP_WAKE_DISABLED_APPS =
        "subscreen_double_tap_wake_disabled_apps"
    const val SUBSCREEN_HIGH_LOAD_MODE_DISABLED_APPS =
        "subscreen_high_load_mode_disabled_apps"

    const val MORE_DEBUG = "enable_more_debug_logging"
    const val MODULE_FAVORITE_CONFIG_NODES = "module_favorite_config_nodes"
    const val MODULE_NAVIGATION_QUICK_ACTIONS = "module_navigation_quick_actions"
}

enum class ModuleNavigationBarMode(
    val value: Int,
    @param:StringRes val titleRes: Int,
) {
    NORMAL(
        value = 0,
        titleRes = R.string.module_navigation_bar_mode_normal,
    ),
    FLOATING(
        value = 1,
        titleRes = R.string.module_navigation_bar_mode_floating,
    ),
    FLOATING_GLASS(
        value = 2,
        titleRes = R.string.module_navigation_bar_mode_floating_glass,
    ),

    SEMI_TRANSPARENT(
        value = 3,
        titleRes = R.string.module_navigation_bar_mode_semi_transparent,
    );

    companion object {
        val default = NORMAL
        val selectableEntries = listOf(
            NORMAL,
            FLOATING,
            FLOATING_GLASS,
            SEMI_TRANSPARENT,
        )

        fun fromValue(value: Int): ModuleNavigationBarMode {
            return entries.firstOrNull { it.value == value } ?: default
        }
    }
}

enum class ModuleSearchBarStyle(
    val value: Int,
    @param:StringRes val titleRes: Int,
) {
    DEFAULT(
        value = 0,
        titleRes = R.string.module_search_bar_style_default,
    ),
    MIUIX(
        value = 1,
        titleRes = R.string.module_search_bar_style_miuix,
    );

    companion object {
        val default = DEFAULT
        val selectableEntries = listOf(
            DEFAULT,
            MIUIX,
        )

        fun fromValue(value: Int): ModuleSearchBarStyle {
            return entries.firstOrNull { it.value == value } ?: default
        }
    }
}

enum class LyricProvider(val value: Int, val titleRes: Int) {
    LYRICON(value = 0, titleRes = R.string.lyric_provider_lyricon),
    SUPER_LYRIC(value = 1, titleRes = R.string.lyric_provider_superlyric);

    companion object {
        fun fromValue(value: Int): LyricProvider {
            return entries.firstOrNull { it.value == value } ?: LYRICON
        }
    }
}

private fun LyricParser.DisplayMode.toTitleRes(): Int {
    return when (this) {
        LyricParser.DisplayMode.ORIGINAL -> R.string.lyric_display_mode_original
        LyricParser.DisplayMode.TRANSLATION -> R.string.lyric_display_mode_translation
        LyricParser.DisplayMode.ROMANIZATION -> R.string.lyric_display_mode_romanization
    }
}

val REAREyeConfig = listOf(
    ConfigCategory(
        icon = ConfigCategoryIcon.Package("hk.uwu.reareye"),
        titleRes = R.string.category_module_settings,
        descriptionRes = R.string.category_module_settings_desc,
        children = listOf(
            ConfigItem(
                key = ConfigKeys.MODULE_THEME_MODE,
                titleRes = R.string.module_theme_mode,
                descriptionRes = R.string.module_theme_mode_desc,
                type = ConfigType.EnumSingleSelect(
                    defaultValue = AppThemeMode.default.value,
                    options = AppThemeMode.selectableEntries.map {
                        ConfigType.EnumOption(
                            titleRes = it.titleRes,
                            value = it.value,
                        )
                    },
                ),
            ),
            ConfigItem(
                key = ConfigKeys.MODULE_NAVIGATION_BAR_MODE,
                titleRes = R.string.module_navigation_bar_mode,
                descriptionRes = R.string.module_navigation_bar_mode_desc,
                type = ConfigType.EnumSingleSelect(
                    defaultValue = ModuleNavigationBarMode.default.value,
                    options = ModuleNavigationBarMode.selectableEntries.map {
                        ConfigType.EnumOption(
                            titleRes = it.titleRes,
                            value = it.value,
                        )
                    },
                ),
            ),
            ConfigItem(
                key = ConfigKeys.MODULE_SEARCH_BAR_STYLE,
                titleRes = R.string.module_search_bar_style,
                descriptionRes = R.string.module_search_bar_style_desc,
                type = ConfigType.EnumSingleSelect(
                    defaultValue = ModuleSearchBarStyle.default.value,
                    options = ModuleSearchBarStyle.selectableEntries.map {
                        ConfigType.EnumOption(
                            titleRes = it.titleRes,
                            value = it.value,
                        )
                    },
                ),
            ),
            ConfigItem(
                key = ConfigKeys.MODULE_STORE_API_PROVIDER,
                titleRes = R.string.module_store_api,
                descriptionRes = R.string.module_store_api_desc,
                type = ConfigType.RearStoreApi(
                    defaultProviderValue = StoreApiProvider.default.value,
                    customDomainKey = ConfigKeys.MODULE_STORE_API_CUSTOM_DOMAIN,
                ),
            ),
            ConfigItem(
                key = ConfigKeys.MODULE_STORE_WEBVIEW_HARDWARE_ACCELERATION,
                titleRes = R.string.module_store_webview_hardware_acceleration,
                descriptionRes = R.string.module_store_webview_hardware_acceleration_desc,
                type = ConfigType.BooleanVal(defaultValue = true)
            ),
            ConfigItem(
                key = ConfigKeys.MODULE_HIDE_LAUNCHER_ENTRY,
                titleRes = R.string.hide_launcher_entry,
                descriptionRes = R.string.hide_launcher_entry_desc,
                type = ConfigType.BooleanVal(defaultValue = false)
            ),
            ConfigItem(
                key = ConfigKeys.MORE_DEBUG,
                titleRes = R.string.cfg_more_debug,
                type = ConfigType.BooleanVal(defaultValue = false)
            )
        )
    ),
    ConfigCategory(
        icon = ConfigCategoryIcon.Package("system"),
        titleRes = R.string.category_system,
        children = listOf(
            ConfigCategory(
                titleRes = R.string.cfg_activities_whitelist,
                descriptionRes = R.string.cfg_activities_whitelist_desc,
                children = listOf(
                    ConfigItem(
                        key = ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                        titleRes = R.string.enable_custom_activities_whitelist,
                        type = ConfigType.BooleanVal(defaultValue = true)
                    ),
                    ConfigItem(
                        key = ConfigKeys.ALLOW_ALL_ACTIVITIES,
                        titleRes = R.string.allow_all_activities,
                        type = ConfigType.BooleanVal(defaultValue = false)
                    ),
                    ConfigItem(
                        key = ConfigKeys.ACTIVITIES_WHITELIST_APPS,
                        titleRes = R.string.custom_activities_whitelist_apps,
                        descriptionRes = R.string.custom_activities_whitelist_apps_desc,
                        type = ConfigType.AppList(defaultValues = emptySet())
                    )
                )
            ),
            ConfigCategory(
                titleRes = R.string.cfg_background_whitelist,
                descriptionRes = R.string.cfg_background_whitelist_desc,
                children = listOf(
                    ConfigGroup(
                        children = listOf(
                            ConfigItem(
                                key = ConfigKeys.HOOK_BACKGROUND_WHITELIST,
                                titleRes = R.string.enable_background_whitelist,
                                descriptionRes = R.string.enable_background_whitelist_desc,
                                type = ConfigType.BooleanVal(defaultValue = true)
                            ),
                            ConfigItem(
                                key = ConfigKeys.BACKGROUND_WHITELIST_APPS,
                                titleRes = R.string.background_whitelist_apps,
                                descriptionRes = R.string.background_whitelist_apps_desc,
                                type = ConfigType.AppList(defaultValues = emptySet())
                            )
                        )
                    ),
                    ConfigItem(
                        key = ConfigKeys.BACKGROUND_LOCK_APPS,
                        titleRes = R.string.background_lock_apps,
                        descriptionRes = R.string.background_lock_apps_desc,
                        type = ConfigType.AppList(defaultValues = emptySet())
                    ),
                )
            ),
            ConfigItem(
                key = ConfigKeys.HOOK_SKIP_LOCK_BACK_HOME,
                titleRes = R.string.skip_lock_back_home,
                type = ConfigType.BooleanVal(defaultValue = false)
            ),
            ConfigItem(
                key = ConfigKeys.CFG_CUSTOM_BOUNDS_COMPAT_MANAGER,
                titleRes = R.string.custom_bounds_compat_manager,
                descriptionRes = R.string.custom_bounds_compat_manager_desc,
                type = ConfigType.Manager(ConfigType.ManagerType.BOUNDS),
            ),
            ConfigItem(
                key = ConfigKeys.HOOK_DISABLE_REAR_SCREEN_COVER,
                titleRes = R.string.cfg_disable_rear_screen_cover,
                descriptionRes = R.string.cfg_disable_rear_screen_cover_desc,
                type = ConfigType.BooleanVal(defaultValue = false)
            ),
            ConfigItem(
                key = ConfigKeys.SUBSCREEN_DOUBLE_TAP_SLEEP_DISABLED_APPS,
                titleRes = R.string.cfg_disable_subscreen_double_tap_sleep,
                descriptionRes = R.string.cfg_disable_subscreen_double_tap_sleep_desc,
                type = ConfigType.AppList(defaultValues = emptySet())
            ),
            ConfigItem(
                key = ConfigKeys.SUBSCREEN_DOUBLE_TAP_WAKE_DISABLED_APPS,
                titleRes = R.string.cfg_disable_subscreen_double_tap_wake,
                descriptionRes = R.string.cfg_disable_subscreen_double_tap_wake_desc,
                type = ConfigType.AppList(defaultValues = emptySet())
            ),
            ConfigItem(
                key = ConfigKeys.SUBSCREEN_HIGH_LOAD_MODE_DISABLED_APPS,
                titleRes = R.string.cfg_disable_subscreen_high_load_mode,
                descriptionRes = R.string.cfg_disable_subscreen_high_load_mode_desc,
                type = ConfigType.AppList(defaultValues = emptySet())
            )
        )
    ),
    ConfigCategory(
        icon = ConfigCategoryIcon.Package("com.xiaomi.subscreencenter"),
        titleRes = R.string.category_subscreencenter,
        children = listOf(
            ConfigCategory(
                titleRes = R.string.rear_widget_manager_category,
                descriptionRes = R.string.rear_widget_manager_category_desc,
                children = listOf(
                    ConfigItem(
                        key = ConfigKeys.CFG_REAR_WIDGET_BUSINESS_MANAGER,
                        titleRes = R.string.rear_widget_business_manager,
                        descriptionRes = R.string.rear_widget_business_manager_desc,
                        type = ConfigType.Manager(ConfigType.ManagerType.BUSINESS),
                    ),
                    ConfigItem(
                        key = ConfigKeys.CFG_REAR_WIDGET_SCENE_ROUTE_MANAGER,
                        titleRes = R.string.rear_widget_scene_route_manager,
                        descriptionRes = R.string.rear_widget_scene_route_manager_desc,
                        type = ConfigType.Manager(ConfigType.ManagerType.SCENE_ROUTE),
                    ),
                    ConfigItem(
                        key = ConfigKeys.CFG_REAR_WIDGET_CARD_MANAGER,
                        titleRes = R.string.rear_widget_card_manager,
                        descriptionRes = R.string.rear_widget_card_manager_desc,
                        type = ConfigType.Manager(ConfigType.ManagerType.CARD),
                    ),
                    ConfigItem(
                        key = ConfigKeys.CFG_REAR_WALLPAPER_MANAGER,
                        titleRes = R.string.rear_wallpaper_manager,
                        descriptionRes = R.string.rear_wallpaper_manager_desc,
                        type = ConfigType.Manager(ConfigType.ManagerType.REAR_WALLPAPER),
                    ),
                    ConfigItem(
                        key = ConfigKeys.CFG_REAR_WIDGET_BUSINESS_EXTRA_MANAGER,
                        titleRes = R.string.rear_widget_business_extra_manager,
                        descriptionRes = R.string.rear_widget_business_extra_manager_desc,
                        type = ConfigType.Manager(ConfigType.ManagerType.BUSINESS_EXTRA),
                    ),
                    ConfigItem(
                        key = ConfigKeys.HOOK_ALLOW_REAR_FOCUS_NOTICES,
                        titleRes = R.string.allow_rear_focus_notices,
                        descriptionRes = R.string.allow_rear_focus_notices_desc,
                        type = ConfigType.BooleanVal(defaultValue = false),
                    ),
                ),
            ),
            ConfigCategory(
                titleRes = R.string.cfg_dynamic_island_auth_whitelist,
                descriptionRes = R.string.cfg_dynamic_island_auth_whitelist_desc,
                children = listOf(
                    ConfigGroup(
                        children = listOf(
                            ConfigItem(
                                key = ConfigKeys.HOOK_DYNAMIC_ISLAND_AUTH_WHITELIST,
                                titleRes = R.string.enable_dynamic_island_auth_whitelist,
                                type = ConfigType.BooleanVal(defaultValue = true),
                            ),
                            ConfigItem(
                                key = ConfigKeys.DYNAMIC_ISLAND_AUTH_WHITELIST_APPS,
                                titleRes = R.string.dynamic_island_auth_whitelist_apps,
                                descriptionRes =
                                    R.string.dynamic_island_auth_whitelist_apps_desc,
                                type = ConfigType.AppList(defaultValues = emptySet()),
                            ),
                        ),
                    ),
                ),
            ),
            ConfigCategory(
                titleRes = R.string.cfg_music_control_whitelist,
                descriptionRes = R.string.cfg_music_control_whitelist_desc,
                children = listOf(
                    ConfigGroup(
                        children = listOf(
                            ConfigItem(
                                key = ConfigKeys.HOOK_MUSIC_CONTROLS_WHITELIST,
                                titleRes = R.string.enable_music_control_whitelist,
                                type = ConfigType.BooleanVal(defaultValue = true)
                            ),
                            ConfigItem(
                                key = ConfigKeys.MUSIC_CONTROLS_WHITELIST_APPS,
                                titleRes = R.string.music_control_whitelist_apps,
                                descriptionRes = R.string.music_control_whitelist_apps_desc,
                                type = ConfigType.AppList(defaultValues = emptySet())
                            )
                        )
                    ),
                    ConfigItem(
                        key = ConfigKeys.HOOK_MUSIC_CONTROLS_FORCE_UPDATE,
                        titleRes = R.string.enable_music_control_force_update,
                        descriptionRes = R.string.enable_music_control_force_update_desc,
                        type = ConfigType.BooleanVal(defaultValue = false)
                    )
                )
            ),
            ConfigCategory(
                titleRes = R.string.cfg_subscreen_lock_back_home_whitelist,
                descriptionRes = R.string.cfg_subscreen_lock_back_home_whitelist_desc,
                children = listOf(
                    ConfigItem(
                        key = ConfigKeys.SUBSCREEN_LOCK_BACK_HOME_WHITELIST_APPS,
                        titleRes = R.string.subscreen_lock_back_home_whitelist_apps,
                        descriptionRes = R.string.subscreen_lock_back_home_whitelist_apps_desc,
                        type = ConfigType.AppList(defaultValues = emptySet())
                    )
                )
            ),
            ConfigCategory(
                titleRes = R.string.subcategory_lyrics,
                descriptionRes = R.string.subcategory_lyrics_desc,
                children = listOf(
                    ConfigItem(
                        key = ConfigKeys.LYRIC_DISPLAY_MODE,
                        titleRes = R.string.lyric_display_mode,
                        descriptionRes = R.string.lyric_display_mode_desc,
                        type = ConfigType.MaskMultiSelect(
                            defaultValue = ConfigKeys.LYRIC_DISPLAY_MODE_DEFAULT,
                            options = LyricParser.DisplayMode.entries.map {
                                ConfigType.MaskOption(
                                    titleRes = it.toTitleRes(),
                                    maskValue = it.mask,
                                )
                            }
                        )
                    ),
                    ConfigItem(
                        key = ConfigKeys.LYRIC_SHOW_ARTIST_BEFORE_FIRST_LINE,
                        titleRes = R.string.lyric_show_artist_before_first_line,
                        descriptionRes = R.string.lyric_show_artist_before_first_line_desc,
                        type = ConfigType.BooleanVal(defaultValue = false)
                    ),
                    ConfigItem(
                        key = ConfigKeys.LYRIC_PROVIDER,
                        titleRes = R.string.lyric_provider,
                        descriptionRes = R.string.lyric_provider_desc,
                        type = ConfigType.EnumSingleSelect(
                            defaultValue = ConfigKeys.LYRIC_PROVIDER_DEFAULT,
                            options = LyricProvider.entries.map {
                                ConfigType.EnumOption(
                                    titleRes = it.titleRes,
                                    value = it.value,
                                )
                            },
                        )
                    ),
                    ConfigItem(
                        key = ConfigKeys.SUPER_LYRIC_DISPLAY_MODE,
                        titleRes = R.string.super_lyric_display_mode,
                        descriptionRes = R.string.super_lyric_display_mode_desc,
                        type = ConfigType.EnumSingleSelect(
                            defaultValue = ConfigKeys.SUPER_LYRIC_DISPLAY_MODE_DEFAULT,
                            options = listOf(
                                LyricParser.DisplayMode.ORIGINAL,
                                LyricParser.DisplayMode.TRANSLATION,
                            ).map {
                                ConfigType.EnumOption(
                                    titleRes = it.toTitleRes(),
                                    value = it.mask,
                                )
                            },
                        )
                    ),
                    ConfigItem(
                        key = ConfigKeys.HOOK_REMOVE_NATIVE_LYRIC_SUPPORT,
                        titleRes = R.string.lyric_remove_native_lyrics,
                        descriptionRes = R.string.lyric_remove_native_lyrics_desc,
                        type = ConfigType.BooleanVal(defaultValue = false)
                    ),
                    ConfigItem(
                        key = ConfigKeys.HOOK_SKIP_UNCHANGED_MEDIA_TITLE_UPDATE,
                        titleRes = R.string.lyric_skip_unchanged_title_update,
                        descriptionRes = R.string.lyric_skip_unchanged_title_update_desc,
                        type = ConfigType.BooleanVal(defaultValue = false)
                    ),
                    ConfigItem(
                        key = ConfigKeys.HOOK_TAKE_OVER_BUILTIN_LYRIC_HANDLING,
                        titleRes = R.string.lyric_take_over_builtin_handling,
                        descriptionRes = R.string.lyric_take_over_builtin_handling_desc,
                        type = ConfigType.BooleanVal(defaultValue = true)
                    )
                )
            ),
            ConfigItem(
                key = ConfigKeys.HOOK_VIDEO_LOOPING,
                titleRes = R.string.enable_video_looping,
                type = ConfigType.BooleanVal(defaultValue = false)
            ),
            ConfigItem(
                key = ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS,
                titleRes = R.string.cfg_video_wallpaper_resume_progress,
                descriptionRes = R.string.cfg_video_wallpaper_resume_progress_desc,
                type = ConfigType.BooleanVal(defaultValue = false)
            ),
            ConfigItem(
                key = ConfigKeys.VIDEO_WALLPAPER_VOLUME,
                titleRes = R.string.cfg_video_wallpaper_volume,
                descriptionRes = R.string.cfg_video_wallpaper_volume_desc,
                type = ConfigType.FloatSlider(
                    defaultValue = ConfigKeys.VIDEO_WALLPAPER_VOLUME_DEFAULT,
                    minValue = 0f,
                    maxValue = 1.0f,
                    steps = 99,
                    decimalPlaces = 2,
                    valueFormatter = { value -> "${(value * 100f).roundToInt()}%" },
                )
            )
        )
    ),
    ConfigCategory(
        icon = ConfigCategoryIcon.Package("com.android.thememanager"),
        titleRes = R.string.category_thememanager,
        children = listOf(
            ConfigGroup(
                children = listOf(
                    ConfigItem(
                        key = ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS,
                        titleRes = R.string.cfg_unlock_video_restrictions,
                        descriptionRes = R.string.cfg_unlock_video_restrictions_desc,
                        type = ConfigType.BooleanVal(defaultValue = true)
                    ),
                    ConfigItem(
                        key = ConfigKeys.HOOK_UNLOCK_TEMPLATE_MAXIMUM_LIMIT,
                        titleRes = R.string.cfg_unlock_template_maximum_limit,
                        descriptionRes = R.string.cfg_unlock_template_maximum_limit_desc,
                        type = ConfigType.BooleanVal(defaultValue = true)
                    ),
                    ConfigItem(
                        key = ConfigKeys.HOOK_UNMUTE_VIDEO_WALLPAPER,
                        titleRes = R.string.cfg_unmute_video_wallpaper,
                        descriptionRes = R.string.cfg_unmute_video_wallpaper_desc,
                        type = ConfigType.BooleanVal(defaultValue = false)
                    )
                )
            )
        )
    ),
    ConfigCategory(
        icon = ConfigCategoryIcon.Compose(Icons.Filled.Tune),
        titleRes = R.string.category_misc,
        descriptionRes = R.string.category_misc_desc,
        children = listOf(
            ConfigItem(
                key = ConfigKeys.MISC_HOOK_GMS_UNLOCK,
                titleRes = R.string.enable_misc_unlock_gms,
                descriptionRes = R.string.category_misc_desc,
                type = ConfigType.BooleanVal(defaultValue = false)
            )
        )
    )
)
