package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.graphics.Rect
import android.graphics.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ActivityHostInputRegionTest {
    private fun exercise(previous: Region?, check: (Region, Rect) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val host = ActivityHostSurfaceView(activity)
        try {
            activity.setContentView(host)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val root = host.rootView
            root.layout(0, 0, 976, 596)
            host.layout(0, 0, 976, 596)
            // There is no real WindowSession visibility event in this fixture. Seed
            // the attached platform View's window state; production gates remain intact.
            val attach = android.view.View::class.java.getDeclaredField("mAttachInfo")
                .apply { isAccessible = true }.get(host)
            attach.javaClass.getDeclaredField("mWindowVisibility").apply { isAccessible = true }
                .setInt(attach, android.view.View.VISIBLE)
            assertEquals(976, root.width)
            assertEquals(596, root.height)
            assertTrue(host.isAttachedToWindow)
            assertTrue(host.isShown)
            assertEquals(android.view.View.VISIBLE, host.windowVisibility)
            assertTrue(host.inputAbiReady)
            val type = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val info = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val region = type.getField("touchableRegion").get(info) as Region
            if (previous != null) {
                region.set(previous)
                type.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 3)
            }
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            val localHole = Rect(296, 0, 976, 596)
            val screenHole = Rect(localHole).apply { offset(origin[0], origin[1]) }
            host.setInputHole(screenHole)
            assertEquals(
                screenHole, ActivityHostSurfaceView::class.java.getDeclaredField("hole")
                .apply { isAccessible = true }.get(host)
            )
            val subtract = ActivityHostSurfaceView::class.java.getDeclaredMethod(
                "subtractHole",
                Any::class.java
            )
                .apply { isAccessible = true }
            subtract.invoke(host, info)
            assertTrue(host.inputAbiReady)
            assertEquals(
                3,
                type.getDeclaredField("mTouchableInsets").apply { isAccessible = true }.getInt(info)
            )
            check(region, localHole)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun fullRightActivityAreaIsRemovedFromHostInputInWindowLocalCoordinates() {
        exercise(null) { region, hole ->
            assertFalse(region.contains(hole.left + 1, 100))
            assertFalse(region.contains(hole.right - 1, 500))
            assertTrue(region.contains(100, 100))
        }
    }

    @Test
    fun anotherListenersNarrowRegionIsNotExpandedByActivityInputHole() {
        exercise(Region(Rect(0, 0, 200, 596))) { region, _ ->
            assertTrue(region.contains(100, 100))
            assertFalse(region.contains(250, 100))
            assertFalse(region.contains(500, 100))
        }
    }
}
