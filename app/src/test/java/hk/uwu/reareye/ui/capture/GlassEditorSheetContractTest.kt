package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import hk.uwu.mixui.components.Tabs
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.toArgb
import hk.uwu.mixui.components.ArrowPreference
import hk.uwu.mixui.components.FormDialog
import hk.uwu.mixui.components.PreferenceRow
import hk.uwu.mixui.components.SpinnerPreference
import hk.uwu.mixui.components.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.EditorTextField
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.GlassSelectPreference
import hk.uwu.mixui.components.GlassEditorSheet
import hk.uwu.mixui.components.GlassOverlayDialog
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.TextField
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Shared-component contracts. Insets are synthetic; this does not establish device IME behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassEditorSheetContractTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val backends = listOf(MixUiBackend.Miuix, MixUiBackend.Mochi)
    private var visible by mutableStateOf(true)
    private var valid by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var session by mutableStateOf(0)
    private var fieldText by mutableStateOf("abcdef")
    private var password by mutableStateOf(false)
    private var requestFieldFocus by mutableStateOf(true)
    private var placeField by mutableStateOf(true)
    private var revision by mutableStateOf(0)
    private var digitsOnly = false
    private var saveCount = 0
    private var dismissCount = 0
    private var pageBacks = 0
    private var rejectedEdits = 0
    private val pageFocus = FocusRequester()

    private fun frames() {
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun open(backend: MixUiBackend, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    // Ordinary FormDialog needs the same root popup host as production pages.
                    GlassPreferenceScene {
                        Box(Modifier.fillMaxSize().background(Color(0xFF315880))) {
                            TextField("Page", {}, Modifier.testTag("contract-page").focusRequester(pageFocus), singleLine = true)
                            BackHandler { pageBacks++ }
                            content()
                        }
                    }
                }
            }
        }
        frames()
    }

    private fun openSheet(backend: MixUiBackend, long: Boolean) {
        this.open(backend) {
            GlassEditorSheet(
                show = visible,
                title = "Contract editor",
                sessionKey = session,
                onDismissRequest = { dismissCount++; visible = false },
                onConfirm = { saveCount++ },
                confirmText = "Contract save",
                cancelText = "Contract cancel",
                confirmEnabled = valid && !busy,
                allowDismiss = !busy,
            ) {
                Text("First field", Modifier.testTag("contract-first"))
                if (long) repeat(18) { index ->
                    Box(Modifier.fillMaxWidth().height(72.dp)) { Text("Form row $index") }
                }
                EditorTextField(
                    value = fieldText,
                    onValueChange = { fieldText = it },
                    label = "Last field",
                    modifier = Modifier.fillMaxWidth().testTag("contract-last"),
                    singleLine = true,
                )
            }
        }
    }

    private fun openDialog(backend: MixUiBackend) {
        this.open(backend) {
            GlassOverlayDialog(visible, "Contract input", { visible = false }) {
                Column {
                    Text("Revision $revision")
                    EditorTextField(
                        value = fieldText,
                        onValueChange = {
                            if (!digitsOnly || it.all(Char::isDigit)) fieldText = it else rejectedEdits++
                        },
                        label = "Outside label",
                        modifier = Modifier.fillMaxWidth().testTag("contract-field").layout { measurable, constraints ->
                            val child = measurable.measure(constraints)
                            layout(child.width, if (placeField) child.height else 0) {
                                if (placeField) child.place(0, 0)
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                        autoFocus = visible && requestFieldFocus,
                        focusSessionKey = session,
                        selectAllOnFocus = true,
                    )
                }
            }
        }
    }

    private fun sheetTag(backend: MixUiBackend) = if (backend == MixUiBackend.Miuix)
        "mixui-native-glass-sheet" else "mochi-glass-sheet"
    private fun sheet(backend: MixUiBackend) = compose.onNodeWithTag(sheetTag(backend), useUnmergedTree = true)
    private fun root(backend: MixUiBackend) = compose.onNode(
        isRoot() and hasAnyDescendant(hasTestTag(sheetTag(backend))), useUnmergedTree = true)
    private fun body() = compose.onNodeWithTag("mixui-editor-sheet-body", useUnmergedTree = true)
    private fun editableMatcher(tag: String) = hasSetTextAction() and
        (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))
    private fun editable(tag: String) = compose.onNode(editableMatcher(tag), useUnmergedTree = true)
    private fun valueInput(tag: String) = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
    private fun SemanticsNodeInteraction.assertNotInputFocused() = assert(
        SemanticsMatcher("Input is not focused (including removed disabled focus semantics)") {
            it.config.getOrNull(SemanticsProperties.Focused) != true
        })
    private fun assertEditingRejected(input: SemanticsNodeInteraction) {
        val config = input.fetchSemanticsNode().config
        // Native versions may remove disabled editing actions or keep rejecting handlers.
        compose.runOnIdle {
            assertFalse(config.getOrNull(SemanticsActions.SetText)?.action?.invoke(
                androidx.compose.ui.text.AnnotatedString("999")) ?: false)
            assertFalse(config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action?.invoke(
                androidx.compose.ui.text.AnnotatedString("9")) ?: false)
        }
    }
    private fun field() = editable("contract-field")
    private fun save() = compose.onNodeWithContentDescription("Contract save")
    private fun cancel() = compose.onNodeWithContentDescription("Contract cancel")
    private fun selection() = field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
    }
    private fun dragHeader(backend: MixUiBackend) {
        sheet(backend).performTouchInput {
            // The native short-sheet threshold is 150dp OR 800dp/s. A real
            // flick exercises dismissal even when the whole form is under 230dp.
            swipe(Offset(centerX, 8f), Offset(centerX, height - 8f), durationMillis = 120)
        }
        frames()
    }
    private fun injectInsets(navigation: Int, ime: Int = 0) {
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigation))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigation))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), navigation > 0)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView, insets)
        }
        frames()
    }

    private fun assertLabelPlacement(tag: String, label: String, inline: Boolean) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val fieldBounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        compose.onAllNodes(hasText(label) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true).assertCountEquals(if (inline) 1 else 0)
        val labelBounds = node.boundsInWindow
        if (inline) {
            assertTrue("$label must be inside the native field: $labelBounds / $fieldBounds",
                labelBounds.left >= fieldBounds.left - 1 && labelBounds.right <= fieldBounds.right + 1 &&
                    labelBounds.top >= fieldBounds.top - 1 && labelBounds.bottom <= fieldBounds.bottom + 1)
        } else {
            assertTrue("$label must remain above the actual editable field", labelBounds.bottom <= fieldBounds.top + 1)
        }
    }

    /** Controlled shared-component fixture, NOT a production-page or device/IME screenshot. */
    private fun captureContentContract(
        name: String,
        directory: String = "miuix-sheet-content-labels/CONTRACT",
        settle: Boolean = true,
    ): Bitmap {
        if (settle) frames() else compose.waitForIdle()
        val image = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val output = File(System.getProperty("ui.capture.output") ?: "build/reports/ui-capture",
            directory).apply { mkdirs() }
        File(output, "CONTROLLED_FIXTURE.txt").writeText(
            "Controlled shared-component fixture, not a real application page. " +
                "Robolectric native window capture; synthetic insets; no device IME claim. " +
                "Samples use actual tagged controls and vendor-native geometry/shadow references.\n")
        File(output, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return image
    }

    private fun boundsInCapture(image: Bitmap, tag: String): Rect {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInWindow
        assertTrue("$tag must be fully inside the captured Activity window: $bounds",
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= image.width + 1 && bounds.bottom <= image.height + 1)
        assertTrue("$tag needs an unobscured fill sample", bounds.width > 32 && bounds.height > 24)
        return bounds
    }

    private fun colorDistance(first: Int, second: Int) = maxOf(
        abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)),
        abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)),
        abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second)))

    private fun assertColor(message: String, expected: Int, actual: Int, tolerance: Int = 3) {
        assertTrue("$message: expected=${Integer.toHexString(expected)}, actual=${Integer.toHexString(actual)}",
            colorDistance(expected, actual) <= tolerance)
    }

    private fun fillPixel(image: Bitmap, tag: String): Int {
        val bounds = boundsInCapture(image, tag)
        // Short fixture labels/values start at the left; the trailing controls are at the
        // right. This bottom-centre 3x3 patch avoids both, the cursor and native borders.
        val x = bounds.center.x.roundToInt()
        val y = (bounds.bottom - 8).roundToInt()
        val pixels = (-1..1).flatMap { dy -> (-1..1).map { dx -> image.getPixel(x + dx, y + dy) } }
        assertTrue("$tag must render into an opaque window", pixels.all { android.graphics.Color.alpha(it) == 255 })
        val sample = pixels.first()
        pixels.forEach { assertColor("$tag sample must be a flat, text-free fill patch", sample, it, 2) }
        return sample
    }

    private fun assertNative16dpCorner(image: Bitmap, tag: String, fill: Int) {
        fun profile(control: String): List<Boolean> {
            val bounds = boundsInCapture(image, control)
            // The fixture is mdpi: compare the actual top-right 16px corner with the
            // public native squircleSurface(16.dp), not a guessed circular equation.
            return (0 until 16).flatMap { y -> (0 until 16).map { x ->
                colorDistance(fill, image.getPixel(bounds.right.roundToInt() - 1 - x,
                    bounds.top.roundToInt() + y)) <= 3
            } }
        }
        val native = profile("contract-native-16dp")
        val actual = profile(tag)
        assertFalse("$tag must not be a square corner", actual.first())
        assertTrue("$tag must reach its opaque interior inside 16dp", actual.last())
        assertTrue("The native 16dp reference must have a measurable clipped corner", native.count { !it } >= 4)
        assertTrue("$tag must retain the native 16dp corner coverage",
            abs(actual.count { !it } - native.count { !it }) <= 6)
        assertTrue("$tag corner must follow the native 16dp squircle (allow edge antialiasing)",
            actual.zip(native).count { (a, b) -> a != b } <= 12)
    }

    @Test fun miuixSheetContentUsesNativeSurfacesInlineLabelsAndExplicitOverridesInBothSchemes() {
        for (dark in listOf(false, true)) {
            val checked = mutableStateOf(false)
            var rowClicks = 0
            var expectedFill = Color.Unspecified
            val explicitFill = Color(0xFF8C4978)
            this.open(MixUiBackend.Miuix, dark) {
                val fill = if (dark) MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 1f) else Color.White
                SideEffect { expectedFill = fill }
                GlassEditorSheet(true, "Controlled content fixture", {}, {}, "Fixture save") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorTextField("Editor value", {}, Modifier.fillMaxWidth().testTag("contract-sheet-editor"),
                            label = "Sheet editor label", singleLine = true)
                        TextField("Ordinary value", {}, Modifier.fillMaxWidth().testTag("contract-sheet-ordinary"),
                            label = "Sheet ordinary label", singleLine = true)
                        TextField("Override value", {}, Modifier.fillMaxWidth().testTag("contract-sheet-override"),
                            singleLine = true, backgroundColor = explicitFill)
                        SwitchPreference(checked.value, { checked.value = it }, "Switch",
                            Modifier.fillMaxWidth().testTag("contract-sheet-switch"))
                        SpinnerPreference(listOf("First", "Second"), 0, "Spinner", {},
                            Modifier.testTag("contract-sheet-spinner"), summary = "Selected First")
                        PreferenceRow("Action", Modifier.fillMaxWidth().testTag("contract-sheet-row"),
                            onClick = { rowClicks++ })
                        ArrowPreference("Arrow", Modifier.fillMaxWidth().testTag("contract-sheet-arrow"),
                            onClick = { rowClicks++ })
                        GlassSelectPreference("Glass select", listOf("First", "Second"), 0, {},
                            Modifier.fillMaxWidth().testTag("contract-sheet-select"))
                        // Geometry reference only. Fill assertions below sample the real controls above.
                        Box(Modifier.size(64.dp).testTag("contract-native-16dp")
                            .squircleSurface(color = fill, cornerRadius = 16.dp))
                    }
                }
            }
            assertEquals("Native preference radius must still be 16dp", 16.dp,
                top.yukonga.miuix.kmp.basic.CardDefaults.CornerRadius)
            assertLabelPlacement("contract-sheet-editor", "Sheet editor label", inline = true)
            assertLabelPlacement("contract-sheet-ordinary", "Sheet ordinary label", inline = true)
            val image = captureContentContract("miuix-${if (dark) "dark" else "light"}-sheet-content")
            try {
                for (tag in listOf("editor", "ordinary", "switch", "spinner", "row", "arrow", "select")) {
                    val fullTag = "contract-sheet-$tag"
                    assertColor("$fullTag must use the opaque native sheet fill", expectedFill.toArgb(), fillPixel(image, fullTag))
                    assertNative16dpCorner(image, fullTag, expectedFill.toArgb())
                }
                assertColor("Explicit TextField background must override the sheet default",
                    explicitFill.toArgb(), fillPixel(image, "contract-sheet-override"))
            } finally { image.recycle() }
            compose.onNodeWithTag("contract-sheet-row").performClick()
            assertEquals("Native row must retain its click callback", 1, rowClicks)
            compose.onNodeWithTag("contract-sheet-switch").performTouchInput { click() }
            frames()
            assertTrue("Native switch must retain its state callback", checked.value)
        }
    }

    /** Infer the material bounds from vendor-owned selectable slots, not our padding/tag order. */
    private fun nativeTrackBounds(tag: String): Rect {
        val slots = compose.onAllNodes(
            hasAnyAncestor(hasTestTag(tag)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { it.boundsInWindow }
        assertEquals("$tag must remain one continuous native two-slot track", 2, slots.size)
        slots.forEach { assertEquals("Outer halo must not enlarge native touch/slider slots", 34f, it.height, 1f) }
        return Rect(slots.minOf { it.left } - 3f, slots.minOf { it.top } - 3f,
            slots.maxOf { it.right } + 3f, slots.maxOf { it.bottom } + 3f)
    }

    @Test fun miuixSheetTabsPreserveUnclippedNativeTrackHaloAndTouchGeometryInBothSchemes() {
        val directory = "sheet-glass-controls-3a980f7c/TABS"
        for (dark in listOf(false, true)) {
            val selected = mutableStateOf(0)
            val tracksVisible = mutableStateOf(false)
            val base = if (dark) Color(0xFF303030) else Color(0xFFE8E8E8)
            this.open(MixUiBackend.Miuix, dark) {
                val referenceBackdrop = rememberLayerBackdrop()
                GlassEditorSheet(true, "Controlled tab halo", {}, {}, "Fixture save") {
                    // Identical opaque surround isolates the actual native shadow from the sheet
                    // material. The reference is a real vendor track, with ample external space;
                    // its exterior shadow does not depend on the recorded backdrop's contents.
                    Column(Modifier.fillMaxWidth().drawBehind {
                        // Equal backdrop under BOTH halos, including the pre-existing
                        // sheet margin and top inset. This changes no layout geometry.
                        val gutter = 24.dp.toPx()
                        val halo = 8.dp.toPx()
                        drawRect(base, topLeft = Offset(-gutter, -halo),
                            size = Size(size.width + gutter * 2, size.height + halo * 2))
                    }) {
                        if (tracksVisible.value) {
                            Tabs(listOf("First", "Second"), selected.value, { selected.value = it }, Modifier.fillMaxWidth())
                        } else Box(Modifier.fillMaxWidth().height(40.dp))
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                            if (tracksVisible.value) {
                                GlassSegmentedTabRow(listOf("First", "Second"), selected.value, {}, referenceBackdrop,
                                    Modifier.fillMaxWidth().testTag("contract-unclipped-native-track"))
                            } else Box(Modifier.fillMaxWidth().height(40.dp))
                        }
                    }
                }
            }
            val background = captureContentContract("miuix-${if (dark) "dark" else "light"}-tabs-background", directory)
            compose.runOnIdle { tracksVisible.value = true }
            compose.onNodeWithTag("tabs-miuix", useUnmergedTree = true).assertDoesNotExist()
            val image = captureContentContract("miuix-${if (dark) "dark" else "light"}-tabs-halo", directory)
            try {
                val actual = nativeTrackBounds("glass-tabs-miuix")
                val reference = nativeTrackBounds("contract-unclipped-native-track")
                assertEquals("Material must keep its ORIGINAL 40dp height", 40f, actual.height, 1f)
                assertEquals("Must keep the original full-width 352dp track, never shrink it to 336dp", 352f, actual.width, 1f)
                assertEquals("Reference and real track must have identical original material width", reference.width, actual.width, 1f)
                val viewport = body().fetchSemanticsNode().boundsInWindow
                val panel = sheet(MixUiBackend.Miuix).fetchSemanticsNode().boundsInWindow
                assertEquals("Original 24dp left inset must not change", 24f, actual.left - panel.left, 1f)
                assertEquals("Original 24dp right inset must not change", 24f, panel.right - actual.right, 1f)
                assertEquals("Shadow room must come from the PARENT, not child padding", viewport.left, actual.left, 1f)
                assertEquals("Original content width must not change", viewport.width, actual.width, 1f)
                assertEquals("Original first-row placement must stay 8dp from body top", 8f, actual.top - viewport.top, 1f)
                val width = actual.width.roundToInt()
                val height = actual.height.roundToInt()
                val pill = android.graphics.Path().apply {
                    addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f,
                        android.graphics.Path.Direction.CW)
                }
                val mask = android.graphics.Region().apply {
                    setPath(pill, android.graphics.Region(0, 0, width, height))
                }
                var haloPixels = 0
                var compared = 0
                var mismatches = 0
                var totalDifference = 0L
                for (y in -8 until height + 8) for (x in -8 until width + 8) {
                    // Skip material and antialiased rim; compare the real outer halo including
                    // all four cap corners and each edge against the unbounded native track.
                    if ((-1..1).any { dy -> (-1..1).any { dx -> mask.contains(x + dx, y + dy) } }) continue
                    val expected = image.getPixel(reference.left.roundToInt() + x, reference.top.roundToInt() + y)
                    val pixel = image.getPixel(actual.left.roundToInt() + x, actual.top.roundToInt() + y)
                    if (colorDistance(expected, base.toArgb()) >= 2) haloPixels++
                    val difference = colorDistance(expected, pixel)
                    totalDifference += difference
                    if (difference > 4) mismatches++
                    compared++
                }
                assertTrue("Native reference must have a measurable real halo, not an empty same-colour fake", haloPixels > 16)
                assertTrue("Sheet halo must match unbounded vendor pixels (mean error): $totalDifference / $compared",
                    totalDifference <= compared * 2L)
                assertTrue("No scroll-edge/corner truncation or material painted into halo: $mismatches / $compared",
                    mismatches <= compared / 25)
                // Independent exterior probes: two equally clipped native tracks would
                // agree above. The sheet margin OUTSIDE the original 352dp body must
                // actually contain the shadow, relative to the SAME scene without tracks.
                fun brightness(color: Int) = android.graphics.Color.red(color) +
                    android.graphics.Color.green(color) + android.graphics.Color.blue(color)
                val centerY = actual.center.y.roundToInt()
                val leftHalo = (-6..6).count { dy ->
                    brightness(background.getPixel((actual.left - 2).roundToInt(), centerY + dy)) >
                        brightness(image.getPixel((actual.left - 2).roundToInt(), centerY + dy))
                }
                val rightHalo = (-6..6).count { dy ->
                    brightness(background.getPixel((actual.right + 1).roundToInt(), centerY + dy)) >
                        brightness(image.getPixel((actual.right + 1).roundToInt(), centerY + dy))
                }
                System.err.println("FULL_WIDTH_HALO dark=$dark actual=$actual left=$leftHalo right=$rightHalo")
                assertTrue("Left shadow must really paint beyond the original content bounds", leftHalo >= 6)
                assertTrue("Right shadow must really paint beyond the original content bounds", rightHalo >= 6)
            } finally { image.recycle(); background.recycle() }
            val second = compose.onNode(hasText("Second") and hasAnyAncestor(hasTestTag("glass-tabs-miuix")))
            compose.mainClock.autoAdvance = false
            second.performTouchInput { down(center) }
            try {
                repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                assertEquals("Holding a native tab must not select before release", 0, selected.value)
                val held = captureContentContract("miuix-${if (dark) "dark" else "light"}-tabs-held",
                    directory, settle = false)
                held.recycle()
                assertEquals("Held native capsule/slot geometry must stay unchanged", 40f,
                    nativeTrackBounds("glass-tabs-miuix").height, 1f)
            } finally {
                second.performTouchInput { up() }
                frames()
            }
            second.assertIsSelected()
            assertEquals("Native slider must keep selection callbacks", 1, selected.value)
            val switched = captureContentContract("miuix-${if (dark) "dark" else "light"}-tabs-second", directory)
            switched.recycle()
        }
    }

    @Test fun miuixSheetPreferenceHeldPressClipsToNativeSquircleAndStillClicksInBothSchemes() {
        val directory = "sheet-glass-controls-3a980f7c/TABS"
        for (dark in listOf(false, true)) {
            var clicks = 0
            var squircleEnabled = false
            this.open(MixUiBackend.Miuix, dark) {
                val enabled = isSquircleEnabled()
                SideEffect { squircleEnabled = enabled }
                GlassEditorSheet(true, "Controlled held preference", {}, {}, "Fixture save") {
                    PreferenceRow("Native press feedback", Modifier.fillMaxWidth().testTag("contract-held-preference"),
                        onClick = { clicks++ })
                }
            }
            val scheme = if (dark) "dark" else "light"
            val before = captureContentContract("miuix-$scheme-preference-rest", directory)
            val row = compose.onNodeWithTag("contract-held-preference", useUnmergedTree = true)
            val bounds = boundsInCapture(before, "contract-held-preference")
            val width = bounds.width.roundToInt()
            val height = bounds.height.roundToInt()
            val path = Path().apply {
                addSquircleRect(width.toFloat(), height.toFloat(),
                    top.yukonga.miuix.kmp.basic.CardDefaults.CornerRadius.value,
                    squircleEnabled = squircleEnabled)
            }
            val nativeMask = android.graphics.Region().apply {
                setPath(path.asAndroidPath(), android.graphics.Region(0, 0, width, height))
            }
            compose.mainClock.autoAdvance = false
            row.performTouchInput { down(center) }
            var pressed: Bitmap? = null
            try {
                // Keep the physical pointer down through the delayed scroll-container press and
                // native feedback animation; capture must not call frames()/auto-advance here.
                repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                assertEquals("A held press must not dispatch the callback early", 0, clicks)
                val held = captureContentContract("miuix-$scheme-preference-held", directory, settle = false)
                pressed = held
                var outsideSamples = 0
                for (cornerX in listOf(0, width - 18)) for (cornerY in listOf(0, height - 18)) {
                    var cornerSamples = 0
                    for (dy in 0 until 18) for (dx in 0 until 18) {
                        val x = cornerX + dx
                        val y = cornerY + dy
                        // Exclude the one-pixel antialias fringe using the native public path,
                        // not a manually rounded equation or a same-fill reference picture.
                        if ((-1..1).any { oy -> (-1..1).any { ox -> nativeMask.contains(x + ox, y + oy) } }) continue
                        assertColor("Native pressed feedback must not square off corner ($x,$y)",
                            before.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y),
                            held.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y), 2)
                        cornerSamples++
                    }
                    assertTrue("Every native squircle corner must provide outside-path probes", cornerSamples >= 4)
                    outsideSamples += cornerSamples
                }
                assertTrue("Corner test must sample real exterior pixels", outsideSamples >= 16)
                var feedbackPixels = 0
                for (x in width / 2 - 8..width / 2 + 8) for (y in height - 12..height - 6) {
                    assertTrue(nativeMask.contains(x, y))
                    val original = before.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y)
                    val current = held.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y)
                    fun brightness(color: Int) = android.graphics.Color.red(color) +
                        android.graphics.Color.green(color) + android.graphics.Color.blue(color)
                    val delta = brightness(current) - brightness(original)
                    // The native on-surface indication darkens light controls and lightens dark ones.
                    if (if (dark) delta >= 3 else delta <= -3) feedbackPixels++
                }
                assertTrue("Real native held feedback must visibly change the text-free interior in the theme's direction, not be disabled",
                    feedbackPixels >= 60)
            } finally {
                row.performTouchInput { up() }
                frames()
                before.recycle()
                pressed?.recycle()
            }
            assertEquals("Releasing the same real press must preserve the row click callback", 1, clicks)
            val released = captureContentContract("miuix-$scheme-preference-released", directory)
            released.recycle()
        }
    }

    @Composable private fun dialogSurfaceControls() {
        // A colored probe behind the real inputs makes transparency observable even
        // when the ordinary native Dialog panel is naturally the same white as a Sheet fill.
        Column(Modifier.fillMaxWidth().background(Color(0xFF557799)),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorTextField("Editor value", {}, Modifier.fillMaxWidth().testTag("contract-nested-editor"),
                label = "Dialog editor label", singleLine = true)
            TextField("Transparent value", {}, Modifier.fillMaxWidth().testTag("contract-transparent-reference"),
                singleLine = true, backgroundColor = Color.Transparent)
            TextField("Ordinary value", {}, Modifier.fillMaxWidth().testTag("contract-dialog-ordinary"), singleLine = true)
        }
    }

    @Test fun dialogsOpenedFromActualSheetBodyResetPresentationAndKeepTransparentEditorsInBothSchemes() {
        for (dark in listOf(false, true)) for (glass in listOf(true, false)) {
            val kind = if (glass) "glass-overlay" else "form"
            val scheme = if (dark) "dark" else "light"
            // A direct Page -> Dialog rendering is the unchanged ordinary-TextField baseline.
            this.open(MixUiBackend.Miuix, dark) {
                if (glass) GlassOverlayDialog(true, "Controlled direct dialog", {}) { dialogSurfaceControls() }
                else FormDialog(true, "Controlled direct dialog", {}) { dialogSurfaceControls() }
            }
            val baseline = captureContentContract("miuix-$scheme-$kind-direct")
            val originalOrdinary: Int
            try { originalOrdinary = fillPixel(baseline, "contract-dialog-ordinary") }
            finally { baseline.recycle() }
            val nested = mutableStateOf(false)
            var expectedSheetFill = Color.Unspecified
            this.open(MixUiBackend.Miuix, dark) {
                val fill = if (dark) MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 1f) else Color.White
                SideEffect { expectedSheetFill = fill }
                GlassEditorSheet(true, "Controlled nesting fixture", {}, {}, "Fixture save") {
                    EditorTextField("Sheet value", {}, Modifier.fillMaxWidth().testTag("contract-parent-editor"),
                        label = "Parent sheet label", singleLine = true)
                    PreferenceRow("Open nested dialog", Modifier.fillMaxWidth().testTag("contract-open-nested"),
                        onClick = { nested.value = true })
                    // This invocation is genuinely composed inside the rendered Sheet body.
                    if (glass) GlassOverlayDialog(nested.value, "Controlled nested dialog", { nested.value = false }) {
                        dialogSurfaceControls()
                    } else FormDialog(nested.value, "Controlled nested dialog", { nested.value = false }) {
                        dialogSurfaceControls()
                    }
                }
            }
            val sheetImage = captureContentContract("miuix-$scheme-$kind-parent-sheet")
            try { assertColor("The caller must really be a Sheet", expectedSheetFill.toArgb(),
                fillPixel(sheetImage, "contract-parent-editor")) } finally { sheetImage.recycle() }
            compose.onNodeWithTag("contract-open-nested").performClick()
            frames()
            assertLabelPlacement("contract-nested-editor", "Dialog editor label", inline = true)
            val image = captureContentContract("miuix-$scheme-$kind-from-sheet")
            try {
                val transparent = fillPixel(image, "contract-transparent-reference")
                assertTrue("Transparent reference must distinguish the panel from an inherited sheet fill",
                    colorDistance(transparent, expectedSheetFill.toArgb()) > 3)
                assertColor("Nested EditorTextField must match the actual explicitly transparent field, not sheet white/dark fill",
                    transparent, fillPixel(image, "contract-nested-editor"), 6)
                assertColor("An ordinary Dialog TextField must retain its direct-dialog default",
                    originalOrdinary, fillPixel(image, "contract-dialog-ordinary"))
            } finally { image.recycle() }
            editable("contract-nested-editor").performTouchInput { click() }
            frames()
            editable("contract-nested-editor").assertIsFocused()
            back()
            assertFalse("Back must dismiss only the nested $kind dialog", nested.value)
            compose.onNodeWithTag("contract-parent-editor").assertIsDisplayed()
            compose.onNodeWithTag("contract-open-nested").assertIsDisplayed()
        }
    }

    @Test fun pageAndMochiEditorLabelsRemainExternalWithNativeMochiMaterialInBothSchemes() {
        for (dark in listOf(false, true)) for (backend in backends) {
            val name = "${backend.name.lowercase()}-${if (dark) "dark" else "light"}"
            this.open(backend, dark) {
                Column {
                    EditorTextField("Page value", {}, Modifier.fillMaxWidth().testTag("contract-page-editor"),
                        label = "Page editor label", singleLine = true)
                    TextField("Ordinary value", {}, Modifier.fillMaxWidth().testTag("contract-page-ordinary"), singleLine = true)
                }
            }
            assertLabelPlacement("contract-page-editor", "Page editor label", inline = false)
            val page = captureContentContract("$name-page-external-label")
            try {
                if (backend == MixUiBackend.Mochi) assertColor("Mochi page editor must retain native TextField material",
                    fillPixel(page, "contract-page-ordinary"), fillPixel(page, "contract-page-editor"), 6)
            } finally { page.recycle() }
            if (backend != MixUiBackend.Mochi) continue
            this.open(backend, dark) {
                GlassEditorSheet(true, "Controlled Mochi fixture", {}, {}, "Fixture save") { dialogSurfaceControls() }
            }
            assertLabelPlacement("contract-nested-editor", "Dialog editor label", inline = false)
            val mochi = captureContentContract("$name-sheet-external-label")
            try { assertColor("Mochi sheet editor must retain native TextField material",
                fillPixel(mochi, "contract-dialog-ordinary"), fillPixel(mochi, "contract-nested-editor"), 6) }
            finally { mochi.recycle() }
        }
    }

    @Test fun miuixSheetDisabledPreferencesUseNativeNeutralAndCannotInvokeActionsInBothSchemes() {
        for (dark in listOf(false, true)) {
            var callbacks = 0
            var expectedFill = Color.Unspecified
            this.open(MixUiBackend.Miuix, dark) {
                val disabledFill = MiuixTheme.colorScheme.disabledSecondaryVariant
                SideEffect { expectedFill = disabledFill }
                GlassEditorSheet(true, "Disabled preferences", {}, {}, "Fixture save") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreferenceRow("Disabled row", Modifier.fillMaxWidth().testTag("disabled-row"),
                            summary = "Native summary", enabled = false, onClick = { callbacks++ })
                        ArrowPreference("Disabled arrow", Modifier.fillMaxWidth().testTag("disabled-arrow"),
                            summary = "Native summary", enabled = false, onClick = { callbacks++ })
                        SwitchPreference(false, { callbacks++ }, "Disabled switch",
                            Modifier.fillMaxWidth().testTag("disabled-switch"), summary = "Native summary", enabled = false)
                        SpinnerPreference(listOf("First", "Second"), 0, "Disabled spinner", { callbacks++ },
                            Modifier.fillMaxWidth().testTag("disabled-spinner"), summary = "Native summary", enabled = false)
                    }
                }
            }
            val image = captureContentContract("miuix-${if (dark) "dark" else "light"}-preferences-disabled",
                "sheet-disabled-controls/CONTRACT")
            try {
                for (tag in listOf("disabled-row", "disabled-arrow", "disabled-switch", "disabled-spinner")) {
                    val node = compose.onNodeWithTag(tag).assertIsNotEnabled()
                    assertColor("Only each disabled preference surface receives native neutral", expectedFill.toArgb(), fillPixel(image, tag))
                    node.performTouchInput { click() }
                }
            } finally { image.recycle() }
            frames()
            assertEquals("Disabled native preference controls must not call business actions", 0, callbacks)
        }
    }

    @Test fun disabledSheetPaletteDoesNotLeakIntoPageOrDialogInputs() {
        for (dark in listOf(false, true)) for (inDialog in listOf(false, true)) {
            this.open(MixUiBackend.Miuix, dark) {
                val fields: @Composable () -> Unit = {
                    Column {
                        TextField("Disabled", {}, Modifier.fillMaxWidth().testTag("contract-isolated-wrapper"),
                            enabled = false, singleLine = true)
                        top.yukonga.miuix.kmp.basic.TextField("Disabled", {},
                            Modifier.fillMaxWidth().testTag("contract-isolated-native"), enabled = false, singleLine = true)
                    }
                }
                if (inDialog) FormDialog(true, "Disabled isolation", {}) { fields() } else fields()
            }
            val image = captureContentContract("miuix-${if (dark) "dark" else "light"}-${if (inDialog) "dialog" else "page"}-disabled-isolation",
                "sheet-disabled-controls/CONTRACT")
            try {
                assertColor("Page/Dialog keep their own native fill, never Sheet's disabled neutral",
                    fillPixel(image, "contract-isolated-native"), fillPixel(image, "contract-isolated-wrapper"))
            } finally { image.recycle() }
        }
    }

    @Test fun sheetDisabledInputsUseNativePaletteRejectEditsAndKeepDraftAndUnrelatedFocus() {
        for (dark in listOf(false, true)) for (backend in backends) {
            val automatic = mutableStateOf(false)
            val draft = mutableStateOf("731")
            var callbacks = 0
            var expectedFill = Color.Unspecified
            var expectedText = Color.Unspecified
            var expectedExternalLabel = Color.Unspecified
            this.open(backend, dark) {
                val externalLabel = if (backend == MixUiBackend.Mochi)
                    ui.feice.mochi.theme.MochiTheme.colorScheme.onSurface.let {
                        it.copy(alpha = it.alpha * ui.feice.mochi.theme.MochiDefaults.DisabledAlpha)
                    } else Color.Unspecified
                val disabledPalette = MiuixTheme.colorScheme
                SideEffect {
                    expectedFill = disabledPalette.disabledSecondaryVariant
                    expectedText = disabledPalette.disabledOnSurface
                    expectedExternalLabel = externalLabel
                }
                GlassEditorSheet(true, "Disabled contract", {}, {}, "Fixture save") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchPreference(automatic.value, { automatic.value = it }, "Automatic priority",
                            Modifier.testTag("contract-automatic"))
                        EditorTextField(draft.value, { draft.value = it; callbacks++ },
                            Modifier.fillMaxWidth().testTag("contract-disabled-editor"),
                            label = "Manual priority", enabled = !automatic.value, singleLine = true,
                            selectAllOnFocus = true,
                            // Deliberately enabled auxiliary action: the field boundary must block it.
                            trailingIcon = { Text("Clear", Modifier.testTag("contract-disabled-clear")
                                .clickable { draft.value = "" }) })
                        TextField(draft.value, { draft.value = it; callbacks++ },
                            Modifier.fillMaxWidth().testTag("contract-disabled-string"),
                            label = "Ordinary priority", enabled = !automatic.value, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Magenta))
                        EditorTextField("Unrelated", {}, Modifier.fillMaxWidth().testTag("contract-unrelated"),
                            label = "Unrelated field", singleLine = true)
                        if (backend == MixUiBackend.Mochi) {
                            ui.feice.mochi.basic.MochiTextField(draft.value, {},
                                Modifier.fillMaxWidth().testTag("contract-native-disabled"), enabled = false)
                        }
                    }
                }
            }
            val name = "${backend.name.lowercase()}-${if (dark) "dark" else "light"}"
            val target = editable("contract-disabled-editor")
            target.performTouchInput { click() }
            frames()
            target.assertIsFocused().performTextReplacement("864")
            frames()
            assertEquals("864", draft.value)
            // State transition while this input owns focus (not a click that already moves focus).
            compose.runOnIdle { automatic.value = true }
            frames()
            for (tag in listOf("contract-disabled-editor", "contract-disabled-string")) {
                val input = valueInput(tag).assertIsNotEnabled().assertNotInputFocused()
                val editsBefore = callbacks
                assertEditingRejected(input)
                input.performTouchInput { click() }
                frames()
                input.assertNotInputFocused()
                assertEquals("864", draft.value)
                assertEquals("Disabled callbacks must not run", editsBefore, callbacks)
            }
            compose.onAllNodes(hasText("Clear") and hasClickAction()).assertCountEquals(0)
            compose.onNodeWithTag("contract-disabled-editor", useUnmergedTree = true).performTouchInput {
                click(Offset(width - 16f, centerY))
            }
            frames()
            assertEquals("A disabled field's trailing clear must not edit its draft", "864", draft.value)
            val disabled = captureContentContract("$name-disabled", "sheet-disabled-controls/CONTRACT")
            try {
                if (backend == MixUiBackend.Miuix) {
                    for (tag in listOf("contract-disabled-editor", "contract-disabled-string")) {
                        assertColor("Disabled fill uses the native theme neutral", expectedFill.toArgb(), fillPixel(disabled, tag))
                        val bounds = boundsInCapture(disabled, tag)
                        val foregroundPixels = (bounds.top.roundToInt() + 6 until bounds.bottom.roundToInt() - 6).sumOf { y ->
                            (bounds.left.roundToInt() + 12 until bounds.right.roundToInt() - 40).count { x ->
                                colorDistance(disabled.getPixel(x, y), expectedText.toArgb()) <= 5
                            }
                        }
                        assertTrue("$tag value/label must really render in native disabled foreground", foregroundPixels > 12)
                    }
                } else {
                    assertColor("Mochi must retain its native disabled material, not receive a second alpha",
                        fillPixel(disabled, "contract-native-disabled"), fillPixel(disabled, "contract-disabled-editor"), 6)
                    val labelBounds = compose.onNodeWithText("Manual priority", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInWindow
                    val background = Color(disabled.getPixel(labelBounds.right.roundToInt() + 4,
                        labelBounds.center.y.roundToInt()))
                    val expectedLabelPixel = expectedExternalLabel.compositeOver(background).toArgb()
                    val labelPixels = (labelBounds.top.roundToInt() until labelBounds.bottom.roundToInt()).sumOf { y ->
                        (labelBounds.left.roundToInt() until labelBounds.right.roundToInt()).count { x ->
                            colorDistance(disabled.getPixel(x, y), expectedLabelPixel) <= 6
                        }
                    }
                    assertTrue("Mochi external label must independently use native DisabledAlpha exactly once", labelPixels > 8)
                }
            } finally { disabled.recycle() }
            compose.runOnIdle { automatic.value = false }
            frames()
            editable("contract-disabled-editor").assertIsEnabled().performTouchInput { click() }
            frames()
            editable("contract-disabled-editor").assertIsFocused().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("864")))
            val enabled = captureContentContract("$name-reenabled", "sheet-disabled-controls/CONTRACT")
            enabled.recycle()
            editable("contract-unrelated").performTouchInput { click() }
            frames()
            editable("contract-unrelated").assertIsFocused()
            compose.runOnIdle { automatic.value = true }
            frames()
            editable("contract-unrelated").assertIsFocused()
            assertEquals("Toggling another field never resets a draft", "864", draft.value)
        }
    }

    @Test fun shortFormsWrapAndLongFormsHaveOneBodyScrollWithStationaryActions() {
        for (backend in backends) {
            visible = true
            openSheet(backend, long = false)
            val shortHeight = sheet(backend).fetchSemanticsNode().boundsInRoot.height
            val windowHeight = root(backend).fetchSemanticsNode().boundsInRoot.height
            assertTrue("$backend short content must not be a full-height sheet", shortHeight < windowHeight * 0.7f)
            assertEquals(0f, body().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 1f)
            openSheet(backend, long = true)
            assertTrue("$backend long form must be taller", sheet(backend).fetchSemanticsNode().boundsInRoot.height > shortHeight)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
                .assertCountEquals(1)
            val top = save().fetchSemanticsNode().boundsInRoot
            val cancelTop = cancel().fetchSemanticsNode().boundsInRoot
            val range = body().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("$backend body must overflow", range.maxValue() > 500f)
            body().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 10_000f)) }
            frames()
            assertTrue(range.value() > 0f)
            assertEquals(top.top, save().fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(cancelTop.top, cancel().fetchSemanticsNode().boundsInRoot.top, 1f)
            editable("contract-last").assertIsDisplayed()
            save().assertIsDisplayed()
            // Accessibility scrolls have no physical pointer/fling pair. Overshooting
            // the body at its top must not become a never-released sheet drag.
            repeat(2) {
                body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
                frames()
                assertEquals(0f, range.value(), 1f)
                assertEquals(top.top, save().fetchSemanticsNode().boundsInRoot.top, 1f)
                assertEquals(cancelTop.top, cancel().fetchSemanticsNode().boundsInRoot.top, 1f)
                save().assertIsDisplayed()
                cancel().assertIsDisplayed()
                compose.onNodeWithTag("contract-first").assertIsDisplayed()
                assertTrue(visible)
            }
        }
    }

    @Test fun physicalBodyOverscrollStillDismissesAtTheTop() {
        for (backend in backends) {
            visible = true
            dismissCount = 0
            openSheet(backend, long = true)
            body().performTouchInput {
                swipe(Offset(centerX, 24f), Offset(centerX, height - 24f), durationMillis = 180)
            }
            frames()
            assertFalse("$backend physical body drag must still transfer to the sheet", visible)
            assertEquals(1, dismissCount)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h480dp-mdpi")
    fun shortWindowSyntheticImeAndNavigationKeepHeaderAndFocusedLastFieldReachable() {
        for (backend in backends) {
            visible = true
            openSheet(backend, long = true)
            for (navigation in listOf(24, 48)) {
                injectInsets(navigation, ime = 160)
                save().assertIsDisplayed()
                cancel().assertIsDisplayed()
                val headerTop = save().fetchSemanticsNode().boundsInRoot.top
                val bottomLimit = root(backend).fetchSemanticsNode().boundsInRoot.bottom - 160f
                assertTrue("$backend body must stay above the synthetic IME", body().fetchSemanticsNode().boundsInRoot.bottom <= bottomLimit + 1f)
                body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
                frames()
                val last = editable("contract-last")
                last.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                frames()
                last.assertIsFocused().assertIsDisplayed()
                assertTrue("$backend focus must bring the last editor into its scroll viewport",
                    body().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
                assertTrue(last.fetchSemanticsNode().boundsInRoot.bottom <= bottomLimit + 1f)
                assertEquals(headerTop, save().fetchSemanticsNode().boundsInRoot.top, 1f)
                // A fresh focus transition is needed to exercise BringIntoView again.
                save().performClick()
                compose.runOnUiThread { visible = false }; frames()
                visible = true; session++
                openSheet(backend, long = true)
            }
            injectInsets(48)
            body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
            frames()
            assertTrue(editable("contract-last").fetchSemanticsNode().boundsInRoot.bottom <=
                root(backend).fetchSemanticsNode().boundsInRoot.bottom - 48f + 1f)
        }
    }

    @Test fun validationBusyVetoAndSaveAreOwnerControlledAndRecoverAfterUnlock() {
        for (backend in backends) {
            visible = true; valid = false; busy = false
            val initialSaves = saveCount
            val initialDismissals = dismissCount
            openSheet(backend, long = false)
            save().assertIsNotEnabled().performTouchInput { click() }
            assertEquals(initialSaves, saveCount)
            compose.runOnUiThread { valid = true; busy = true }; frames()
            save().assertIsNotEnabled()
            cancel().assertIsNotEnabled()
            val restingTop = sheet(backend).fetchSemanticsNode().boundsInRoot.top
            root(backend).performTouchInput { click(Offset(4f, 4f)) }; frames()
            dragHeader(backend)
            back()
            assertTrue("$backend busy sheet must remain open", visible)
            assertEquals(initialDismissals, dismissCount)
            assertEquals("Modal Back must not reach the page", 0, pageBacks)
            assertEquals(restingTop, sheet(backend).fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.runOnUiThread { busy = false }; frames()
            save().assertIsEnabled().performClick(); frames()
            assertEquals(initialSaves + 1, saveCount)
            assertTrue("Save only invokes the owner; it must not auto-close", visible)
            back()
            assertEquals(initialDismissals + 1, dismissCount)
            assertFalse(visible)
            visible = true; session++
            openSheet(backend, long = false)
            dragHeader(backend)
            assertEquals("$backend handle recovers after unlock", initialDismissals + 2, dismissCount)
            assertFalse(visible)
        }
    }

    @Test fun dialogAutoFocusSelectsOrdinaryPasswordAndEmptyValuesOnFirstOpen() {
        for (backend in backends) for ((sample, secure) in listOf("abcdef" to false, "secret" to true, "" to false)) {
            visible = true; fieldText = sample; password = secure; requestFieldFocus = true; placeField = true; session++
            openDialog(backend)
            field().assertIsFocused()
            assertEquals("$backend initial focus must select all (secure=$secure)", TextRange(0, sample.length), selection())
            assertLabelPlacement("contract-field", "Outside label", inline = backend == MixUiBackend.Miuix)
        }
    }

    @Test fun manualSelectionSurvivesRecompositionAndFilteredEditsFollowBusinessValue() {
        for (backend in backends) {
            visible = true; fieldText = "abcdef"; password = false; digitsOnly = false; requestFieldFocus = true; session++
            openDialog(backend)
            field().performTextInputSelection(TextRange(1, 2))
            compose.runOnUiThread { revision++ }; frames()
            assertEquals(TextRange(1, 2), selection())
            field().performTextInput("z"); frames()
            assertEquals("azcdef", fieldText)
            assertEquals("Typing must not select all again", TextRange(2), selection())
            compose.runOnUiThread { fieldText = "12"; digitsOnly = true }; frames()
            field().performTextInputSelection(TextRange(0, 2))
            val rejectedBefore = rejectedEdits
            field().performTextInput("x"); frames()
            assertEquals(rejectedBefore + 1, rejectedEdits)
            assertEquals("12", fieldText)
            field().assertTextEquals("12")
            compose.runOnUiThread { fieldText = "9876" }; frames()
            field().assertTextEquals("9876")
            compose.runOnUiThread { revision++ }; frames()
            assertNotEquals("External replacement is not a new focus session", TextRange(0, 4), selection())
        }
    }

    @Test fun pendingLayoutFocusIsCancelledDuringExitAndNextSessionCanFocusAgain() {
        for (backend in backends) {
            visible = true; placeField = false; requestFieldFocus = true; password = false; fieldText = "pending"; session++
            openDialog(backend)
            compose.onAllNodes(editableMatcher("contract-field") and isFocused(), useUnmergedTree = true).assertCountEquals(0)
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                // Reveal the previously unplaced node while its dialog is retained for exit.
                // Its old layout-await must not revive a cancelled autofocus request.
                visible = false; requestFieldFocus = false; placeField = true; session++
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.onAllNodes(editableMatcher("contract-field"), useUnmergedTree = true).fetchSemanticsNodes().forEach {
                assertFalse("$backend exiting editor must not take focus", it.config.getOrNull(SemanticsProperties.Focused) == true)
            }
            frames()
            compose.runOnUiThread { pageFocus.requestFocus() }; frames()
            editable("contract-page").assertIsFocused()
            compose.runOnUiThread { revision++ }; frames()
            editable("contract-page").assertIsFocused()
            compose.runOnUiThread { visible = true; requestFieldFocus = true; session++ }; frames()
            field().assertIsFocused()
            assertEquals(TextRange(0, fieldText.length), selection())
        }
    }

    @Test fun nullableSessionKeepsOldHostThroughExitAndBlocksPagePointerAndBack() {
        for (backend in backends) {
            visible = true
            var editTarget by mutableStateOf<Any?>("editing-$backend")
            var pageClicks = 0
            var requests = 0
            val initialPageBacks = pageBacks
            this.open(backend) {
                Box(Modifier.fillMaxSize().testTag("contract-page-click-target").clickable { pageClicks++ }) {
                    Text("Underlying clickable page")
                }
                GlassEditorSheet(
                    show = visible,
                    title = "Contract editor",
                    sessionKey = editTarget,
                    onDismissRequest = { requests++; visible = false; editTarget = null },
                    onConfirm = {},
                    confirmText = "Contract save",
                    cancelText = "Contract cancel",
                ) { Text("Retained editor body") }
            }
            val originalBodyId = body().fetchSemanticsNode().id
            compose.mainClock.autoAdvance = false
            cancel().performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            assertFalse(visible)
            assertNull(editTarget)
            assertEquals(1, requests)
            compose.onAllNodesWithTag(sheetTag(backend), useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodesWithTag("${sheetTag(backend)}-host", useUnmergedTree = true).assertCountEquals(1)
            assertEquals("$backend null owner target must retain the existing body, not remount it",
                originalBodyId, body().fetchSemanticsNode().id)
            // The clock remains frozen mid-exit. This exposed page area is still
            // covered by the old modal input layer until its animation completes.
            root(backend).performTouchInput { click(Offset(8f, 40f)) }
            compose.runOnUiThread { repeat(2) { activity!!.get().onBackPressedDispatcher.onBackPressed() } }
            compose.waitForIdle()
            assertEquals("$backend exit pointer must not reach the page", 0, pageClicks)
            assertEquals("$backend exit Back must not reach the page", initialPageBacks, pageBacks)
            assertEquals("Exit input must not request dismissal again", 1, requests)
            compose.onAllNodesWithTag(sheetTag(backend), useUnmergedTree = true).assertCountEquals(1)
            frames()
            compose.onAllNodesWithTag(sheetTag(backend), useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithTag("${sheetTag(backend)}-host", useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithTag("contract-page-click-target").performTouchInput { click(Offset(8f, 40f)) }
            assertEquals("Page pointer resumes only after host removal", 1, pageClicks)
            back()
            assertEquals("Page Back resumes only after host removal", initialPageBacks + 1, pageBacks)
        }
    }

    @Test fun dismissRequestKeyRearmsDismissalWithoutClosingOrReplacingTheSession() {
        for (backend in backends) for (secondViaBack in listOf(true, false)) {
            visible = true
            var requestKey by mutableStateOf(0)
            var requests = 0
            val initialPageBacks = pageBacks
            this.open(backend) {
                GlassEditorSheet(
                    show = visible,
                    title = "Contract editor",
                    sessionKey = "same-editor-session",
                    dismissRequestKey = requestKey,
                    onDismissRequest = {
                        requests++
                        if (requests == 1) requestKey++ else visible = false
                    },
                    onConfirm = {},
                    confirmText = "Contract save",
                    cancelText = "Contract cancel",
                ) { Text("Owner keeps this draft open after the first dismissal request") }
            }
            val originalBodyId = body().fetchSemanticsNode().id
            back()
            assertEquals(1, requests)
            assertEquals(1, requestKey)
            assertTrue("$backend first callback only re-arms dismissal; show stays true", visible)
            compose.onAllNodesWithTag(sheetTag(backend), useUnmergedTree = true).assertCountEquals(1)
            assertEquals("Changing only dismissRequestKey must preserve the active draft/body",
                originalBodyId, body().fetchSemanticsNode().id)
            if (secondViaBack) back() else {
                root(backend).performTouchInput { click(Offset(8f, 40f)) }
                frames()
            }
            assertEquals("$backend second ${if (secondViaBack) "Back" else "outside tap"} must reach the re-armed callback", 2, requests)
            assertFalse(visible)
            assertEquals("Both requests belong to the modal, not the page", initialPageBacks, pageBacks)
            compose.onAllNodesWithTag(sheetTag(backend), useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithTag("${sheetTag(backend)}-host", useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
}
