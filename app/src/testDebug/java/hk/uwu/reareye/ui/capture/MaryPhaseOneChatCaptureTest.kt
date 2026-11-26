package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.components.BottomSheet
import hk.uwu.mixui.components.GlassBottomSheet
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.GlassBottomSheetSize
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.MaryAvailableModel
import hk.uwu.reareye.ui.components.MaryChatPreview
import hk.uwu.reareye.ui.components.MaryComposerModels
import hk.uwu.reareye.ui.components.MaryReasoningModelDropdown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryPhaseOneChatCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ui = MaryPhaseOneCapture(compose, "chat")

    @Before fun requireResources() = ui.requireResources()
    @After fun close() = ui.close()

    @Test fun miuixLightChat() = exercise(MixUiBackend.Miuix, dark = false)
    @Test fun miuixDarkChat() = exercise(MixUiBackend.Miuix, dark = true)
    @Test fun mochiLightChat() = exercise(MixUiBackend.Mochi, dark = false)
    @Test fun mochiDarkChat() = exercise(MixUiBackend.Mochi, dark = true)

    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        val importing = mutableStateOf(false)
        val agentMode = mutableStateOf(true)
        ui.open(backend, dark) {
            GlassBottomSheetScene { MaryChatPreview(importing = importing.value, agentMode = agentMode.value) }
        }
        ui.node("mary-composer-input", unmerged = true).performTouchInput { click() }
        ui.settle()
        val modelButton = ui.node("mary-reasoning-strength", unmerged = true).assertIsDisplayed()
        val header = ui.node("mary-reasoning-model-header", unmerged = true)
        val slider = ui.node("mary-reasoning-slider", unmerged = true)
        val approval = ui.node("mary-approval-mode", unmerged = true).assertIsDisplayed()
        header.assertDoesNotExist()
        ui.capture("01-chat")

        modelButton.performTouchInput { click() }
        ui.settle()
        slider.assertIsDisplayed()
        assertAlignedReasoningHeader()
        ui.capture("02-reasoning-panel")

        val initialRange = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range
        fun openModels() {
            header.assertIsDisplayed().performTouchInput { click() }
            ui.settle()
        }
        openModels()
        val flashRow = modelOption(backend, "DeepSeek v4.1 Flash", "DeepSeek")
        val flash = compose.onNode(flashRow)
        flash.assertIsDisplayed()
        // Mochi draws the check natively but does not expose selected semantics in 0.2.0.
        if (backend == MixUiBackend.Miuix) {
            flash.assertIsNotSelected()
            compose.onNode(modelOption(backend, "GPT 6.2 Sol", "OpenAI")).assertIsSelected()
        }
        slider.assertIsDisplayed()
        ui.capture("03-reasoning-model-list", includeExtraRoots = true)

        ui.back()
        flash.assertDoesNotExist()
        header.assertIsDisplayed()
        slider.assertIsDisplayed()
        openModels()
        // Dispatch within the menu's own Compose root (a separate full-window overlay in Mochi).
        menuRoot(flashRow).performTouchInput { click(Offset(centerX, 32f)) }
        ui.settle()
        flash.assertDoesNotExist()
        header.assertIsDisplayed()
        slider.assertIsDisplayed()

        openModels()
        flash.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        flash.assertDoesNotExist()
        header.assertIsDisplayed()
        ui.node("mary-reasoning-model-name", unmerged = true).assertTextEquals("DeepSeek v4.1 Flash")
        slider.assertIsDisplayed()

        val before = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertNotEquals("The picker immediately uses the selected model's reasoning levels", initialRange, before.range)
        val toEnd = before.current != before.range.endInclusive
        val target = if (toEnd) before.range.endInclusive else before.range.start
        slider.performTouchInput {
            val left = Offset(20f, centerY)
            val right = Offset(width - 20f, centerY)
            if (toEnd) swipe(left, right, durationMillis = 600) else swipe(right, left, durationMillis = 600)
        }
        ui.settle()
        assertProgress(slider, target)
        ui.capture("04-reasoning-flash-after-drag")
        ui.back()
        header.assertDoesNotExist()
        compose.onNodeWithText("DeepSeek v4.1 Flash").assertDoesNotExist()
        modelButton.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        assertProgress(slider, target)
        ui.back()
        header.assertDoesNotExist()
        compose.runOnIdle { agentMode.value = false }
        approval.assertDoesNotExist()
        modelButton.assertIsDisplayed().assertIsEnabled()

        val attach = ui.node("mary-composer-attach", unmerged = true)
        attach.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        val chooseFile = compose.onNode(hasText(ui.label(R.string.mary_attachment_choose_file)) and hasClickAction())
        val chooseImage = compose.onNode(hasText(ui.label(R.string.mary_attachment_choose_image)) and hasClickAction())
        chooseFile.assertIsDisplayed().assertIsEnabled()
        chooseImage.assertIsDisplayed().assertIsEnabled()
        ui.capture("05-attachment-menu", includeExtraRoots = true)
        chooseFile.performTouchInput { click() }
        ui.settle()
        chooseFile.assertDoesNotExist()
        ui.node("mary-draft-file-sample-file", unmerged = true).assertIsDisplayed()
        attach.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        chooseImage.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        chooseImage.assertDoesNotExist()
        ui.node("mary-draft-file-sample-image", unmerged = true).assertIsDisplayed()
        ui.capture("06-attachment-drafts")

        val send = ui.node("mary-composer-send", unmerged = true)
        compose.runOnIdle { importing.value = true }
        ui.node("mary-attachment-importing", unmerged = true).assertIsDisplayed()
        attach.assertIsNotEnabled()
        send.assertIsNotEnabled()
        compose.runOnIdle { importing.value = false }
        send.assertIsEnabled()

        compose.onNodeWithContentDescription(ui.label(R.string.mary_attachment_remove, "reference.png"))
            .assertIsDisplayed().performClick()
        ui.settle()
        ui.node("mary-draft-file-sample-image", unmerged = true).assertDoesNotExist()
        send.assertContentDescriptionEquals(ui.label(R.string.mary_action_send)).performClick()
        ui.settle()
        ui.node("mary-draft-file-sample-file", unmerged = true).assertDoesNotExist()
        ui.node("mary-sent-file-sample-file", unmerged = true).assertIsDisplayed()
        ui.node("mary-sent-file-sample-image", unmerged = true).assertDoesNotExist()
        ui.capture("07-sent-attachment")
    }

    @Test fun miuixModelListEdgeCases() = exerciseModelList(MixUiBackend.Miuix)
    @Test fun miuixDarkModelListEdgeCases() = exerciseModelList(MixUiBackend.Miuix, dark = true)
    @Test fun mochiModelListEdgeCases() = exerciseModelList(MixUiBackend.Mochi)

    @Test fun miuixModelDropdownWorksInLegacyWindowSheet() {
        val selected = mutableStateOf<String?>(null)
        val options = listOf(MaryAvailableModel("legacy-id", "独立宿主模型", "测试服务商"))
        ui.open(MixUiBackend.Miuix, dark = false) {
            BottomSheet(show = true, onDismissRequest = {}) {
                MaryReasoningModelDropdown(
                    MaryComposerModels(selected.value, "测试模型", options) { selected.value = it },
                    effort = null, enabled = true,
                )
            }
        }
        val header = ui.node("mary-reasoning-model-header", unmerged = true)
        header.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        val option = compose.onNode(modelOption(MixUiBackend.Miuix, "独立宿主模型", "测试服务商"))
        option.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        option.assertDoesNotExist()
        header.assertIsDisplayed()
        assertEquals("legacy-id", selected.value)
    }

    private fun exerciseModelList(backend: MixUiBackend, dark: Boolean = false) {
        val options = mutableStateOf(emptyList<MaryAvailableModel>())
        val selected = mutableStateOf<String?>(null)
        val selections = mutableListOf<String>()
        val extraSheetHeight = mutableIntStateOf(0)
        val pageBand = mutableStateOf<IntRange?>(null)
        val pageColor = mutableStateOf(Color(0xFFE85421))
        var pageY = 0f
        ui.open(backend, dark) {
            GlassBottomSheetScene {
                if (dark) {
                    Canvas(Modifier.fillMaxSize().onGloballyPositioned { pageY = it.positionInWindow().y }) {
                        // Paint the same base into the real page and its recorded backdrop.
                        drawRect(Color(0xFF557895))
                        pageBand.value?.let { band ->
                            drawRect(pageColor.value, Offset(0f, band.first - pageY), Size(size.width, band.count().toFloat()))
                        }
                    }
                }
                GlassBottomSheet(show = true, onDismissRequest = {}, size = GlassBottomSheetSize.Content) {
                    Column {
                        if (extraSheetHeight.intValue != 0) Spacer(Modifier.height(extraSheetHeight.intValue.dp))
                        MaryReasoningModelDropdown(
                            models = MaryComposerModels(selected.value, "测试模型", options.value) {
                                selections += it
                                selected.value = it
                            },
                            effort = null,
                            enabled = true,
                        )
                    }
                }
            }
        }
        val header = ui.node("mary-reasoning-model-header", unmerged = true)
        val emptyHint = hasText(ui.label(R.string.mary_chat_no_configured_model))
        header.assertIsDisplayed().assertIsNotEnabled().assertHasNoClickAction().performTouchInput { click() }
        ui.settle()
        compose.onNode(emptyHint).assertIsDisplayed().assertHasNoClickAction()
        assertEquals(emptyList<String>(), selections)

        // Mochi sizes its native viewport to the available space above the anchor;
        // 16 of its 42 dp glass rows can fit on this 800 dp device without scrolling.
        val extraCount = 30
        compose.runOnIdle {
            options.value = listOf(
                MaryAvailableModel("one", "同名模型", "服务商甲"),
                MaryAvailableModel("two", "同名模型", "服务商乙"),
            ) + (1..extraCount).map { MaryAvailableModel("extra-$it", "候选 $it", "测试服务商") }
            selected.value = "one"
        }
        header.assertIsEnabled().performTouchInput { click() }
        ui.settle()
        val first = compose.onNode(modelOption(backend, "同名模型", "服务商甲")).assertIsDisplayed()
        val second = compose.onNode(modelOption(backend, "同名模型", "服务商乙")).assertIsDisplayed()
        if (backend == MixUiBackend.Miuix) {
            first.assertIsSelected()
            second.assertIsNotSelected()
        }
        second.performTouchInput { click() }
        ui.settle()
        second.assertDoesNotExist()
        header.assertIsDisplayed()
        assertEquals(listOf("two"), selections)

        header.performTouchInput { click() }
        ui.settle()
        if (backend == MixUiBackend.Miuix) second.assertIsSelected()
        val anyExtra = hasClickAction() and hasText("测试服务商", substring = true)
        val modelRows = hasClickAction() and (hasText("服务商甲") or hasText("服务商乙") or hasText("测试服务商"))
        val fourth = compose.onNode(modelOption(backend, "候选 2", "测试服务商")).assertIsDisplayed()
        val from = fourth.fetchSemanticsNode().boundsInRoot.center
        val to = first.fetchSemanticsNode().boundsInRoot.center
        val tail = compose.onNode(modelOption(backend, "候选 $extraCount", "测试服务商"))
        tail.assertIsNotDisplayed()
        val beforeScroll = captureModelList("08-reasoning-model-list-overflow", backend, modelRows)
        val root = menuRoot(anyExtra)
        fun tailTouchPoint(): Offset? {
            if (!tail.isDisplayed()) return null
            val row = tail.fetchSemanticsNode()
            // The native list can compose a sliver past its edge. Require most of
            // the row to be visible and tap the clipped bounds, not its layout box.
            return row.boundsInRoot.takeIf { it.height >= row.size.height * 0.75f }?.center
        }
        var touchPoint = tailTouchPoint()
        for (swipe in 1..16) {
            if (touchPoint != null) break
            root.performTouchInput { swipe(from, to, durationMillis = 300) }
            ui.settle()
            touchPoint = tailTouchPoint()
        }
        val visibleCenter = checkNotNull(touchPoint) { "Touch scrolling did not reveal the last model" }
        val afterScroll = captureModelList("09-reasoning-model-list-after-scroll", backend, modelRows)
        root.performTouchInput { click(visibleCenter) }
        ui.settle()
        tail.assertDoesNotExist()
        header.assertIsDisplayed()
        assertEquals(listOf("two", "extra-$extraCount"), selections)
        if (beforeScroll != null && afterScroll != null) {
            beforeScroll.assertAligned()
            afterScroll.assertAligned()
            beforeScroll.assertStableAfterScroll(afterScroll)
            if (dark) {
                header.performTouchInput { click() }
                ui.settle()
                assertPageBandTracksWindow(modelRows, { band -> pageBand.value = band }) {
                    pageColor.value = Color(0xFF1885F1)
                }
                compose.runOnIdle {
                    pageBand.value = null
                    // Extend above the anchor so the drawer edge moves without a menu height change.
                    extraSheetHeight.intValue = 64
                }
                ui.settle()
                val taller = checkNotNull(captureModelList("12-reasoning-model-list-taller-sheet", backend, modelRows))
                assertTrue("The fixture must move the visible drawer edge: ${beforeScroll.panel} -> ${taller.panel}",
                    beforeScroll.panel.y - taller.panel.y >= 48)
                taller.assertAligned()
                ui.back()
                compose.onNode(anyExtra).assertDoesNotExist()
                header.assertIsDisplayed()
                assertEquals(listOf("two", "extra-$extraCount"), selections)
            }
        }
    }

    private data class Edge(val y: Double, val contrast: Double)

    private data class MenuSampling(
        val name: String,
        val panel: Edge,
        val menu: Edge,
        val sheetTop: Int,
        val rows: IntRange,
        val menuLuminance: DoubleArray,
    ) {
        fun assertAligned() {
            assertTrue("$name must show the actual sheet edge: geometry=$sheetTop, witness=$panel",
                panel.contrast >= 4 && abs(panel.y - sheetTop) <= 8)
            // Blur can spread the transition over many rows. Its half-amplitude point must still align.
            assertTrue("$name menu must sample the sheet edge in window coordinates: sheet=$panel, menu=$menu",
                menu.contrast >= 5 && abs(menu.y - panel.y) <= 24)
        }

        fun assertStableAfterScroll(after: MenuSampling) {
            val commonRows = maxOf(rows.first, after.rows.first)..minOf(rows.last, after.rows.last)
            val drift = commonRows.map { abs(menuLuminance[it] - after.menuLuminance[it]) }.average()
            assertTrue("Scrolling moves visible labels, not their own backdrop: mean material drift=$drift",
                drift <= maxOf(2.0, menu.contrast * .10))
        }
    }

    private data class SamplingGeometry(val sheet: Rect, val menu: Rect) {
        // Short fixture labels are on the left and the native selection mark on the far right.
        val menuStrip = (menu.left + menu.width * .72f).roundToInt()..(menu.left + menu.width * .78f).roundToInt()
        // The real panel edge is visible beside the menu, outside the corner and popup shadow.
        val witnessStrip = (menu.right + (sheet.right - menu.right) * .59f).roundToInt()..
            (menu.right + (sheet.right - menu.right) * .70f).roundToInt()
    }

    private fun samplingGeometry(row: SemanticsMatcher): SamplingGeometry {
        val sheet = ui.node("mixui-native-glass-sheet", unmerged = true).fetchSemanticsNode().boundsInWindow
        // Use the union of clipped row bounds, not an implementation-specific scroll node.
        val rows = compose.onAllNodes(row).fetchSemanticsNodes().map { it.boundsInWindow }
            .filter { it.height > 0 && it.width > 0 }
        check(rows.isNotEmpty()) { "No visible model rows for optical sampling" }
        return SamplingGeometry(sheet, Rect(rows.minOf { it.left }, rows.minOf { it.top },
            rows.maxOf { it.right }, rows.maxOf { it.bottom }))
    }

    private fun captureModelList(name: String, backend: MixUiBackend, row: SemanticsMatcher): MenuSampling? {
        var sampling: MenuSampling? = null
        ui.capture(name, includeExtraRoots = true) { bitmap ->
            if (backend == MixUiBackend.Miuix) {
                val geometry = samplingGeometry(row)
                val top = geometry.sheet.top.roundToInt()
                val rows = (geometry.menu.top + 18).roundToInt()..(geometry.menu.bottom - 18).roundToInt()
                check(top - 28 in rows && top + 28 in rows) {
                    "$name needs sampled menu above and below the drawer edge: $geometry"
                }
                val panel = stripProfile(bitmap, geometry.witnessStrip, ::luminance)
                val menu = stripProfile(bitmap, geometry.menuStrip, ::luminance)
                sampling = MenuSampling(name, strongestEdge(panel, top - 8..top + 8),
                    halfAmplitudeEdge(menu, rows), top, rows, menu)
            }
        }
        return sampling
    }

    private fun assertPageBandTracksWindow(row: SemanticsMatcher, setBand: (IntRange) -> Unit, changeColor: () -> Unit) {
        val geometry = samplingGeometry(row)
        val menuTop = geometry.menu.top
        val room = geometry.sheet.top - menuTop
        // Independently draw a localized hue shift in the PAGE, strictly above the drawer.
        check(room >= 160) { "Not enough room for page controls on both sides of the band: $geometry" }
        fun at(fraction: Float) = (menuTop + room * fraction).roundToInt()
        fun capture(name: String): Pair<DoubleArray, DoubleArray> {
            lateinit var profiles: Pair<DoubleArray, DoubleArray>
            ui.capture(name) { bitmap ->
                fun chroma(pixel: Int) = (PixelColor.red(pixel) - PixelColor.blue(pixel)).toDouble()
                profiles = stripProfile(bitmap, geometry.menuStrip, ::chroma) to
                    stripProfile(bitmap, geometry.witnessStrip, ::chroma)
            }
            return profiles
        }
        compose.runOnIdle { setBand(at(.38f)..at(.72f)) }
        val (redMenu, redPage) = capture("10-reasoning-model-list-page-warm")
        compose.runOnIdle(changeColor)
        val (blueMenu, bluePage) = capture("11-reasoning-model-list-page-cool")
        fun change(before: DoubleArray, after: DoubleArray, fraction: Float): Double {
            val y = at(fraction)
            return (y - 7..y + 7).map { before[it] - after[it] }.average()
        }
        val pageGain = change(redPage, bluePage, .55f)
        val menuGain = change(redMenu, blueMenu, .55f)
        val leakage = maxOf(abs(change(redMenu, blueMenu, .16f)), abs(change(redMenu, blueMenu, .90f)))
        assertTrue("The uncovered page must actually change hue: response=$pageGain", pageGain >= 32)
        assertTrue("Menu must respond at the real page band, not at offset control strips: " +
            "page=$pageGain, menu=$menuGain, outside=$leakage",
            menuGain >= maxOf(3.0, pageGain * .08) && menuGain >= leakage + 3)
    }

    private fun luminance(p: Int) = .2126 * PixelColor.red(p) + .7152 * PixelColor.green(p) + .0722 * PixelColor.blue(p)

    private fun stripProfile(bitmap: Bitmap, columns: IntRange, signal: (Int) -> Double): DoubleArray {
        check(!columns.isEmpty() && columns.first >= 0 && columns.last < bitmap.width) { "Off-window strip $columns" }
        return DoubleArray(bitmap.height) { y -> columns.sumOf { x -> signal(bitmap.getPixel(x, y)) } / columns.count() }
    }

    private fun strongestEdge(profile: DoubleArray, rows: IntRange): Edge {
        fun contrast(y: Int) = abs((y until y + 6).sumOf { profile[it] } - (y - 6 until y).sumOf { profile[it] }) / 6
        check(!rows.isEmpty() && rows.first >= 6 && rows.last + 6 <= profile.size) { "Off-window rows: $rows" }
        val y = checkNotNull(rows.maxByOrNull(::contrast))
        return Edge(y.toDouble(), contrast(y))
    }

    private fun halfAmplitudeEdge(profile: DoubleArray, rows: IntRange): Edge {
        // Estimate opposite material levels well inside the menu, rather than imposing a blur width.
        val plateau = 16
        check(rows.count() >= 4 * plateau)
        fun mean(range: IntRange) = range.sumOf { profile[it] } / range.count()
        val above = mean(rows.first until rows.first + plateau)
        val below = mean(rows.last - plateau + 1..rows.last)
        val delta = below - above
        if (delta == 0.0) return Edge(Double.NaN, 0.0)
        fun coverage(y: Int) = (mean(y - 3..y + 3) - above) / delta
        // Rows are pixel centers; interpolate the first sustained crossing in either luminance direction.
        for (y in rows.first + plateau..rows.last - plateau) {
            val previous = coverage(y - 1)
            val current = coverage(y)
            if (previous < .5 && current >= .5 && coverage(y + 8) >= .5) {
                return Edge(y - .5 + (.5 - previous) / (current - previous), abs(delta))
            }
        }
        return Edge(Double.NaN, abs(delta))
    }

    private fun modelOption(backend: MixUiBackend, model: String, provider: String): SemanticsMatcher {
        val label = if (backend == MixUiBackend.Mochi) hasText("$model · $provider")
        else hasText(model) and hasText(provider)
        return hasClickAction() and label
    }

    private fun menuRoot(row: SemanticsMatcher): SemanticsNodeInteraction =
        compose.onNode(isRoot() and hasAnyDescendant(row))

    private fun assertAlignedReasoningHeader() {
        val header = ui.node("mary-reasoning-model-header", unmerged = true).fetchSemanticsNode()
        val slider = ui.node("mary-reasoning-slider", unmerged = true).fetchSemanticsNode()
        assertEquals(slider.boundsInRoot.center.x, header.boundsInRoot.center.x, 1f)
        val name = ui.node("mary-reasoning-model-name", unmerged = true).assertIsDisplayed()
        val effort = ui.node("mary-reasoning-level", unmerged = true).assertIsDisplayed()
        fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val nameLayout = layout(name)
        val effortLayout = layout(effort)
        assertEquals(nameLayout.layoutInput.style.fontSize, effortLayout.layoutInput.style.fontSize)
        assertEquals(nameLayout.layoutInput.style.lineHeight, effortLayout.layoutInput.style.lineHeight)
        assertEquals(
            name.fetchSemanticsNode().positionInRoot.y + nameLayout.firstBaseline,
            effort.fetchSemanticsNode().positionInRoot.y + effortLayout.firstBaseline,
            1f,
        )
    }

    private fun assertProgress(slider: SemanticsNodeInteraction, expected: Float) {
        assertEquals("The selected model retains the committed reasoning strength", expected,
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
    }
}
