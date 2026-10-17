package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class RearDisplayDataSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private fun values(geometry: RearDisplayGeometry): Map<String, Any?> =
        RearDisplayDataSource.schema.validate(
            (RearDisplayDataSource.snapshot(geometry) as MamlDataResult.Success).snapshot
        ).values

    @Test
    fun absentCutoutFallbackOnlyAppliesToMeasuredMaxPoseRegardlessOfDensity() {
        val max = RearDisplayGeometry(976, 596, device = "popsicle", model = "2509FPN0BC")
        for (density in listOf(0, 320, 450, 600)) {
            val output = values(max.copy(densityDpi = density))
            assertEquals(976.0, output["width"])
            assertEquals(596.0, output["height"])
            assertEquals(296.0, output["safeLeft"])
            assertEquals(0.0, output["safeTop"])
            assertEquals(0.0, output["safeRight"])
            assertEquals(0.0, output["safeBottom"])
        }
        for (unknown in listOf(
            RearDisplayGeometry(904, 572), RearDisplayGeometry(976, 596),
            max.copy(rotation = 1), max.copy(width = 596, height = 976),
            max.copy(model = "unknown"), max.copy(device = "unknown")
        )) assertEquals(0.0, values(unknown)["safeLeft"])
        assertEquals(12.0, values(max.copy(insets = RearDisplayInsets(12, 0, 0, 0)))["safeLeft"])
    }

    @Test
    fun injectedProCutoutIsRuntimeDataNotAnAssumedCameraBoundary() {
        val output = values(RearDisplayGeometry(904, 572, RearDisplayInsets(180, 7, 5, 9)))
        assertEquals(180.0, output["safeLeft"])
        assertEquals(7.0, output["safeTop"])
        assertEquals(5.0, output["safeRight"])
        assertEquals(9.0, output["safeBottom"])
    }

    @Test
    fun cutoutReplacesFallbackAndNegativeInsetsClampToZero() {
        val output = values(RearDisplayGeometry(200, 100, RearDisplayInsets(-50, -20, 30, 40)))
        assertEquals(0.0, output["safeLeft"])
        assertEquals(0.0, output["safeTop"])
        assertEquals(30.0, output["safeRight"])
        assertEquals(40.0, output["safeBottom"])
        val zero = values(RearDisplayGeometry(200, 100, RearDisplayInsets(0, 0, 0, 0)))
        assertEquals(0.0, zero["safeLeft"])
        assertEquals(0.0, zero["safeRight"])
        assertEquals(0.0, zero["safeBottom"])
    }

    @Test
    fun oversizedInsetsAndOverlappingEdgesHaveNoPretendSafeArea() {
        for (insets in listOf(
            RearDisplayInsets(Int.MAX_VALUE, 0, 0, 0),
            RearDisplayInsets(0, 0, Int.MAX_VALUE, 0), RearDisplayInsets(0, Int.MAX_VALUE, 0, 0),
            RearDisplayInsets(0, 0, 0, Int.MAX_VALUE), RearDisplayInsets(70, 0, 40, 0)
        )) {
            assertTrue(
                RearDisplayDataSource.snapshot(
                    RearDisplayGeometry(
                        100,
                        100,
                        insets
                    )
                ) is MamlDataResult.Failure
            )
        }
        assertTrue(
            RearDisplayDataSource.snapshot(
                RearDisplayGeometry(
                    0,
                    100
                )
            ) is MamlDataResult.Failure
        )
        assertTrue(
            RearDisplayDataSource.snapshot(
                RearDisplayGeometry(
                    100,
                    -1
                )
            ) is MamlDataResult.Failure
        )
    }

    @Test
    fun defaultDisplayIsNeverUsedAsRearDisplayFallback() {
        val source = RearDisplayDataSource(context)
        try {
            var result: MamlDataResult? = null
            source.query(MamlDataRequest(emptyMap())) { result = it }
            assertTrue(result is MamlDataResult.Failure)
        } finally {
            source.close()
        }
    }

    @Test
    fun hostNonDefaultDisplayWinsAndDisplayChangesInvalidateOnlyWhileActive() {
        val firstId = ShadowDisplayManager.addDisplay("w800dp-h500dp-mdpi")
        val hostId = ShadowDisplayManager.addDisplay("w1200dp-h700dp-mdpi")
        val manager = context.getSystemService(DisplayManager::class.java)
        val source = RearDisplayDataSource(context.createDisplayContext(manager.getDisplay(hostId)))
        var invalidations = 0
        try {
            assertEquals(1, firstId)
            val fallback = RearDisplayDataSource(context)
            try {
                fallback.query(MamlDataRequest(emptyMap())) {
                    assertEquals(800.0, (it as MamlDataResult.Success).snapshot.values["width"])
                }
            } finally {
                fallback.close()
            }
            var output: MamlDataResult? = null
            source.query(MamlDataRequest(emptyMap())) { output = it }
            val snapshot = (output as MamlDataResult.Success).snapshot
            assertEquals(1200.0, snapshot.values["width"])
            assertEquals(700.0, snapshot.values["height"])
            source.subscribeInvalidations { invalidations++ }
            ShadowDisplayManager.changeDisplay(hostId, "w1300dp-h700dp-mdpi")
            main.idle()
            assertEquals(1, invalidations)
            source.query(MamlDataRequest(emptyMap())) { output = it }
            assertEquals(1300.0, (output as MamlDataResult.Success).snapshot.values["width"])
            source.onPause()
            ShadowDisplayManager.changeDisplay(hostId, "w1400dp-h700dp-mdpi")
            main.idle()
            assertEquals(1, invalidations)
            source.onResume()
            ShadowDisplayManager.changeDisplay(hostId, "w1500dp-h700dp-mdpi")
            main.idle()
            assertEquals(2, invalidations)
            source.close()
            ShadowDisplayManager.changeDisplay(hostId, "w1600dp-h700dp-mdpi")
            main.idle()
            assertEquals(2, invalidations)
        } finally {
            source.close()
            ShadowDisplayManager.removeDisplay(hostId)
            ShadowDisplayManager.removeDisplay(firstId)
            main.idle()
        }
    }

    @Test
    fun injectedGeometryPublishesSynchronouslyAndPausedClosedQueriesAreSuppressed() {
        var reads = 0
        val source = RearDisplayDataSource(
            context,
            RearDisplayGeometryProvider { reads++; RearDisplayGeometry(900, 500) })
        var callbacks = 0
        val handle = source.query(MamlDataRequest(emptyMap())) {
            callbacks++
            assertEquals(0.0, (it as MamlDataResult.Success).snapshot.values["safeLeft"])
        }
        assertEquals(1, callbacks)
        handle.close(); handle.close() // Synchronous completion leaves no queued work to cancel.
        source.onPause()
        source.query(MamlDataRequest(emptyMap())) { callbacks++ }
        assertEquals(1, reads)
        source.onResume()
        source.query(MamlDataRequest(emptyMap())) { callbacks++ }
        source.close(); source.close()
        source.query(MamlDataRequest(emptyMap())) { callbacks++ }
        assertEquals(2, reads)
        assertEquals(2, callbacks)
    }

    @Test
    fun everyQueryUsesCurrentRearSizeRotationAndCutout() {
        var geometry = RearDisplayGeometry(976, 596, RearDisplayInsets(296, 0, 0, 0), 450)
        val source = RearDisplayDataSource(context, RearDisplayGeometryProvider { geometry })
        try {
            source.query(MamlDataRequest(emptyMap())) {
                assertEquals(296.0, (it as MamlDataResult.Success).snapshot.values["safeLeft"])
            }
            geometry = RearDisplayGeometry(596, 976, RearDisplayInsets(0, 296, 0, 0), 320, 1)
            source.query(MamlDataRequest(emptyMap())) {
                val output = (it as MamlDataResult.Success).snapshot.values
                assertEquals(596.0, output["width"])
                assertEquals(976.0, output["height"])
                assertEquals(0.0, output["safeLeft"])
                assertEquals(296.0, output["safeTop"])
                assertEquals(320.0, output["densityDpi"])
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun providerFailuresAreExplicitAndPauseDuringReadSuppressesOldResult() {
        val missing = RearDisplayDataSource(context, RearDisplayGeometryProvider { null })
        val broken = RearDisplayDataSource(
            context,
            RearDisplayGeometryProvider { error("geometry unavailable") })
        try {
            missing.query(MamlDataRequest(emptyMap())) { assertTrue(it is MamlDataResult.Failure) }
            broken.query(MamlDataRequest(emptyMap())) {
                assertEquals(
                    "geometry unavailable",
                    (it as MamlDataResult.Failure).message
                )
            }
        } finally {
            missing.close(); broken.close()
        }
        lateinit var source: RearDisplayDataSource
        source = RearDisplayDataSource(context, RearDisplayGeometryProvider {
            source.onPause(); source.onResume()
            RearDisplayGeometry(900, 500)
        })
        try {
            var callbacks = 0
            source.query(MamlDataRequest(emptyMap())) { callbacks++ }
            assertEquals(0, callbacks)
        } finally {
            source.close()
        }
    }

    @Test
    fun configurationInvalidationCoalescesAndSubscriptionsDetachAcrossLifecycle() {
        val source = RearDisplayDataSource(
            context,
            RearDisplayGeometryProvider { RearDisplayGeometry(900, 500) })
        var callbacks = 0
        val configuration = Configuration(context.resources.configuration)
        try {
            val first = source.subscribeInvalidations { callbacks++ }
            val second = source.subscribeInvalidations { callbacks++ }
            context.onConfigurationChanged(configuration)
            context.onConfigurationChanged(configuration)
            main.idle()
            assertEquals(2, callbacks)
            context.onConfigurationChanged(configuration)
            source.onPause(); source.onResume(); main.idle()
            assertEquals(2, callbacks)
            first.close(); first.close()
            context.onConfigurationChanged(configuration); main.idle()
            assertEquals(3, callbacks)
            second.close()
            context.onConfigurationChanged(configuration); main.idle()
            assertEquals(3, callbacks)
            source.subscribeInvalidations { callbacks++ }
            context.onConfigurationChanged(configuration)
            source.close(); main.idle()
            context.onConfigurationChanged(configuration); main.idle()
            assertEquals(3, callbacks)
        } finally {
            source.close()
        }
    }
}
