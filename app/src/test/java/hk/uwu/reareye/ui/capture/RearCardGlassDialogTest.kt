package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.*
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.config.BusinessManagerScreen
import hk.uwu.reareye.ui.components.config.CardManagerScreen
import hk.uwu.reareye.ui.components.config.ConfigDashboardAction
import hk.uwu.reareye.ui.components.config.RearCardCreateDialog
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.widgetapi.IRearWidgetApiService
import hk.uwu.reareye.widgetapi.RearWidgetApiClient
import hk.uwu.reareye.widgetapi.RearAppApiClient
import hk.uwu.reareye.widgetapi.RearAppOperationResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real populated CardManager pages use production validation/persistence with memory preferences.
 * The differential test separately identifies its controlled, populated production-form fixture.
 * Native Robolectric pixels are not physical-device shader or keyboard certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearCardGlassDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var prefs: PrefsManager
    private val action = mutableStateOf<ConfigDashboardAction?>(null)
    private var pageBacks = 0
    private var embedded = false
    private var scenario = "unhosted"
    private fun geometry(phase: String, screenshot: Boolean = false) {
        runCatching {
            System.err.println("CARD_GEOMETRY scenario=$scenario phase=$phase pageBacks=$pageBacks action=${action.value}")
            val probes = listOf(
                "roots" to isRoot(), "editors" to hasSetTextAction(), "popup" to isPopup(),
                "actions" to (hasContentDescription(label(R.string.rear_widget_confirm)) or
                    hasContentDescription(label(R.string.rear_widget_cancel))),
            ) + listOf("rear-card-create-form", "rear-card-edit-dialog", "mochi-glass-sheet-host",
                "mochi-glass-sheet", "mixui-native-glass-sheet-host", "mixui-native-glass-sheet")
                .map { it to hasTestTag(it) }
            for ((name, matcher) in probes) {
                val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
                System.err.println("CARD_GEOMETRY $name count=${nodes.size}")
                nodes.forEach { node -> System.err.println("CARD_GEOMETRY $name id=${node.id} " +
                    "clipped=${node.boundsInRoot} root=${node.positionInRoot} screen=${node.positionOnScreen} size=${node.size} " +
                    "scroll=${node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)} " +
                    "focused=${node.config.getOrNull(SemanticsProperties.Focused)}") }
            }
            if (screenshot) capture("failure-$scenario-$phase")
        }.onFailure { System.err.println("CARD_GEOMETRY diagnostic failed: $it") }
    }
    private inline fun <T> inspectFailure(phase: String, block: () -> T): T = try { block() }
        catch (failure: AssertionError) { geometry(phase, screenshot = true); throw failure }
    private val cards = (0..7).map { index -> RearCardConfig(
        id = "card-$index", title = if (index == 0) "Calendar agenda" else "Existing card $index",
        packageName = "fixture.target", business = "clock.$index", priority = 600 - index,
    ) }
    private val businesses = (0..7).map { index -> RearBusinessConfig(
        id = "business-$index", packageName = "fixture.target", business = "clock.$index",
        filePath = "/fixture/clock-$index", defaultPriority = 600 - index,
    ) }

    @Before fun enabled() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(15) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun editor(tag: String) = compose.onNode(hasSetTextAction() and
        (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        compose.mainClock.autoAdvance = true
        return try { performScrollTo() } finally { compose.mainClock.autoAdvance = false }
    }
    private fun replace(tag: String, value: String) { editor(tag).reveal().performTextReplacement(value); settle() }
    private fun button(kind: String, confirm: Boolean) = compose.onNodeWithContentDescription(
        label(if (confirm) R.string.rear_widget_confirm else R.string.rear_widget_cancel))
    private fun assertEditorSheet(tag: String) = inspectFailure("display-$tag") {
        compose.onNodeWithTag(tag).assertIsDisplayed().assert(hasScrollAction())
        compose.onNode(isDialog()).assertDoesNotExist()
    }
    private fun captureFormEnds(prefix: String, kind: String, firstTag: String, lastTag: String) = inspectFailure("ends-$prefix") {
        scenario = prefix
        geometry("before-scroll-to-top")
        val viewport = compose.onNodeWithTag(if (kind == "create") "rear-card-create-form" else "rear-card-edit-dialog")
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, -10_000f) }
        settle()
        geometry("after-scroll-to-top")
        val actions = button(kind, true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        capture("$prefix-top")
        compose.onNodeWithTag(lastTag).reveal(); settle()
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, 10_000f) }
        settle()
        button(kind, false).assertIsDisplayed()
        assertEquals("Save remains fixed while the body scrolls", actions,
            button(kind, true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        capture("$prefix-bottom")
        editor(firstTag).reveal(); settle()
    }
    private fun back() {
        geometry("before-back")
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; settle()
        geometry("after-back")
    }
    private fun awaitClosed(kind: String) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag(if (kind == "create") "rear-card-create-form" else "rear-card-edit-dialog").fetchSemanticsNodes().isEmpty()
        }
        settle()
        compose.onNodeWithTag("CardManagerScreen-list").assertExists()
    }
    private fun host(backend: String, dark: Boolean, isEmbedded: Boolean = false,
        initialCards: List<RearCardConfig> = cards) {
        embedded = isEmbedded
        scenario = "$backend-$dark-$isEmbedded"
        prefs = PrefsManager(GuideMemoryPrefs()).also {
            it.putString(ConfigKeys.REAR_WIDGET_CARD_DATA, RearWidgetConfigCodec.encodeCards(initialCards))
            it.putString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA, RearWidgetConfigCodec.encodeBusinesses(businesses))
        }
        compose.runOnUiThread {
            activity?.close(); action.value = null; pageBacks = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    BackHandler { pageBacks++ }
                    CardManagerScreen(prefs, { pageBacks++ }, embedded = embedded,
                        actionRequest = action.value, onActionHandled = { action.value = null })
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag("rear-card-item-card-0").fetchSemanticsNodes().isNotEmpty()
        }
        settle()
    }
    private fun openEdit() {
        compose.onNodeWithTag("CardManagerScreen-list").performScrollToKey("card-0"); settle()
        compose.onNodeWithText("Calendar agenda").performTouchInput { click(center) }; settle()
        editor("rear-card-edit-title").assertExists()
        assertEditorSheet("rear-card-edit-dialog")
    }
    private fun openCreate() {
        if (embedded) compose.runOnUiThread { action.value = ConfigDashboardAction.ADD_CARD }
        else compose.onNodeWithContentDescription(label(R.string.rear_widget_add_card)).performTouchInput { click(center) }
        settle()
        assertEditorSheet("rear-card-create-form")
    }
    private fun capture(name: String): Bitmap {
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val dir = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"), "card-create-edit-glass")
        check(dir.mkdirs() || dir.isDirectory)
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }
    private fun assertActionRow(kind: String) {
        button(kind, true).assertIsDisplayed(); settle()
        val cancel = button(kind, false).fetchSemanticsNode().boundsInRoot
        val confirm = button(kind, true).fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.top, confirm.top, 1f)
        assertTrue("Cancel precedes Confirm on one row", cancel.right <= confirm.left)
    }

        @Test fun miuixSheetUsesJoinedGlassTabsAndPreservesDraftsAcrossModes() {
            val folder = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"),
                "sheet-glass-controls-3a980f7c/ACTUAL-PAGES").apply { mkdirs() }
            for (dark in listOf(false, true)) {
                host("miuix", dark); openCreate()
                val theme = if (dark) "dark" else "light"
            val inSheet = hasAnyAncestor(hasTestTag("mixui-native-glass-sheet"))
            val track = compose.onNode(hasTestTag("glass-tabs-miuix") and inSheet, useUnmergedTree = true)
            track.assertIsDisplayed()
            compose.onNode(hasTestTag("tabs-miuix") and inSheet, useUnmergedTree = true).assertDoesNotExist()
            fun tab(res: Int) = compose.onNode(hasText(label(res)) and inSheet and
                hasAnyAncestor(hasTestTag("glass-tabs-miuix")))
                fun capturePage(step: String) {
                    compose.onNodeWithTag("rear-card-create-form").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
                    settle()
                    track.assertIsDisplayed()
                    // Infer the 40dp material from the vendor's 34dp selectable slots plus
                    // its 3dp rim, independent of whether the bounds tag includes outer padding.
                    val slots = compose.onAllNodes(inSheet and hasAnyAncestor(hasTestTag("glass-tabs-miuix")) and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected), useUnmergedTree = true)
                        .fetchSemanticsNodes().map { it.boundsInWindow }
                    assertEquals("Actual page keeps one joined two-tab track", 2, slots.size)
                    slots.forEach { assertEquals("Halo padding must not enlarge the touch/slider slots", 34f, it.height, 1f) }
                    // The caller's first testTag on the same layout node wins over the shared body tag.
                    val viewport = compose.onNodeWithTag("rear-card-create-form", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInWindow
                    val materialTop = slots.minOf { it.top } - 3f
                    val materialLeft = slots.minOf { it.left } - 3f
                    val materialRight = slots.maxOf { it.right } + 3f
                    assertEquals("Original native 352dp track width must not shrink for its shadow", 352f,
                        materialRight - materialLeft, 1f)
                    assertEquals("Original 24dp left position must be preserved", 24f, materialLeft, 1f)
                    assertEquals("Original 24dp right position must be preserved", 376f, materialRight, 1f)
                    assertEquals("Track must retain its full original content width", viewport.width,
                        materialRight - materialLeft, 1f)
                    assertEquals("Track must not gain vertical padding or move from its original first-row inset",
                        8f, materialTop - viewport.top, 1f)
                    val firstEditor = compose.onNodeWithTag(if (step == "app")
                        "rear-card-create-app-title" else "rear-card-create-title", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInWindow
                    assertEquals("Original input width must stay aligned with the unchanged tab", materialLeft,
                        firstEditor.left, 1f)
                    assertEquals("Original input width must stay aligned with the unchanged tab", materialRight,
                        firstEditor.right, 1f)
                    assertEquals("Original 12dp tab-to-editor gap must not change", 12f,
                        firstEditor.top - (materialTop + 40f), 1f)
                    val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                    File(folder, "$theme-$step.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    if (step == "app") {
                        val selector = compose.onNodeWithTag("rear-card-business-selector")
                            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                        val x = selector.center.x.roundToInt()
                        val y = (selector.bottom - 8).roundToInt()
                        val pixel = bitmap.getPixel(x, y)
                        System.err.println("ACTUAL_SPINNER bounds=$selector sampled=${Integer.toHexString(pixel)}")
                        val expected = if (dark) 0x24 else 0xFF
                        for (channel in listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel),
                            android.graphics.Color.blue(pixel))) {
                            assertEquals("Actual app-card selector must visibly paint its native content surface",
                                expected.toFloat(), channel.toFloat(), 3f)
                        }
                    }
                    bitmap.recycle()
                }
                tab(R.string.rear_widget_card_tab_normal).assertIsSelected()
                replace("rear-card-create-title", "Normal mode draft")
                button("create", true).assertIsNotEnabled()
                capturePage("normal")
                tab(R.string.rear_widget_card_tab_app).performTouchInput { click(center) }; settle()
                tab(R.string.rear_widget_card_tab_app).assertIsSelected()
                tab(R.string.rear_widget_card_tab_normal).assertIsNotSelected()
                replace("rear-card-create-app-title", "App mode draft")
                button("create", true).assertIsEnabled()
                // The same-window glass selector must remain interactive after the canvas clip.
                compose.onNodeWithTag("rear-card-business-selector").performTouchInput { click(center) }
                settle()
                compose.onNodeWithText("clock.1").assertIsDisplayed().performTouchInput { click(center) }
                settle()
                fun assertSelectedBusiness() {
                    assertTrue("Native spinner must paint its updated selection after mode changes",
                        compose.onAllNodes(hasText("clock.1") and
                            hasAnyAncestor(hasTestTag("rear-card-business-selector")), useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty())
                }
                assertSelectedBusiness()
                capturePage("app")
                tab(R.string.rear_widget_card_tab_normal).performTouchInput { click(center) }; settle()
                editor("rear-card-create-title").assertTextContains("Normal mode draft")
                tab(R.string.rear_widget_card_tab_normal).assertIsSelected()
                button("create", true).assertIsNotEnabled()
                capturePage("normal-return")
                tab(R.string.rear_widget_card_tab_app).performClick(); settle()
                editor("rear-card-create-app-title").assertTextContains("App mode draft")
                assertSelectedBusiness()
                button("create", false).performClick(); awaitClosed("create")
                assertEquals(cards, RearWidgetManagerRepository.loadCards(prefs))
                assertEquals(0, pageBacks)
            }
        }

    @Test fun actualCardPageEditPreservesValidationCancellationAndSingleSave() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            for (isEmbedded in listOf(false, true)) {
                host(backend, dark, isEmbedded); openEdit()
                val prefix = "$backend-${if (dark) "dark" else "light"}-${if (embedded) "embedded" else "standalone"}"
                captureFormEnds("$prefix-edit", "edit", "rear-card-edit-title", "rear-card-edit-priority")
                replace("rear-card-edit-title", "Discard this")
                replace("rear-card-edit-package", "")
                button("edit", true).performTouchInput { click(center) }; settle()
                compose.onNodeWithTag("rear-card-edit-dialog").assertExists()
                assertEquals(cards, RearWidgetConfigCodec.parseCards(prefs.getString(ConfigKeys.REAR_WIDGET_CARD_DATA)))
                replace("rear-card-edit-package", "fixture.target")
                assertActionRow("edit")
                button("edit", true).performTouchInput { down(center); advanceEventTime(32); cancel() }; settle()
                assertEquals(cards, RearWidgetConfigCodec.parseCards(prefs.getString(ConfigKeys.REAR_WIDGET_CARD_DATA)))
                button("edit", false).performTouchInput { click(center) }; awaitClosed("edit")
                openEdit(); editor("rear-card-edit-title").assertTextContains("Calendar agenda")
                replace("rear-card-edit-title", "Trip agenda")
                button("edit", true).assertIsDisplayed()
                val submit = button("edit", true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                compose.runOnUiThread { submit(); submit() }
                awaitClosed("edit")
                compose.waitUntil(10_000) {
                    RearWidgetConfigCodec.parseCards(prefs.getString(ConfigKeys.REAR_WIDGET_CARD_DATA))
                        .any { it.id == "card-0" && it.title == "Trip agenda" }
                }
                val saved = RearWidgetConfigCodec.parseCards(prefs.getString(ConfigKeys.REAR_WIDGET_CARD_DATA))
                assertEquals(8, saved.size)
                assertEquals("Trip agenda", saved.single { it.id == "card-0" }.title)
                capture("$prefix-edit-saved")
                assertEquals(0, pageBacks)
            }
        }
    }

    @Test fun lockedCardSheetChangesOnlyManualPriorityAndCancelPreservesIt() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            val locked = cards.first().copy(renameable = false)
            host(backend, dark, initialCards = listOf(locked) + cards.drop(1))
            fun openLocked() {
                compose.onNodeWithText(locked.title).performClick(); settle()
                assertEditorSheet("rear-card-edit-dialog")
                editor("rear-card-edit-title").assertDoesNotExist()
                editor("rear-card-edit-package").assertDoesNotExist()
                editor("rear-card-edit-business").assertDoesNotExist()
            }
            fun manualPriority(value: String) {
                compose.onNodeWithText(label(R.string.rear_widget_priority_mode_auto)).reveal().performClick(); settle()
                replace("rear-card-edit-priority", value)
            }
            openLocked(); manualPriority("321")
            capture("$backend-$dark-locked-priority")
            button("edit", false).performClick(); awaitClosed("edit")
            assertEquals(locked, RearWidgetManagerRepository.loadCards(prefs).single { it.id == locked.id })
            openLocked(); manualPriority("321")
            button("edit", true).performClick(); awaitClosed("edit")
            compose.waitUntil(10_000) { RearWidgetManagerRepository.loadCards(prefs).single { it.id == locked.id }.priority == 321 }
            assertEquals("Locked content is immutable apart from priority", locked.copy(priority = 321),
                RearWidgetManagerRepository.loadCards(prefs).single { it.id == locked.id })
        }
    }

    @Test fun actualCardPageCreateKeepsAutocompleteFocusBackBoundsAndReopen() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark); openCreate()
            captureFormEnds("$backend-${if (dark) "dark" else "light"}-create", "create",
                "rear-card-create-title", "rear-card-create-priority")
            button("create", true).assertIsNotEnabled()
            editor("rear-card-business-input").reveal().performClick(); settle()
            replace("rear-card-business-input", "clock.")
            editor("rear-card-business-input").assertIsFocused()
            compose.onNode(isPopup()).assertIsDisplayed()
            val form = compose.onNodeWithTag("rear-card-create-form").fetchSemanticsNode()
            val popup = compose.onNode(isPopup()).fetchSemanticsNode()
            assertTrue(popup.positionOnScreen.y >= form.positionOnScreen.y)
            assertTrue(popup.positionOnScreen.y + popup.size.height <= form.positionOnScreen.y + form.size.height)
            capture("$backend-$dark-real-create-suggestions")
            back()
            compose.onNode(isPopup()).assertDoesNotExist()
            assertEditorSheet("rear-card-create-form")
            assertEquals(0, pageBacks)
            replace("rear-card-business-input", "clock.7")
            compose.onNode(hasText("clock.7") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .assertIsDisplayed().performTouchInput { click(center) }; settle()
            // Automatic priority is read-only; disabled native fields expose value
            // semantics without a SetText action.
            compose.onNode(hasText("593") and
                (hasTestTag("rear-card-create-priority") or hasAnyAncestor(hasTestTag("rear-card-create-priority"))),
                useUnmergedTree = true).assertIsDisplayed()
            replace("rear-card-create-title", "New calendar")
            assertActionRow("create")
            capture("$backend-$dark-real-create-ready")
            button("create", true).performTouchInput { click(center) }; awaitClosed("create")
            compose.waitUntil(10_000) { RearWidgetManagerRepository.loadCards(prefs).size == 9 }
            assertEquals("clock.7", RearWidgetManagerRepository.loadCards(prefs).single { it.title == "New calendar" }.business)
            openCreate()
            editor("rear-card-create-title").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            replace("rear-card-create-title", "Cancelled card")
            button("create", false).performTouchInput { click(center) }; awaitClosed("create")
            assertEquals(9, RearWidgetManagerRepository.loadCards(prefs).size)
            openCreate()
            compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("rear-card-create-form")), useUnmergedTree = true)
                .performTouchInput { click(Offset(2f, 2f)) }
            awaitClosed("create")
            openCreate(); back(); awaitClosed("create")
            assertEquals(0, pageBacks)
        }
    }

    @Test fun actualAppCreateFailureRetainsDraftAndRejectsRepeatedSubmit() {
        val clientField = RearAppCardRepository::class.java.getDeclaredField("remoteClient").apply { isAccessible = true }
        val oldClient = clientField.get(RearAppCardRepository)
        val widgetClientField = RearWidgetManagerRepository::class.java.getDeclaredField("remoteClient").apply { isAccessible = true }
        val oldWidgetClient = widgetClientField.get(RearWidgetManagerRepository)
        val widgetRemoteField = RearWidgetApiClient::class.java.getDeclaredField("remote").apply { isAccessible = true }
        try {
            for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
                val requests = AtomicInteger()
                val refreshedBusinesses = AtomicInteger()
                // Registration first refreshes widget definitions. Fake that IPC too:
                // a real disconnected widget client waits 4 seconds for each bind,
                // so a fake app client alone never reaches the failure under test.
                widgetClientField.set(RearWidgetManagerRepository, RearWidgetApiClient().also { client ->
                    widgetRemoteField.set(client, object : IRearWidgetApiService.Default() {
                        override fun registerBusinessFile(business: String, filePath: String) {
                            refreshedBusinesses.incrementAndGet()
                        }
                    })
                })
                clientField.set(RearAppCardRepository, object : RearAppApiClient() {
                    override fun bind(context: Context, onConnected: (() -> Unit)?): Boolean = true
                    override fun getCatalog(): Bundle = Bundle()
                    override fun registerAppCard(title: String, componentBusiness: String): Bundle {
                        requests.incrementAndGet()
                        return RearAppOperationResult(success = false, error = "Fixture registration failure").toBundle()
                    }
                })
                host(backend, dark); openCreate()
                compose.onAllNodesWithText(label(R.string.rear_widget_card_tab_app)).onLast().performClick(); settle()
                replace("rear-card-create-app-title", "Keep this draft")
                capture("$backend-$dark-app-mode-top")
                compose.onAllNodesWithText(label(R.string.rear_widget_card_tab_normal)).onLast().performClick(); settle()
                replace("rear-card-create-title", "Normal mode draft")
                captureFormEnds("$backend-$dark-normal-mode", "create",
                    "rear-card-create-title", "rear-card-create-priority")
                compose.onAllNodesWithText(label(R.string.rear_widget_card_tab_app)).onLast().performClick(); settle()
                editor("rear-card-create-app-title").assertTextContains("Keep this draft")
                button("create", true).assertIsDisplayed()
                val initialRefreshes = refreshedBusinesses.get()
                val submit = button("create", true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                compose.runOnUiThread { submit(); submit() }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeBy(100)
                    requests.get() == 1
                }; settle()
                assertTrue("Submit must execute the production widget refresh before registering",
                    refreshedBusinesses.get() >= initialRefreshes + businesses.size)
                assertEquals("Repeated submit must issue one app registration", 1, requests.get())
                editor("rear-card-create-app-title").assertTextContains("Keep this draft")
                button("create", true).assertIsEnabled()
                capture("$backend-$dark-app-create-failed")
                button("create", true).performTouchInput { click(center) }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeBy(100)
                    requests.get() == 2
                }; settle()
                editor("rear-card-create-app-title").assertTextContains("Keep this draft")
                button("create", false).performClick(); awaitClosed("create")
                assertEquals(8, RearWidgetManagerRepository.loadCards(prefs).size)
            }
        } finally {
            compose.runOnUiThread { activity?.close(); activity = null }
            clientField.set(RearAppCardRepository, oldClient)
            widgetClientField.set(RearWidgetManagerRepository, oldWidgetClient)
        }
    }

    @Test fun actualBusinessPageCardShortcutUsesTheSameGlassHost() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            for (isEmbedded in listOf(false, true)) {
                prefs = PrefsManager(GuideMemoryPrefs()).also {
                    it.putString(ConfigKeys.REAR_WIDGET_CARD_DATA, RearWidgetConfigCodec.encodeCards(cards))
                    it.putString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA, RearWidgetConfigCodec.encodeBusinesses(businesses))
                }
                compose.runOnUiThread {
                    activity?.close()
                    activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                    activity!!.get().setContent {
                        CaptureTheme(backend, dark) { BusinessManagerScreen(prefs, {}, embedded = isEmbedded) }
                    }
                }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeBy(100)
                    compose.onAllNodesWithContentDescription(label(R.string.rear_widget_add_card)).fetchSemanticsNodes().isNotEmpty()
                }
                settle()
                repeat(2) {
                    compose.onAllNodesWithContentDescription(label(R.string.rear_widget_add_card)).onFirst()
                        .performTouchInput { click(center) }; settle()
                    editor("rear-card-create-title").assertTextContains("clock.0")
                    assertActionRow("create")
                    captureFormEnds("$backend-$dark-business-$isEmbedded-shortcut-$it", "create",
                        "rear-card-create-title", "rear-card-create-priority")
                    button("create", false).performTouchInput { click(center) }; settle()
                    compose.onNodeWithTag("rear-card-create-form").assertDoesNotExist()
                    assertEquals(8, RearWidgetManagerRepository.loadCards(prefs).size)
                }
            }
        }
    }

    @Test fun populatedCreateFormSamplesBodyWithoutItsOwnForeground() {
        val title = mutableStateOf("Short title")
        val body = mutableStateOf(Color(0xFF202020))
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            compose.runOnUiThread {
                activity?.close(); title.value = "Short title"; body.value = Color(0xFF202020)
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme(backend, dark) {
                        GlassPreferenceScene(overlayContent = {
                            RearCardCreateDialog(true, 0, {}, businesses, title.value, { title.value = it },
                                "fixture.target", {}, "clock.0", {}, "600", {}, true, {}, true, {},
                                "App card", {}, 0, {}, {}, {}, {})
                        }) {
                            Column(Modifier.fillMaxSize().background(body.value).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                cards.forEach { card -> ModuleStyleManagerCard(
                                    modifier = Modifier.fillMaxWidth(), title = card.title,
                                    backgroundColor = body.value, summaryLines = listOf(card.business), badges = emptyList(),
                                    leftAction = {}, rightAction = {},
                                ) }
                            }
                        }
                    }
                }
            }
            settle()
            val first = compose.onNodeWithTag("rear-card-create-title").fetchSemanticsNode()
            val second = compose.onNodeWithTag("rear-card-create-package").fetchSemanticsNode()
            val origin = IntArray(2)
            compose.runOnUiThread { activity!!.get().window.decorView.getLocationOnScreen(origin) }
            val left = (first.positionOnScreen.x - origin[0] + 20).roundToInt()
            val right = left + first.size.width - 40
            val top = (first.positionOnScreen.y - origin[1] + first.size.height + 2).roundToInt()
            val bottom = (second.positionOnScreen.y - origin[1] - 2).roundToInt()
            assertTrue("Unobstructed gap between ordinary solid fields", right > left && bottom > top)
            fun diff(a: Bitmap, b: Bitmap, y1: Int = top, y2: Int = bottom): Double {
                var sum = 0L; var count = 0L
                for (y in y1 until y2) for (x in left until right) {
                    val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                    for (shift in listOf(0, 8, 16)) { sum += abs(((p shr shift) and 255) - ((q shr shift) and 255)); count++ }
                }
                return sum.toDouble() / count
            }
            val prefix = "$backend-$dark-populated-form-source"
            val initial = capture("$prefix-initial")
            compose.runOnUiThread { title.value = "MMMMMMMMMMMMMMMMMMMMMM" }; settle()
            val edited = capture("$prefix-foreground-changed")
            compose.runOnUiThread { body.value = Color(0xFF90D0F0) }; settle()
            val changed = capture("$prefix-body-changed")
            settle(); val stable = capture("$prefix-stable")
            val foreground = diff(initial, edited)
            val response = diff(edited, changed)
            val drift = diff(changed, stable)
            val fieldTop = (first.positionOnScreen.y - origin[1]).roundToInt()
            val fieldChanged = diff(initial, edited, fieldTop, fieldTop + first.size.height)
            println("CARD_FORM_SAMPLING $prefix field=$fieldChanged foreground=$foreground body=$response drift=$drift")
            assertTrue("Foreground mutation must actually render", fieldChanged > 0.5)
            assertTrue("Foreground must not enter its own backdrop: $foreground", foreground < 1.5)
            assertTrue("Native glass responds to populated body: $response", response > 0.5)
            assertTrue("Settled native glass has no feedback: $drift", drift < 1.5)
        }
    }
}
