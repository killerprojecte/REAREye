package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.hardware.display.DisplayManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RearDisplayDensityTest {
    @Test
    fun densityIsAdditiveAndLegacyGeometryReportsUnknownRatherThanMainScreenMetrics() {
        val legacy =
            RearDisplayDataSource.snapshot(RearDisplayGeometry(900, 500)) as MamlDataResult.Success
        assertEquals(
            0.0,
            RearDisplayDataSource.schema.validate(legacy.snapshot).values["densityDpi"]
        )
        val real = RearDisplayDataSource.snapshot(
            RearDisplayGeometry(
                900,
                500,
                densityDpi = 320
            )
        ) as MamlDataResult.Success
        assertEquals(
            320.0,
            RearDisplayDataSource.schema.validate(real.snapshot).values["densityDpi"]
        )
        assertEquals(
            0.0,
            real.snapshot.values["safeLeft"]
        ) // Unknown geometry has no guessed cutout.
    }

    @Test
    fun realRearDisplayDensityUsesSelectedHostDisplay() {
        val context = RuntimeEnvironment.getApplication()
        val first = ShadowDisplayManager.addDisplay("w800dp-h500dp-mdpi")
        val host = ShadowDisplayManager.addDisplay("w900dp-h500dp-xhdpi")
        val manager = context.getSystemService(DisplayManager::class.java)
        val source = RearDisplayDataSource(context.createDisplayContext(manager.getDisplay(host)))
        try {
            source.query(MamlDataRequest(emptyMap())) { result ->
                assertEquals(
                    320.0,
                    (result as MamlDataResult.Success).snapshot.values["densityDpi"]
                )
            }
        } finally {
            source.close(); ShadowDisplayManager.removeDisplay(host); ShadowDisplayManager.removeDisplay(
                first
            )
        }
    }
}
