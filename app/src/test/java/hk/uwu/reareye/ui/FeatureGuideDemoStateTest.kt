package hk.uwu.reareye.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGuideDemoStateTest {
    @Test
    fun productionModelsAreIsolatedBetweenSessions() {
        val edited = FeatureGuideDemoState()
        val fresh = FeatureGuideDemoState()
        val original = edited.cards.single()
        edited.cards = listOf(original.copy(title = "Edited", enabled = false, priority = 650))
        edited.businesses = edited.businesses.map { it.copy(business = "edited_clock") }
        edited.hideTimeTip = true
        edited.currentWallpaperId = edited.wallpapers("Dusk").single().wallpaperId
        assertEquals("Edited", edited.cards.single().title)
        assertEquals(650, edited.cards.single().priority)
        assertFalse(edited.cards.single().enabled)
        assertEquals("demo_clock", fresh.businesses.single().business)
        assertTrue(fresh.cards.single().enabled)
        assertFalse(fresh.hideTimeTip)
        assertEquals(null, fresh.currentWallpaperId)
        assertEquals("guide://wallpaper/dusk", fresh.wallpapers("Dusk").single().cachePath)
    }

    @Test
    fun preferenceEditsCannotLeakAcrossGuideSessions() {
        val a = FeatureGuideDemoState().prefsManager
        val b = FeatureGuideDemoState().prefsManager
        a.putString("config", "sample")
        a.putBoolean("enabled", true)
        a.putStringSet("items", setOf("one"))
        assertEquals("sample", a.getString("config"))
        assertEquals("", b.getString("config"))
        assertFalse(b.getBoolean("enabled", false))
        a.prefs.edit().clear().putInt("priority", 620).commit()
        assertEquals(620, a.getInt("priority", 0))
        assertEquals(setOf("priority"), a.all().keys)
    }
}
