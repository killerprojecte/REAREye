package hk.uwu.reareye.hook.activityhost

import android.content.res.Configuration
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ActivityHostTaskConfigurationAdapterTest {
    private fun window(config: Configuration): Any =
        requireNotNull(config.javaClass.getField("windowConfiguration").get(config))

    private fun set(window: Any, name: String, rect: Rect) {
        window.javaClass.getMethod(name, Rect::class.java).invoke(window, rect)
    }

    private fun get(window: Any, name: String): Rect =
        Rect(window.javaClass.getMethod(name).invoke(window) as Rect)

    @Test
    fun qualifiersUseContentBoundsWithoutRewritingWindowGeometry() {
        val parent = Configuration().apply { densityDpi = 240 }
        val resolved = Configuration()
        val window = window(resolved)
        set(window, "setBounds", Rect(100, 200, 900, 1400))
        set(window, "setAppBounds", Rect(100, 250, 900, 1300))
        set(window, "setMaxBounds", Rect(0, 0, 1080, 1920))
        val beforeBounds = get(window, "getBounds")
        val beforeApp = get(window, "getAppBounds")
        val beforeMax = get(window, "getMaxBounds")
        assertTrue(ActivityHostTaskConfigurationAdapter.apply(parent, resolved, 320))
        assertEquals(320, resolved.densityDpi)
        assertEquals(400, resolved.screenWidthDp)
        assertEquals(525, resolved.screenHeightDp)
        assertEquals(400, resolved.smallestScreenWidthDp)
        assertEquals(400, resolved.javaClass.getField("compatScreenWidthDp").getInt(resolved))
        assertEquals(525, resolved.javaClass.getField("compatScreenHeightDp").getInt(resolved))
        assertEquals(
            400,
            resolved.javaClass.getField("compatSmallestScreenWidthDp").getInt(resolved)
        )
        assertEquals(Configuration.ORIENTATION_PORTRAIT, resolved.orientation)
        assertEquals(beforeBounds, get(window, "getBounds"))
        assertEquals(beforeApp, get(window, "getAppBounds"))
        assertEquals(beforeMax, get(window, "getMaxBounds"))
        val first = Configuration(resolved)
        assertTrue(ActivityHostTaskConfigurationAdapter.apply(parent, resolved, 320))
        assertEquals(first, resolved)
        // Translation is not a density change.
        set(window, "setBounds", Rect(120, 230, 920, 1430))
        set(window, "setAppBounds", Rect(120, 280, 920, 1330))
        assertTrue(ActivityHostTaskConfigurationAdapter.apply(parent, resolved, 320))
        assertEquals(first.densityDpi, resolved.densityDpi)
        assertEquals(first.screenWidthDp, resolved.screenWidthDp)
        assertEquals(first.screenHeightDp, resolved.screenHeightDp)
    }

    @Test
    fun zeroDpiInheritsWithoutPrivateQualifierWrites() {
        val parent = Configuration().apply { densityDpi = 240 }
        val resolved = Configuration().apply { screenWidthDp = 480; screenHeightDp = 800 }
        val original = Configuration(resolved)
        assertTrue(ActivityHostTaskConfigurationAdapter.apply(parent, resolved, 0))
        assertEquals(original, resolved)
    }

    @Test
    fun absentAppBoundsFailsClosedAndLeavesConfigurationUntouched() {
        val parent = Configuration();
        val resolved = Configuration()
        val original = Configuration(resolved)
        assertFalse(ActivityHostTaskConfigurationAdapter.apply(parent, resolved, 320))
        assertEquals(original, resolved)
    }

    @Test
    fun densityIndependentAndLandscapeDerived() {
        val low = ActivityHostTaskConfigurationAdapter.derive(1200, 800, 160)
        val high = ActivityHostTaskConfigurationAdapter.derive(1200, 800, 320)
        assertEquals(1200, low.widthDp)
        assertEquals(600, high.widthDp)
        assertEquals(400, high.smallestDp)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, high.orientation)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostTaskConfigurationAdapter.derive(1200, 800, 0)
        }
    }
}
