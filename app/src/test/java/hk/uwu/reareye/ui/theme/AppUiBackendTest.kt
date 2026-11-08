package hk.uwu.reareye.ui.theme

import hk.uwu.mixui.MixUiBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUiBackendTest {
    @Test fun stableStoredValuesSelectRealBackends() {
        assertEquals(MixUiBackend.Miuix, AppUiBackend.fromValue(0).backend)
        assertEquals(MixUiBackend.Mochi, AppUiBackend.fromValue(1).backend)
    }
    @Test fun unknownStoredValuesPreserveExistingMiuixDefault() {
        assertEquals(AppUiBackend.default, AppUiBackend.fromValue(-1))
        assertEquals(AppUiBackend.default, AppUiBackend.fromValue(999))
    }
}
