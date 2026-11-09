package hk.uwu.reareye.ui.components

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.Card
import hk.uwu.reareye.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Offline field transactions, using real controls and native in-window form hosts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarySettingsFieldInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var value by mutableStateOf("")
    private val writes = mutableListOf<String>()
    private var label = R.string.mary_provider_endpoint

    @Before fun explicitUiRun() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    private fun frames() {
        repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    private fun host(backend: MixUiBackend, initial: String, password: Boolean = false, dark: Boolean = false) {
        compose.runOnUiThread {
            activity?.close()
            value = initial
            writes.clear()
            label = if (password) R.string.mary_provider_api_key else R.string.mary_provider_endpoint
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = backend, colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Scaffold { Column(Modifier.fillMaxSize()) {
                        Card {
                            MarySettingsField(value, { value = it; writes += it }, label, password)
                        }
                    } }
                }
            }
        }
        frames()
    }

    private fun open() {
        compose.onNodeWithText(activity!!.get().getString(label)).performClick()
        frames()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(System.getProperty("ui.capture.output"), "mary-settings-field")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onAllNodes(isRoot()).filter(hasAnyDescendant(hasSetTextAction()))
            .onFirst().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun button(resource: Int) {
        compose.onNodeWithText(activity!!.get().getString(resource)).performClick()
        frames()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun cancelDiscardsAndReopeningCopiesTheLatestParentValue() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "https://offline.example.invalid/v1", dark = dark)
            open()
            screenshot("${backend.name}-${if (dark) "dark" else "light"}")
            compose.onNode(hasSetTextAction()).performTextReplacement("not saved")
            compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
            button(R.string.rear_widget_cancel)
            compose.runOnIdle {
                assertEquals("https://offline.example.invalid/v1", value)
                assertEquals(emptyList<String>(), writes)
                value = "https://updated.example.invalid/v2"
            }
            frames()
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("https://updated.example.invalid/v2")
            compose.onNode(hasSetTextAction()).performTextReplacement("https://saved.example.invalid/v3")
            button(R.string.rear_widget_confirm)
            compose.runOnIdle { assertEquals(listOf("https://saved.example.invalid/v3"), writes) }
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("https://saved.example.invalid/v3")
            button(R.string.rear_widget_cancel)
            compose.runOnIdle { assertEquals(1, writes.size) }
        }
    }

    @Test fun secretSummaryStaysMaskedAndClearingRequiresConfirm() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "fake-offline-secret", password = true, dark = dark)
            compose.onNodeWithText("••••••", substring = true).assertIsDisplayed()
            compose.onNodeWithText("fake-offline-secret", substring = true).assertDoesNotExist()
            open()
            compose.onNode(hasSetTextAction()).performTextClearance()
            button(R.string.rear_widget_cancel)
            compose.runOnIdle { assertEquals("fake-offline-secret", value); assertEquals(0, writes.size) }
            open()
            compose.onNode(hasSetTextAction()).performTextClearance()
            button(R.string.rear_widget_confirm)
            compose.runOnIdle { assertEquals("", value); assertEquals(listOf(""), writes) }
        }
    }
}
