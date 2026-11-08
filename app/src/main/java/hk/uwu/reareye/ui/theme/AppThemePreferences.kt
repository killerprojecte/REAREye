package hk.uwu.reareye.ui.theme

import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class AppThemeLoadResult(val mode: AppThemeMode, val migrationPending: Boolean = false)

/** The theme value is the only backend authority. The former backend key is read only for migration. */
internal object AppThemePreferences {
    private val completedSaves = MutableStateFlow(0L)
    val changes = completedSaves.asStateFlow()
    internal const val LEGACY_BACKEND_KEY = "module_ui_backend"

    fun resolve(storedMode: Int, legacyBackend: Int?): AppThemeMode {
        val mode = AppThemeMode.fromValue(storedMode)
        if (legacyBackend != 1) return mode
        return when (mode) {
            AppThemeMode.MIUIX_SYSTEM -> AppThemeMode.MOCHI_SYSTEM
            AppThemeMode.MIUIX_LIGHT -> AppThemeMode.MOCHI_LIGHT
            AppThemeMode.MIUIX_DARK -> AppThemeMode.MOCHI_DARK
            AppThemeMode.MONET_SYSTEM -> AppThemeMode.LEGACY_MOCHI_MONET_SYSTEM
            AppThemeMode.MONET_LIGHT -> AppThemeMode.LEGACY_MOCHI_MONET_LIGHT
            AppThemeMode.MONET_DARK -> AppThemeMode.LEGACY_MOCHI_MONET_DARK
            else -> mode // Already unified values take precedence over every stale legacy value.
        }
    }

    /** Pure preference read for settings labels, including an interrupted migration. */
    fun readMode(prefs: HookPrefs): AppThemeMode = resolve(
        prefs.getInt(ConfigKeys.MODULE_THEME_MODE, AppThemeMode.default.value),
        if (prefs.contains(LEGACY_BACKEND_KEY)) prefs.getInt(LEGACY_BACKEND_KEY, 0) else null,
    )

    /** Missing keys keep the original iOS appearance without writing during reads or migration. */
    fun readNavigationStyle(prefs: HookPrefs): AppNavigationBarStyle = AppNavigationBarStyle.fromValue(
        prefs.getInt(ConfigKeys.MODULE_NAVIGATION_BAR_STYLE, AppNavigationBarStyle.default.value),
    )

    /** Run off the main thread. Removal is the migration marker, in the same commit as the new mode. */
    fun loadAndMigrate(prefs: HookPrefs): AppThemeLoadResult {
        val mode = readMode(prefs)
        if (!prefs.contains(LEGACY_BACKEND_KEY)) return AppThemeLoadResult(mode)
        // Keep rendering the resolved original choice if saving fails; callers surface the failure.
        return AppThemeLoadResult(mode, migrationPending = !writeMode(prefs, mode.value))
    }

    /** An explicit selection leaves legacy Monet compatibility and cannot be remigrated on restart. */
    fun saveSelection(prefs: HookPrefs, mode: AppThemeMode): Boolean = writeMode(prefs, mode.selectionValue)

    /** Save the sheet's complete draft together; cancellation never invokes this writer. */
    fun saveTheme(
        prefs: HookPrefs,
        mode: AppThemeMode,
        style: AppMochiThemeStyle,
        navigationStyle: AppNavigationBarStyle = readNavigationStyle(prefs),
    ): Boolean = try {
        val saved = prefs.edit().putInt(ConfigKeys.MODULE_THEME_MODE, mode.selectionValue)
            .putInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, style.value)
            .putInt(ConfigKeys.MODULE_NAVIGATION_BAR_STYLE, navigationStyle.value)
            .remove(LEGACY_BACKEND_KEY).commit()
        if (saved) completedSaves.update { it + 1 }
        saved
    } catch (_: Exception) { false }

    private fun writeMode(prefs: HookPrefs, value: Int): Boolean = try {
        prefs.edit()
            .putInt(ConfigKeys.MODULE_THEME_MODE, value)
            .remove(LEGACY_BACKEND_KEY)
            .commit()
    } catch (_: Exception) {
        false
    }
}
