package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual production-page Compose rendering in Robolectric, not device integration evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealPageCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<UiCaptureActivity>? = null

    @Before fun requireExplicitMode() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun renderImplementedPagesAndSamplePressFrames() = renderPages(UiCaptureActivity.PAGES)

    @Test fun renderHomeAndStoreProductionFixtures() = renderPages(setOf("home-offline", "store-catalog"))

    private fun renderPages(pages: Set<String>) {
        val directory = File(System.getProperty("ui.capture.output"))
        for (backend in System.getProperty("ui.capture.backends", "miuix,mochi").split(",")) {
            for (page in pages.sorted()) {
                for (dark in listOf(false, true)) {
                    compose.mainClock.autoAdvance = false
                    compose.runOnUiThread {
                        activity?.close()
                        activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                            Intent().putExtra("page", page).putExtra("backend", backend).putExtra("dark", dark)).setup()
                    }
                    repeat(10) {
                        compose.mainClock.advanceTimeBy(250)
                        compose.waitForIdle()
                    }
                    if (page == "version-dialog") {
                        compose.onNodeWithText("Fixture target app", useUnmergedTree = true).assertIsDisplayed()
                    }
                    if (page == "home-offline") {
                        assertVisibleText("REAREye")
                        compose.onNodeWithText(activity!!.get().getString(hk.uwu.reareye.R.string.home_status_working), useUnmergedTree = true).assertIsDisplayed()
                        compose.onNodeWithText(activity!!.get().getString(hk.uwu.reareye.R.string.preset_pack_missing_title), useUnmergedTree = true).assertIsDisplayed()
                    }
                    if (page == "store-catalog") {
                        assertVisibleText(activity!!.get().getString(hk.uwu.reareye.R.string.store_navigation))
                        compose.onNodeWithText("Fixture Aurora Clock", useUnmergedTree = true).assertIsDisplayed()
                        compose.onNodeWithText("A readable clock for the rear display.", useUnmergedTree = true).assertIsDisplayed()
                    }
                    val editorTitle = when (page) {
                        "card-editor" -> hk.uwu.reareye.R.string.rear_widget_edit_card
                        "component-editor" -> hk.uwu.reareye.R.string.rear_widget_edit_business
                        else -> null
                    }
                    if (editorTitle != null) {
                        compose.onNodeWithText(activity!!.get().getString(editorTitle), useUnmergedTree = true).assertIsDisplayed()
                    }
                    if (page == "cards" && !java.lang.Boolean.getBoolean("ui.capture.baseline")) {
                        compose.onAllNodesWithTag("tabs-$backend", useUnmergedTree = true).assertCountEquals(2)
                        compose.onAllNodesWithTag("tabs-$backend", useUnmergedTree = true)[1].assertIsDisplayed()
                    }
                    val folder = File(directory, "$backend/${if (dark) "dark" else "light"}/$page").apply { mkdirs() }
                    save(folder, "initial.png")
                    if (!dark && page == "cards") {
                        val clickables = compose.onAllNodes(hasClickAction())
                        if (clickables.fetchSemanticsNodes().isNotEmpty()) {
                            clickables[0].performTouchInput { down(center) }
                            repeat(12) { index ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "pressed-${index.toString().padStart(2, '0')}.png")
                            }
                            clickables[0].performTouchInput { up() }
                            repeat(12) { index ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "released-${index.toString().padStart(2, '0')}.png")
                            }
                        }
                    }
                    if (!dark && page == "cards" && !java.lang.Boolean.getBoolean("ui.capture.baseline")) {
                        compose.onAllNodesWithTag("tabs-$backend", useUnmergedTree = true)[1].performTouchInput {
                            down(Offset(width * 0.75f, height * 0.5f))
                            up()
                        }
                        repeat(24) { index ->
                            compose.mainClock.advanceTimeBy(16)
                            save(folder, "tab-selection-${index.toString().padStart(2, '0')}.png")
                        }
                    }
                    if (page == "scene-routes-populated") {
                        compose.onRoot(useUnmergedTree = true).performTouchInput { swipeUp() }
                        repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
                        val sceneRows = compose.onAllNodesWithText("example.fixture", useUnmergedTree = true)
                        check(sceneRows.fetchSemanticsNodes().indices.any { sceneRows[it].isDisplayed() }) {
                            "No populated scene route is visible after scrolling"
                        }
                        save(folder, "scrolled.png")
                        if (!dark && !java.lang.Boolean.getBoolean("ui.capture.baseline")) {
                            val back = compose.onNodeWithContentDescription(
                                activity!!.get().getString(hk.uwu.reareye.R.string.scene_route_navigate_back)
                            )
                            back.assertIsDisplayed()
                            back.performTouchInput { down(center) }
                            repeat(12) { frame ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "glass-pressed-${frame.toString().padStart(2, '0')}.png")
                            }
                            back.performTouchInput { up() }
                            repeat(12) { frame ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "glass-released-${frame.toString().padStart(2, '0')}.png")
                            }
                        }
                    }
                    File(folder, "evidence.txt").writeText(
                        "Renderer: Robolectric 4.17 native graphics / Compose\n" +
                        "Fixture: $page; backend: $backend; dark: $dark\n" +
                        "Not a physical-device or full application integration test.\n" +
                        "Sampled frames require visual review before claiming an animation effect.\n")
                }
            }
        }
    }

    @Test fun marySettingsNativeTitleHostsRenderBothBackends() {
        for (backend in listOf("miuix", "mochi")) {
            for (dark in listOf(false, true)) {
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread {
                    activity?.close()
                    activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                        Intent().putExtra("page", "mary-settings-unconfigured")
                            .putExtra("backend", backend).putExtra("dark", dark)).setup()
                }
                repeat(10) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
                val title = activity!!.get().getString(hk.uwu.reareye.R.string.mary_settings_title)
                val titles = compose.onAllNodesWithText(title, useUnmergedTree = true)
                check(titles.fetchSemanticsNodes().indices.any { titles[it].isDisplayed() })
                compose.onNodeWithText(activity!!.get().getString(
                    hk.uwu.reareye.R.string.mary_settings_not_ready), useUnmergedTree = true).assertIsDisplayed()
                val folder = File(System.getProperty("ui.capture.output"),
                    "mary-native-title/$backend/${if (dark) "dark" else "light"}").apply { mkdirs() }
                save(folder, "initial.png")
            }
        }
    }

    @Test fun actualEditorFieldsAcceptChanges() {
        for (backend in System.getProperty("ui.capture.backends", "miuix,mochi").orEmpty().split(",")) {
            for (page in listOf("card-editor", "component-editor")) {
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread {
                    activity?.close()
                    activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                        Intent().putExtra("page", page).putExtra("backend", backend).putExtra("dark", false)).setup()
                }
                repeat(10) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
                if (page == "card-editor") {
                    compose.onNode(hasSetTextAction() and hasText("Fixture clock"))
                        .performTextReplacement("Edited fixture")
                    // The capture clock is manual: editing schedules recomposition and
                    // focus/label motion, so advance frames before checking visible state.
                    repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
                    compose.onNode(hasSetTextAction() and hasText("Edited fixture")).assertIsDisplayed()
                } else {
                    compose.onNode(hasSetTextAction() and hasText("/demo/clock.zip"))
                        .performTextReplacement("/demo/edited.zip")
                    repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
                    compose.onNode(hasSetTextAction() and hasText("/demo/edited.zip")).assertIsDisplayed()
                }
            }
        }
    }


    @Test fun mochiStylesRenderActualCardsAndKeepEditorState() {
        val directory = File(System.getProperty("ui.capture.output"), "mochi-styles")
        for (backend in listOf("mochi", "miuix")) {
            for (dark in listOf(false, true)) {
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread {
                    activity?.close()
                    activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                        Intent().putExtra("page", "cards").putExtra("backend", backend).putExtra("dark", dark)).setup()
                }
                repeat(10) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
                val originalColors = activity!!.get().themeSignature
                for (style in hk.uwu.mixui.MixMochiStyle.entries) {
                    compose.runOnUiThread { activity!!.get().mochiStyle = style }
                    repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
                    compose.onAllNodesWithTag("tabs-$backend", useUnmergedTree = true).assertCountEquals(2)
                    if (backend == "miuix") org.junit.Assert.assertEquals(originalColors, activity!!.get().themeSignature)
                    val folder = File(directory, "$backend/${style.name}/${if (dark) "dark" else "light"}/cards").apply { mkdirs() }
                    save(folder, "initial.png")
                }
                if (backend == "mochi") {
                    val context = activity!!.get()
                    val resources = context.resources
                    val pkg = resources.getResourcePackageName(hk.uwu.reareye.R.string.module_theme_mode)
                    val fonts = listOf("nothing_inter", "nothing_dot", "nothing_display").map { name ->
                        val id = resources.getIdentifier(name, "font", pkg)
                        check(id != 0) { "Missing font resource: $name" }
                        requireNotNull(androidx.core.content.res.ResourcesCompat.getFont(context, id))
                    }
                    val widths = fonts.map { font -> android.graphics.Paint().apply { typeface = font; textSize = 24f }.measureText("Nothing 0123456789") }
                    widths.drop(1).forEach { org.junit.Assert.assertEquals(widths.first(), it, 0.01f) }
                }
            }
        }
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                Intent().putExtra("page", "card-editor").putExtra("backend", "mochi")).setup()
        }
        repeat(10) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
        compose.onNode(hasSetTextAction() and hasText("Fixture clock")).performTextReplacement("Keep draft across themes")
        for (style in hk.uwu.mixui.MixMochiStyle.entries) {
            compose.runOnUiThread { activity!!.get().mochiStyle = style }
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            compose.onNode(hasSetTextAction() and hasText("Keep draft across themes")).assertIsDisplayed()
        }
    }


    @Test fun unifiedThemeChoicesKeepActualEditorDraftAcrossBackends() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                Intent().putExtra("page", "card-editor").putExtra("backend", "miuix")).setup()
        }
        repeat(10) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
        compose.onNode(hasSetTextAction() and hasText("Fixture clock")).performTextReplacement("Keep draft across backends")
        val directory = File(System.getProperty("ui.capture.output"), "unified-theme-switch")
        // Existing MIUIX light/dark and the new Mochi light/dark persisted choices.
        for (value in listOf(7, 8, 0, 1, 7)) {
            val mode = hk.uwu.reareye.ui.theme.AppThemeMode.fromValue(value)
            compose.runOnUiThread { activity!!.get().themeModeOverride = mode }
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            org.junit.Assert.assertEquals(mode.backend, activity!!.get().renderedBackend)
            compose.onNode(hasSetTextAction() and hasText("Keep draft across backends")).assertIsDisplayed()
            save(File(directory, value.toString()).apply { mkdirs() }, "editor.png")
        }
    }

    private fun assertRenderedColor(bitmap: Bitmap, rgb: Int, description: String) {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (listOf(0, 8, 16).all { shift ->
                    kotlin.math.abs(((pixel shr shift) and 255) - ((rgb shr shift) and 255)) <= 4
                }) count++
        }
        org.junit.Assert.assertTrue("$description: expected rendered #${rgb.toString(16)}, found $count pixels", count >= 3)
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        fun luminance(rgb: Int): Double {
            val channels = listOf(16, 8, 0).map { shift ->
                val value = ((rgb shr shift) and 255) / 255.0
                if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
        }
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    @Test fun sceneRouteGlassButtonsOpenDismissAndNavigateInBothDirections() {
        val directory = File(System.getProperty("ui.capture.output"), "scene-route-controls")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) for (rtl in listOf(false, true)) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                    Intent().putExtra("page", "scene-routes-populated").putExtra("backend", backend)
                        .putExtra("dark", dark).putExtra("rtl", rtl)).setup()
            }
            repeat(10) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
            val context = activity!!.get()
            val backText = context.getString(hk.uwu.reareye.R.string.scene_route_navigate_back)
            val addText = context.getString(hk.uwu.reareye.R.string.rear_widget_add_scene_route)
            val back = compose.onNode(hasClickAction() and (hasContentDescription(backText) or hasAnyDescendant(hasContentDescription(backText))))
            val add = compose.onNode(hasClickAction() and (hasContentDescription(addText) or hasAnyDescendant(hasContentDescription(addText))))
            back.assertIsDisplayed(); add.assertIsDisplayed()
            val backBounds = back.fetchSemanticsNode().boundsInRoot
            val addBounds = add.fetchSemanticsNode().boundsInRoot
            for (bounds in listOf(backBounds, addBounds)) {
                org.junit.Assert.assertTrue("Toolbar hit area $bounds", bounds.width >= 40f && bounds.height >= 40f)
                org.junit.Assert.assertTrue("Toolbar is within root $bounds", bounds.left >= 0f && bounds.right <= 400f && bounds.top >= 0f)
            }
            org.junit.Assert.assertEquals(backBounds.center.y, addBounds.center.y, 1f)
            org.junit.Assert.assertEquals(rtl, backBounds.center.x > addBounds.center.x)
            val folder = File(directory, "$backend/${if (dark) "dark" else "light"}/${if (rtl) "rtl" else "ltr"}").apply { mkdirs() }
            save(folder, "initial.png")
            add.performClick()
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            compose.onNode(hasText(addText) and !hasClickAction()).assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
            val dialogImage = compose.onNode(isRoot() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true).captureToImage().asAndroidBitmap()
            File(folder, "add-dialog.png").outputStream().use {
                check(dialogImage.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            // Inspect actual rendered controls, not just tokens: the native glass container
            // remains visible between them. Both renderers and both text directions are covered.
            val fill = if (dark) 0x303030 else 0xFFFFFF
            val label = if (dark) 0xDDDDDD else 0x515151
            val ink = if (dark) 0xFAFAFA else 0x242424
            val outline = if (dark) 0x999999 else 0x737373
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().indices.forEach { index ->
                val image = compose.onAllNodes(hasSetTextAction())[index].captureToImage().asAndroidBitmap()
                assertRenderedColor(image, fill, "field $index fill ($backend/$dark/$rtl)")
                assertRenderedColor(image, label, "field $index label ($backend/$dark/$rtl)")
                assertRenderedColor(image, outline, "field $index resting outline ($backend/$dark/$rtl)")
            }
            val cancel = compose.onNodeWithText(context.getString(hk.uwu.reareye.R.string.rear_widget_cancel))
            val cancelImage = cancel.captureToImage().asAndroidBitmap()
            assertRenderedColor(cancelImage, fill, "cancel fill ($backend/$dark/$rtl)")
            assertRenderedColor(cancelImage, ink, "cancel text ($backend/$dark/$rtl)")
            assertRenderedColor(cancelImage, outline, "cancel outline ($backend/$dark/$rtl)")
            org.junit.Assert.assertTrue("Label contrast", contrastRatio(label, fill) >= 4.5)
            org.junit.Assert.assertTrue("Content contrast", contrastRatio(ink, fill) >= 4.5)
            org.junit.Assert.assertTrue("Control outline contrast", contrastRatio(outline, fill) >= 3.0)
            // Blank submission must retain the form instead of the native action auto-dismiss.
            compose.onNodeWithText(context.getString(hk.uwu.reareye.R.string.rear_widget_confirm)).performClick()
            repeat(5) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("example.cancelled")
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            assertRenderedColor(compose.onAllNodes(hasSetTextAction())[0].captureToImage().asAndroidBitmap(),
                ink, "focused entered text ($backend/$dark/$rtl)")
            val filledDialog = compose.onNode(isRoot() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true)
                .captureToImage().asAndroidBitmap()
            File(folder, "filled-dialog.png").outputStream().use {
                check(filledDialog.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            cancel.performClick()
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText(context.getString(hk.uwu.reareye.R.string.rear_widget_badge_scene_route_count, 16)).assertIsDisplayed()
            add.performClick()
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
            compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            org.junit.Assert.assertEquals(0, context.backPressCount)
            add.performClick()
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            val fields = compose.onAllNodes(hasSetTextAction())
            fields.fetchSemanticsNodes().forEach {
                org.junit.Assert.assertEquals("Cancelled draft must not survive reopening", "",
                    it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            }
            listOf("example.saved", "saved_scene", "saved_widget").forEachIndexed { index, value ->
                fields[index].performTextReplacement(value)
                compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
            }
            compose.onNodeWithText(context.getString(hk.uwu.reareye.R.string.rear_widget_confirm)).performClick()
            repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText(context.getString(hk.uwu.reareye.R.string.rear_widget_badge_scene_route_count, 17)).assertIsDisplayed()
            back.performTouchInput { down(center) }
            repeat(8) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
            save(folder, "back-pressed.png")
            back.performTouchInput { up() }
            compose.waitForIdle()
            org.junit.Assert.assertEquals(1, context.backPressCount)
        }
    }

    @Test fun sceneSafeSideInsetsAndNativeGlassStates() {
        val directory = File(System.getProperty("ui.capture.output"), "scene-feedback-glass")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) for (rtl in listOf(false, true)) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                    Intent().putExtra("page", "scene-routes-populated").putExtra("backend", backend)
                        .putExtra("dark", dark).putExtra("rtl", rtl)).setup()
            }
            repeat(12) { compose.mainClock.advanceTimeBy(150); compose.waitForIdle() }
            compose.runOnUiThread {
                val bars = androidx.core.graphics.Insets.of(if (rtl) 0 else 48, 0, if (rtl) 48 else 0, 48)
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                    androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 24, 0, 0))
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), bars)
                        .setVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars(), true).build())
            }
            repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            val context = activity!!.get()
            val label = compose.onNodeWithText(context.getString(hk.uwu.reareye.R.string.rear_widget_scene_route_hint_title))
            label.assertIsDisplayed()
            val bounds = label.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Side inset must protect body content: $backend/$rtl $bounds",
                if (rtl) bounds.right <= 352f else bounds.left >= 48f)
            val folder = File(directory, "$backend/${if (dark) "dark" else "light"}/${if (rtl) "rtl" else "ltr"}").apply { mkdirs() }
            save(folder, "rest.png")
            compose.onRoot(useUnmergedTree = true).performTouchInput { swipeUp() }
            repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            save(folder, "scrolled.png")
            val back = compose.onNodeWithContentDescription(context.getString(hk.uwu.reareye.R.string.scene_route_navigate_back))
            back.assertIsDisplayed()
            back.performTouchInput { down(center) }
            repeat(8) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
            save(folder, "pressed.png")
            back.performTouchInput { up() }; compose.waitForIdle()
            org.junit.Assert.assertEquals(1, context.backPressCount)
        }
    }

    @Test fun aboutNativeHeadersKeepRoutesScrollingAndCallbacks() {
        val directory = File(System.getProperty("ui.capture.output"), "about-native-headers")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) for (rtl in listOf(false, true)) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                    Intent().putExtra("page", "about").putExtra("backend", backend)
                        .putExtra("dark", dark).putExtra("rtl", rtl)).setup()
            }
            fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
            fun visible(text: String): Boolean {
                val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
                return nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
            }
            fun rootItem(text: String, upwards: Boolean = true) {
                repeat(18) {
                    if (visible(text)) return
                    compose.onNodeWithTag("about-root-list").performTouchInput {
                        if (upwards) swipeUp() else swipeDown()
                    }
                    settle()
                }
                error("About item not reachable: $text")
            }
            settle()
            val context = activity!!.get()
            val folder = File(directory, "$backend/${if (dark) "dark" else "light"}/${if (rtl) "rtl" else "ltr"}").apply { mkdirs() }
            assertVisibleText("REAREye")
            save(folder, "initial.png")
            val preset = context.getString(hk.uwu.reareye.R.string.preset_pack_title)
            rootItem(preset)
            compose.onNodeWithText(preset).performClick()
            org.junit.Assert.assertEquals(1, context.aboutPresetOpenCount)
            val contributors = context.getString(hk.uwu.reareye.R.string.credits_contributors_title)
            rootItem(contributors)
            compose.onNodeWithText(contributors).performClick()
            settle()
            assertVisibleText("Fixture contributor 1")
            compose.onNodeWithTag("about-back").assertIsDisplayed()
            save(folder, "contributors.png")
            // First spacer is index 0. Reach the final long, wrapping row without
            // adding a nested scroll container or cutting its description short.
            compose.onNodeWithTag("about-secondary-list").performScrollToIndex(24)
            settle()
            assertVisibleText("Fixture contributor 24")
            save(folder, "contributors-scrolled.png")
            compose.onNodeWithTag("about-back").performClick()
            settle()
            compose.onNodeWithTag("about-root-list").assertIsDisplayed()
            val licenses = context.getString(hk.uwu.reareye.R.string.licenses_name)
            rootItem(licenses)
            compose.onNodeWithText(licenses).performClick()
            settle()
            val libraries = hk.uwu.reareye.utils.other.loadLibraries(context).libraries
            check(libraries.isNotEmpty())
            assertVisibleText(libraries.first().name)
            save(folder, "licenses.png")
            compose.onNodeWithTag("about-secondary-list").performScrollToIndex(libraries.lastIndex)
            settle()
            assertVisibleText(libraries.last().name)
            save(folder, "licenses-scrolled.png")
            compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
            settle()
            compose.onNodeWithTag("about-root-list").assertIsDisplayed()
            // Reopen after Back and exercise the native toolbar Back as well.
            rootItem(licenses)
            compose.onNodeWithText(licenses).performClick()
            settle()
            compose.onNodeWithTag("about-back").performClick()
            settle()
            rootItem(contributors, upwards = false)
            compose.onNodeWithText(contributors).performClick()
            settle()
            compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
            settle()
            compose.onNodeWithTag("about-root-list").assertIsDisplayed()
            File(folder, "evidence.txt").writeText(
                "Actual About production route tree with deterministic contributor data.\n" +
                "Robolectric native graphics; not physical-device GPU evidence.\n" +
                "Covers native header routes, long rows, bundled library list and preset callback.\n")
        }
    }

    /** Real CardManagerScreen editor, including its separate Android Dialog window. */
    @Test fun mochiCardEditorKeepsPageVisibleAcrossThemesAndReopen() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                Intent().putExtra("page", "cards").putExtra("backend", "mochi")).setup()
        }
        fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(150); compose.waitForIdle() } }
        settle()
        val context = activity!!.get()
        val folder = File(System.getProperty("ui.capture.output"), "mochi-card-dialog").apply { mkdirs() }
        val editorTitle = context.getString(hk.uwu.reareye.R.string.rear_widget_edit_card)
        val cancel = context.getString(hk.uwu.reareye.R.string.rear_widget_cancel)
        fun openEditor() {
            compose.onNodeWithText("Fixture clock").performClick()
            settle()
            compose.onNodeWithText(editorTitle, useUnmergedTree = true).assertIsDisplayed()
        }
        fun capture(name: String, expectedAlpha: Float) {
            val dialog = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            check(dialog.isShowing)
            check(dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0) {
                "Platform dim must not duplicate the animated Compose scrim"
            }
            val overlay = compose.runOnUiThread { captureDialogWindowBitmap(dialog) }
            // Above/outside the bottom form, this is the actual native-window scrim.
            val pixel = overlay.getPixel(4, 64)
            org.junit.Assert.assertEquals("Native dialog scrim opacity", expectedAlpha,
                android.graphics.Color.alpha(pixel) / 255f, 0.025f)
            File(folder, "$name-window.png").outputStream().use {
                check(overlay.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            // Compose's generic root screenshot resolves the Activity, not Dialog.
            // Composite the two real native-window captures for review of the visible page.
            val page = compose.onNode(isRoot() and hasAnyDescendant(androidx.compose.ui.test.hasTestTag("fixture-root")),
                useUnmergedTree = true).captureToImage().asAndroidBitmap()
            val combined = page.copy(Bitmap.Config.ARGB_8888, true)
            android.graphics.Canvas(combined).drawBitmap(overlay, 0f, 0f, null)
            File(folder, "$name.png").outputStream().use {
                check(combined.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        openEditor()
        compose.onNode(hasSetTextAction() and hasText("Fixture clock")).performTextReplacement("Unsaved card title")
        for ((value, label, alpha) in listOf(Triple(7, "light", 0.3f), Triple(8, "dark", 0.5f), Triple(7, "light-again", 0.3f))) {
            compose.runOnUiThread { context.themeModeOverride = hk.uwu.reareye.ui.theme.AppThemeMode.fromValue(value) }
            settle()
            compose.onNode(hasSetTextAction() and hasText("Unsaved card title")).assertIsDisplayed()
            capture(label, alpha)
        }
        compose.onNodeWithText(cancel).performClick()
        settle()
        check(!org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
        compose.onNodeWithText("Fixture clock").assertIsDisplayed()
        openEditor()
        compose.onNode(hasSetTextAction() and hasText("Fixture clock")).assertIsDisplayed()
        capture("reopened", 0.3f)
        compose.runOnUiThread { context.themeModeOverride = hk.uwu.reareye.ui.theme.AppThemeMode.MOCHI_DARK }
        settle()
        capture("reopened-dark", 0.5f)
        // Exercise Back dismissal separately from the explicit cancel action.
        compose.runOnUiThread {
            org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed()
        }
        settle()
        check(!org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
        openEditor()
        capture("reopened-after-back", 0.5f)
        compose.onNodeWithText(cancel).performClick()
        settle()
        check(!org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
        File(folder, "evidence.txt").writeText(
            "Production CardManagerScreen and OverlayDialog using in-memory guide data.\n" +
                "Maven Mochi 0.2.0; Robolectric native graphics, not physical-device GPU evidence.\n" +
                "Separate Activity and Dialog window images composited for review.\n" +
                "Checks light/dark/light while editing, retained draft, cancel/reopen and Back/reopen.\n")
    }

    private fun assertVisibleText(text: String) {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
        check(nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }) {
            "No visible production title: $text"
        }
    }

    private fun save(folder: File, name: String) {
        val image = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        if (name == "initial.png") {
            val colors = mutableSetOf<Int>()
            for (y in 32 until image.height step 8) for (x in 0 until image.width step 8) {
                colors.add(image.getPixel(x, y))
            }
            check(colors.size > 3) { "Blank fixture content: $folder" }
        }
        File(folder, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
