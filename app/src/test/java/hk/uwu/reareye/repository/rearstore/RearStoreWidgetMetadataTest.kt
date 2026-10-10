package hk.uwu.reareye.repository.rearstore

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearStoreWidgetMetadataTest {
    private val gson = Gson()

    @Test
    fun installAsAppParsesSnakeCaseMetadataField() {
        val metadata = gson.fromJson(
            """{"type":"card","install_as_app":true}""",
            RearStoreWidgetMetadata::class.java,
        )

        assertTrue(metadata.shouldInstallAsApp())
    }

    @Test
    fun installAsAppDefaultsToFalse() {
        val metadata = gson.fromJson(
            """{"type":"card"}""",
            RearStoreWidgetMetadata::class.java,
        )

        assertFalse(metadata.shouldInstallAsApp())
    }

    @Test
    fun installAsAppOnlyAppliesToCardMetadata() {
        val metadata = gson.fromJson(
            """{"type":"notification","install_as_app":true}""",
            RearStoreWidgetMetadata::class.java,
        )

        assertFalse(metadata.shouldInstallAsApp())
    }
}
