package hk.uwu.reareye.repository.rearwidget

import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.HookPrefsEditor
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository.getShowTimeTipForBusiness
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearBusinessExtraConfigRepositoryTest {
    @Test
    fun normalizedReadDoesNotWriteRemotePreferences() {
        val prefs = ReadOnlyProbePrefs(
            """
            {"version":1,"items":[{"business":" taxi ","config":{}}]}
            """.trimIndent(),
        )
        val manager = PrefsManager(prefs)

        val entries = RearBusinessExtraConfigRepository.getAllConfigs(manager)
        val showTimeTip = manager.getShowTimeTipForBusiness(" taxi ")

        assertEquals(listOf("taxi"), entries.map { it.business })
        assertFalse(
            entries.single().config.getBoolean(
                RearBusinessExtraConfigFields.HIDE_TIME_TIP,
                true
            )
        )
        assertTrue(showTimeTip)
        assertEquals(0, prefs.editCalls)
    }

    private class ReadOnlyProbePrefs(
        private val raw: String,
    ) : HookPrefs {
        var editCalls: Int = 0

        override fun getString(key: String, defaultValue: String): String =
            if (key == ConfigKeys.REAR_WIDGET_BUSINESS_EXTRA_CONFIG_DATA) raw else defaultValue

        override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
            defaultValue

        override fun getInt(key: String, defaultValue: Int): Int = defaultValue
        override fun getLong(key: String, defaultValue: Long): Long = defaultValue
        override fun getFloat(key: String, defaultValue: Float): Float = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override fun contains(key: String): Boolean = false
        override fun all(): Map<String, Any?> = emptyMap()

        override fun edit(): HookPrefsEditor {
            editCalls++
            error("read-only probe must not create an editor")
        }
    }
}
