package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Parcel
import com.android.dex.Dex
import hk.uwu.reareye.hook.scopes.system.modules.AppEmbedTaskConfigurationAdapter
import hk.uwu.reareye.internal.appembed.AppEmbedInitialLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppEmbedInitialLayoutTest {
    @Test
    fun initialGeometrySurvivesThePendingIntentParcelBoundary() {
        val layout = AppEmbedInitialLayout(Rect(293, 0, 956, 596), 300)
        val intent = Intent("example.LAUNCH")
        layout.writeTo(intent)
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertEquals(
                layout,
                AppEmbedInitialLayout.readFrom(Intent.CREATOR.createFromParcel(parcel))
            )
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun densityIsCompleteAndDoesNotChangeOnTheLaterNativeTransaction() {
        val layout = AppEmbedInitialLayout(Rect(293, 0, 956, 596), 300)
        val initial = layout.densityOverride()
        val later = AppEmbedTaskDensityCalculator.calculate(layout.bounds, 300)
        assertEquals(300, initial.densityDpi)
        assertEquals(later.screenWidthDp, initial.screenWidthDp)
        assertEquals(later.screenHeightDp, initial.screenHeightDp)
        assertEquals(later.smallestScreenWidthDp, initial.smallestScreenWidthDp)
        assertEquals(0, Configuration(initial).updateFrom(layout.densityOverride()))
    }

    @Test
    fun omittedDensityPreservesDisplayInheritanceAndUnmarkedIntentsAreIgnored() {
        assertNull(AppEmbedInitialLayout.readFrom(Intent()))
        val override = AppEmbedInitialLayout(Rect(0, 0, 663, 596), 0).densityOverride()
        val display = Configuration().apply { densityDpi = 320; screenWidthDp = 488 }
        assertEquals(0, display.updateFrom(override))
        assertEquals(320, display.densityDpi)
        assertThrows(IllegalArgumentException::class.java) { AppEmbedInitialLayout(Rect(), 300) }
    }

    @Test
    fun systemServerAdapterProducesValidDexWithItsCallableEntryPoint() {
        val dex =
            Dex(AppEmbedTaskConfigurationAdapter.generateDex("hk.uwu.reareye.test.InitialTaskLayout"))
        assertEquals(1, dex.classDefs().count())
        assertTrue(dex.methodIds().any { dex.strings()[it.nameIndex] == "apply" })
    }
}
