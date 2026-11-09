package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.FeatureGuideDemoContext
import hk.uwu.reareye.ui.FeatureGuideDemoState
import hk.uwu.reareye.ui.LocalFeatureGuideDemo
import hk.uwu.reareye.ui.components.config.BusinessManagerScreen
import hk.uwu.reareye.ui.components.config.CardManagerScreen
import hk.uwu.reareye.ui.components.config.ConfigDashboardAction
import hk.uwu.reareye.ui.components.config.RearWallpaperManagerScreen
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Actual manager composables with isolated guide data, not a device/runtime integration test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagerNativeTopBarTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var backClicks = 0
    private var handledActions = 0
    private val action = mutableStateOf<ConfigDashboardAction?>(null)

    @Before fun requireCaptureProfile() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() {
        repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }
    private fun open(page: String, backend: String, dark: Boolean, embedded: Boolean) {
        compose.runOnUiThread {
            activity?.close()
            action.value = null; handledActions = 0; backClicks = 0
            val fixture = FeatureGuideDemoState("Fixture clock").apply {
                cards = (1..16).map { cards.first().copy(id = "fixture-card-$it", title = "Fixture clock $it") }
                businesses = (1..16).map { businesses.first().copy(id = "fixture-business-$it", business = "fixture_widget_$it") }
            }
            fixture.prefsManager.putString(ConfigKeys.REAR_WIDGET_CARD_DATA, RearWidgetConfigCodec.encodeCards(fixture.cards))
            fixture.prefsManager.putString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA, RearWidgetConfigCodec.encodeBusinesses(fixture.businesses))
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    CompositionLocalProvider(LocalFeatureGuideDemo provides if (page == "RearWallpaperManagerScreen") FeatureGuideDemoContext(fixture, null, {}, {}) else null) {
                        val onHandled = { handledActions++; action.value = null }
                        when (page) {
                            "CardManagerScreen" -> CardManagerScreen(
                                prefsManager = fixture.prefsManager, onBack = { backClicks++ }, embedded = embedded,
                                contentPadding = PaddingValues(bottom = 24.dp), actionRequest = action.value,
                                onActionHandled = onHandled,
                            )
                            "BusinessManagerScreen" -> BusinessManagerScreen(
                                prefsManager = fixture.prefsManager, onBack = { backClicks++ }, embedded = embedded,
                                contentPadding = PaddingValues(bottom = 24.dp), actionRequest = action.value,
                                onActionHandled = onHandled,
                            )
                            else -> RearWallpaperManagerScreen(
                                prefsManager = fixture.prefsManager, onBack = { backClicks++ }, embedded = embedded,
                                contentPadding = PaddingValues(bottom = 24.dp), actionRequest = action.value,
                                onActionHandled = onHandled,
                            )
                        }
                    }
                }
            }
        }
        settle()
    }
    private fun text(res: Int) = activity!!.get().getString(res)
    private fun save(folder: File, name: String, dialog: Boolean = false) {
        folder.mkdirs()
        val bitmap = if (dialog) compose.runOnUiThread {
            captureDialogWindowBitmap(requireNotNull(ShadowDialog.getLatestDialog()))
        } else compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun nativeFormTag(page: String): String? = when (page) {
        "CardManagerScreen" -> "rear-card-create-form"
        else -> null
    }
    private fun formShowing(page: String): Boolean = nativeFormTag(page)?.let { tag ->
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    } ?: (ShadowDialog.getLatestDialog()?.isShowing == true)
    private fun dismissForm(page: String) {
        compose.runOnUiThread {
            if (nativeFormTag(page) != null) activity!!.get().onBackPressedDispatcher.onBackPressed()
            else requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed()
        }
        settle()
        assertFalse(formShowing(page))
    }

    @Test fun standaloneAndEmbeddedManagersKeepScrollingActionsAndForms() {
        val output = File(System.getProperty("ui.capture.output"), "manager-native-topbars")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            for (page in listOf("CardManagerScreen", "BusinessManagerScreen", "RearWallpaperManagerScreen")) {
                val addLabel = when (page) {
                    "CardManagerScreen" -> R.string.rear_widget_add_card
                    "BusinessManagerScreen" -> R.string.rear_widget_add_business
                    else -> R.string.rear_wallpaper_import
                }
                for (embedded in listOf(false, true)) {
                    open(page, backend, dark, embedded)
                    val folder = File(output, "$backend/${if (dark) "dark" else "light"}/$page/${if (embedded) "embedded" else "standalone"}")
                    val list = compose.onNodeWithTag("$page-list")
                    val back = compose.onNodeWithContentDescription(text(R.string.scene_route_navigate_back))
                    if (embedded) {
                        back.assertDoesNotExist()
                    } else {
                        back.assertIsDisplayed()
                        assertTrue("Body must supply pixels behind toolbar", list.fetchSemanticsNode().boundsInRoot.top < back.fetchSemanticsNode().boundsInRoot.bottom)
                    }
                    save(folder, "initial.png")
                    list.performTouchInput { swipeUp() }; settle()
                    val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                    if (page == "RearWallpaperManagerScreen") {
                        // The production guide deliberately contains one wallpaper: verify the
                        // short-list boundary rather than inventing scrolling that cannot occur.
                        assertEquals(0f, range.maxValue(), 0f)
                        assertEquals(0f, range.value(), 0f)
                    } else {
                        assertTrue("Populated manager must scroll: $page", range.value() > 0f)
                    }
                    save(folder, "scrolled.png")
                    if (embedded) {
                        compose.runOnUiThread {
                            action.value = when (page) {
                                "CardManagerScreen" -> ConfigDashboardAction.ADD_CARD
                                "BusinessManagerScreen" -> ConfigDashboardAction.ADD_COMPONENT
                                else -> ConfigDashboardAction.IMPORT_WALLPAPER
                            }
                        }
                        settle()
                        assertEquals(1, handledActions)
                    } else {
                        compose.onNodeWithContentDescription(text(addLabel)).performTouchInput { click() }; settle()
                    }
                    if (!formShowing(page)) {
                        save(folder, "after-action-no-dialog.png")
                        File(folder, "after-action-semantics.txt").writeText(compose.onAllNodes(isRoot()).printToString())
                    }
                    assertTrue("Add/import action opens existing form: $backend/$dark/$page/$embedded", formShowing(page))
                    if (nativeFormTag(page) != null) {
                        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                        File(folder, "form.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } else save(folder, "form.png", dialog = true)
                    dismissForm(page)
                    save(folder, "dismissed.png")
                    if (!embedded) {
                        // Reopen and cancel again to cover repeated toolbar dispatch after dismissal.
                        compose.onNodeWithContentDescription(text(addLabel)).performTouchInput { click() }; settle()
                        assertTrue(formShowing(page))
                        dismissForm(page)
                        if (page == "RearWallpaperManagerScreen") {
                            compose.onNodeWithContentDescription(text(R.string.rear_wallpaper_refresh)).performTouchInput { click() }; settle()
                        }
                        back.performTouchInput { click() }; settle()
                        assertEquals(1, backClicks)
                    }
                    File(folder, "evidence.txt").writeText(
                        "Production $page; $backend; dark=$dark; embedded=$embedded.\n" +
                        "Isolated memory preferences; wallpaper guide fixture; native Robolectric graphics API 36.\n" +
                        "Full-height populated card/business scroll; one-wallpaper short-list boundary; existing add/import form, Back dismissal, repeated actions and back callback.\n" +
                        "Embedded header absent and dashboard action consumed once.\n" +
                        "Not device shader certification or live repository/runtime verification.\n",
                    )
                }
            }
        }
    }
}
