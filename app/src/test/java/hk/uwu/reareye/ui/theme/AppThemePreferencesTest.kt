package hk.uwu.reareye.ui.theme

import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.HookPrefsEditor
import hk.uwu.reareye.ui.config.ConfigKeys
import org.junit.Assert.*
import org.junit.Test

class AppThemePreferencesTest {
    private val modeKey = ConfigKeys.MODULE_THEME_MODE
    private val backendKey = AppThemePreferences.LEGACY_BACKEND_KEY

    @Test fun everyLegacyMochiCombinationPreservesItsDarknessAndMonetChoice() {
        val expected = mapOf(3 to 6, 0 to 7, 1 to 8, 2 to 9, 4 to 10, 5 to 11)
        for ((old, migrated) in expected) {
            val prefs = MemoryPrefs(mapOf(modeKey to old, backendKey to 1,
                ConfigKeys.MODULE_MOCHI_THEME_STYLE to 2, "unrelated" to true))
            val result = AppThemePreferences.loadAndMigrate(prefs)
            assertEquals(migrated, result.mode.value)
            assertFalse(result.migrationPending)
            assertEquals(migrated, prefs.getInt(modeKey, -1))
            assertFalse(prefs.contains(backendKey))
            assertEquals(2, prefs.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, -1))
            assertTrue(prefs.getBoolean("unrelated", false))
            assertEquals(1, prefs.editCalls)
            assertEquals(1, prefs.commitCalls)
            assertEquals(0, prefs.applyCalls)
            assertEquals(listOf("putInt:$modeKey=$migrated", "remove:$backendKey"), prefs.lastOperations)
        }
    }

    @Test fun oldMiuixAndUnknownBackendsRetainOldThemeValues() {
        for (backend in listOf(0, -1, 999)) {
            for (value in 0..5) {
                val prefs = MemoryPrefs(mapOf(modeKey to value, backendKey to backend))
                assertEquals(value, AppThemePreferences.loadAndMigrate(prefs).mode.value)
                assertEquals(value, prefs.getInt(modeKey, -1))
                assertFalse(prefs.contains(backendKey))
            }
        }
    }

    @Test fun unifiedValuesOverrideAnyStaleBackendKey() {
        for (backend in listOf(0, 1, 99)) {
            for (value in 6..11) {
                val prefs = MemoryPrefs(mapOf(modeKey to value, backendKey to backend))
                assertEquals(value, AppThemePreferences.loadAndMigrate(prefs).mode.value)
                assertEquals(value, prefs.getInt(modeKey, -1))
                assertFalse(prefs.contains(backendKey))
            }
        }
    }

    @Test fun migrationIsIdempotentAndNewInstallReadsDoNotWrite() {
        val fresh = MemoryPrefs()
        assertEquals(AppThemeMode.default, AppThemePreferences.loadAndMigrate(fresh).mode)
        assertEquals(0, fresh.editCalls)
        val prefs = MemoryPrefs(mapOf(modeKey to 4, backendKey to 1))
        assertEquals(10, AppThemePreferences.loadAndMigrate(prefs).mode.value)
        assertEquals(10, AppThemePreferences.loadAndMigrate(prefs).mode.value)
        assertEquals(1, prefs.editCalls)
        assertEquals(1, prefs.commitCalls)
    }

    @Test fun failedCommitKeepsOriginalResolvedModeAndReportsPendingForRetry() {
        val prefs = MemoryPrefs(mapOf(modeKey to 5, backendKey to 1)).apply { failCommit = true }
        val before = prefs.all()
        val result = AppThemePreferences.loadAndMigrate(prefs)
        assertEquals(AppThemeMode.LEGACY_MOCHI_MONET_DARK, result.mode)
        assertTrue(result.migrationPending)
        assertEquals(before, prefs.all())
        assertEquals(result.mode, AppThemePreferences.readMode(prefs))
        assertEquals(1, prefs.editCalls) // Label reads never retry a write on the UI thread.
        prefs.failCommit = false
        assertFalse(AppThemePreferences.loadAndMigrate(prefs).migrationPending)
        assertEquals(11, prefs.getInt(modeKey, -1))
        assertFalse(prefs.contains(backendKey))
    }

    @Test fun thrownCommitAlsoPreservesChoiceAndReportsPending() {
        val prefs = MemoryPrefs(mapOf(modeKey to 1, backendKey to 1)).apply { throwOnCommit = true }
        val before = prefs.all()
        val result = AppThemePreferences.loadAndMigrate(prefs)
        assertEquals(AppThemeMode.MOCHI_DARK, result.mode)
        assertTrue(result.migrationPending)
        assertEquals(before, prefs.all())
    }

    @Test fun explicitMiuixSelectionAfterFailedMigrationCannotTurnBackIntoMochi() {
        val prefs = MemoryPrefs(mapOf(modeKey to 2, backendKey to 1)).apply { failCommit = true }
        assertTrue(AppThemePreferences.loadAndMigrate(prefs).migrationPending)
        prefs.failCommit = false
        assertTrue(AppThemePreferences.saveSelection(prefs, AppThemeMode.MIUIX_LIGHT))
        assertEquals(listOf("putInt:$modeKey=0", "remove:$backendKey"), prefs.lastOperations)
        assertFalse(prefs.contains(backendKey))
        assertEquals(AppThemeMode.MIUIX_LIGHT, AppThemePreferences.loadAndMigrate(prefs).mode)
    }

    @Test fun explicitSelectionExitsHiddenMonetModeAndReportsSaveFailures() {
        val prefs = MemoryPrefs(mapOf(modeKey to 9)).apply { failCommit = true }
        assertFalse(AppThemePreferences.saveSelection(prefs, AppThemeMode.MOCHI_SYSTEM))
        assertEquals(9, prefs.getInt(modeKey, -1))
        prefs.failCommit = false
        assertTrue(AppThemePreferences.saveSelection(prefs, AppThemeMode.LEGACY_MOCHI_MONET_SYSTEM))
        assertEquals(6, prefs.getInt(modeKey, -1))
        assertFalse(prefs.contains(backendKey))
    }

    @Test fun missingOrInvalidOldModesUseThePriorBackendDefaultSafely() {
        assertEquals(AppThemeMode.MOCHI_SYSTEM,
            AppThemePreferences.loadAndMigrate(MemoryPrefs(mapOf(backendKey to 1))).mode)
        assertEquals(AppThemeMode.MOCHI_SYSTEM, AppThemePreferences.resolve(999, 1))
        assertEquals(AppThemeMode.MIUIX_SYSTEM, AppThemePreferences.resolve(999, 999))
        assertEquals(AppThemeMode.MIUIX_SYSTEM, AppThemePreferences.resolve(-1, null))
    }

    @Test fun completeDraftUsesOneAtomicCommitAndPreservesBusinessPreferences() {
        val prefs = MemoryPrefs(mapOf(modeKey to 5, backendKey to 1, "business" to "unchanged"))
        assertTrue(AppThemePreferences.saveTheme(prefs, AppThemeMode.MIUIX_LIGHT, AppMochiThemeStyle.Nothing))
        assertEquals(1, prefs.editCalls)
        assertEquals(1, prefs.commitCalls)
        assertEquals(0, prefs.applyCalls)
        assertEquals(listOf("putInt:$modeKey=0",
            "putInt:${ConfigKeys.MODULE_MOCHI_THEME_STYLE}=2",
            "putInt:${ConfigKeys.MODULE_NAVIGATION_BAR_STYLE}=0",
            "remove:${ConfigKeys.MODULE_NAVIGATION_BAR_MODE}", "remove:$backendKey"), prefs.lastOperations)
        assertEquals("unchanged", prefs.getString("business", ""))
        assertEquals(AppThemeMode.MIUIX_LIGHT, AppThemePreferences.loadAndMigrate(prefs).mode)
    }

    @Test fun failedAndThrownDraftSavesPreserveMigrationForRetry() {
        for (throws in listOf(false, true)) {
            val prefs = MemoryPrefs(mapOf(modeKey to 4, backendKey to 1,
                ConfigKeys.MODULE_MOCHI_THEME_STYLE to 2))
            prefs.failCommit = !throws
            prefs.throwOnCommit = throws
            val before = prefs.all()
            assertFalse(AppThemePreferences.saveTheme(prefs, AppThemeMode.MOCHI_DARK, AppMochiThemeStyle.Ios))
            assertEquals(before, prefs.all())
            assertEquals(AppThemeMode.LEGACY_MOCHI_MONET_LIGHT, AppThemePreferences.readMode(prefs))
            prefs.failCommit = false
            prefs.throwOnCommit = false
            assertTrue(AppThemePreferences.saveTheme(prefs, AppThemeMode.MOCHI_DARK, AppMochiThemeStyle.Ios))
            assertEquals(8, prefs.getInt(modeKey, -1))
            assertEquals(1, prefs.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, -1))
            assertFalse(prefs.contains(backendKey))
        }
    }

    @Test fun absentAndInvalidNavigationStylesKeepOriginalIosWithoutWriting() {
        for (value in listOf(null, -1, 200)) {
            val prefs = MemoryPrefs(if (value == null) emptyMap() else mapOf(ConfigKeys.MODULE_NAVIGATION_BAR_STYLE to value))
            assertEquals(AppNavigationBarStyle.IOS, AppThemePreferences.readNavigationStyle(prefs))
            assertEquals(0, prefs.editCalls)
        }
    }

    @Test fun migrationAndOlderThemeSavesPreserveNavigationAndOtherPreferences() {
        val prefs = MemoryPrefs(mapOf(modeKey to 4, backendKey to 1,
            ConfigKeys.MODULE_NAVIGATION_BAR_STYLE to 1,
            ConfigKeys.MODULE_NAVIGATION_BAR_MODE to 2, "business" to true))
        AppThemePreferences.loadAndMigrate(prefs)
        assertEquals(AppNavigationBarStyle.HyperOS, AppThemePreferences.readNavigationStyle(prefs))
        assertTrue(AppThemePreferences.saveTheme(prefs, AppThemeMode.MIUIX_LIGHT, AppMochiThemeStyle.Ios))
        assertEquals(AppNavigationBarStyle.HyperOS, AppThemePreferences.readNavigationStyle(prefs))
        assertFalse(prefs.contains(ConfigKeys.MODULE_NAVIGATION_BAR_MODE))
        assertTrue(prefs.getBoolean("business", false))
    }

    @Test fun navigationDraftIsAtomicAndFailedSaveCanRetry() {
        val prefs = MemoryPrefs(mapOf(modeKey to 0, ConfigKeys.MODULE_NAVIGATION_BAR_STYLE to 0))
        val before = prefs.all()
        prefs.failCommit = true
        assertFalse(AppThemePreferences.saveTheme(prefs, AppThemeMode.MONET_DARK, AppMochiThemeStyle.Nothing, AppNavigationBarStyle.HyperOS))
        assertEquals(before, prefs.all())
        prefs.failCommit = false
        assertTrue(AppThemePreferences.saveTheme(prefs, AppThemeMode.MONET_DARK, AppMochiThemeStyle.Nothing, AppNavigationBarStyle.HyperOS))
        assertEquals(5, prefs.getInt(modeKey, -1))
        assertEquals(AppNavigationBarStyle.HyperOS, AppThemePreferences.readNavigationStyle(prefs))
        assertEquals(2, prefs.commitCalls)
    }

    @Test fun oldNormalPlacementMigratesOnlyWithoutExplicitStyleAndSaveRetiresKey() {
        val old = ConfigKeys.MODULE_NAVIGATION_BAR_MODE
        val style = ConfigKeys.MODULE_NAVIGATION_BAR_STYLE
        for (value in 0..3) {
            val prefs = MemoryPrefs(mapOf(old to value))
            val expected = if (value == 0) AppNavigationBarStyle.Legacy else AppNavigationBarStyle.IOS
            assertEquals(expected, AppThemePreferences.readNavigationStyle(prefs))
            assertEquals(0, prefs.editCalls)
            assertTrue(AppThemePreferences.saveTheme(prefs, AppThemeMode.MIUIX_LIGHT, AppMochiThemeStyle.default))
            assertFalse(prefs.contains(old))
            assertEquals(expected, AppThemePreferences.readNavigationStyle(prefs))
        }
        assertEquals(AppNavigationBarStyle.IOS, AppThemePreferences.readNavigationStyle(MemoryPrefs(mapOf(old to 0, style to 0))))
        assertEquals(AppNavigationBarStyle.HyperOS, AppThemePreferences.readNavigationStyle(MemoryPrefs(mapOf(old to 0, style to 1))))
    }

    private class MemoryPrefs(initial: Map<String, Any?> = emptyMap()) : HookPrefs {
        private val values = initial.toMutableMap()
        var editCalls = 0
        var commitCalls = 0
        var applyCalls = 0
        var failCommit = false
        var throwOnCommit = false
        var lastOperations = emptyList<String>()

        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defaultValue: Set<String>) = values[key] as? Set<String> ?: defaultValue
        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun getLong(key: String, defaultValue: Long) = values[key] as? Long ?: defaultValue
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun contains(key: String) = values.containsKey(key)
        override fun all(): Map<String, Any?> = values.toMap()
        override fun edit(): HookPrefsEditor {
            editCalls++
            return object : HookPrefsEditor {
                val operations = mutableListOf<String>()
                val writes = mutableMapOf<String, Any?>()
                val removals = mutableSetOf<String>()
                var clear = false
                fun put(key: String, value: Any?, type: String): HookPrefsEditor {
                    operations += "$type:$key=$value"
                    writes[key] = value
                    removals.remove(key)
                    return this
                }
                override fun putString(key: String, value: String?) = put(key, value, "putString")
                override fun putStringSet(key: String, value: Set<String>?) = put(key, value, "putStringSet")
                override fun putInt(key: String, value: Int) = put(key, value, "putInt")
                override fun putLong(key: String, value: Long) = put(key, value, "putLong")
                override fun putFloat(key: String, value: Float) = put(key, value, "putFloat")
                override fun putBoolean(key: String, value: Boolean) = put(key, value, "putBoolean")
                override fun remove(key: String): HookPrefsEditor {
                    operations += "remove:$key"
                    removals += key
                    writes.remove(key)
                    return this
                }
                override fun clear(): HookPrefsEditor { clear = true; return this }
                override fun commit(): Boolean {
                    commitCalls++
                    lastOperations = operations.toList()
                    if (throwOnCommit) error("Simulated remote preference service failure")
                    if (failCommit) return false
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    values.putAll(writes)
                    return true
                }
                override fun apply() { applyCalls++; commit() }
            }
        }
    }
}
