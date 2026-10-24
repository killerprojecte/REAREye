package hk.uwu.reareye.ui.components.markdown

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import coil3.ImageLoader
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.ByteArrayOutputStream

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownFeaturesRenderTest {
    @get:Rule
    val screenshotName = org.junit.rules.TestName()
    @get:Rule
    val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var loader: ImageLoader? = null
    private var server: MockWebServer? = null
    @After
    fun tearDown() {
        try {
            if (activity != null) exportMarkdownScreenshot(
                compose,
                "features-${screenshotName.methodName}"
            )
        } finally {
            compose.runOnUiThread { activity?.close() }
            loader?.shutdown()
            server?.close()
        }
    }

    private fun host(mode: State<ColorSchemeMode>? = null, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                .also { controller ->
                    controller.get().setContent {
                        MiuixTheme(ThemeController(mode?.value ?: ColorSchemeMode.Light)) {
                            CompositionLocalProvider(LocalMarkdownImageLoader provides loader) {
                                Box(
                                    Modifier
                                        .background(MiuixTheme.colorScheme.background)
                                        .width(320.dp)
                                        .verticalScroll(rememberScrollState())
                                ) { content() }
                            }
                        }
                    }
                }
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring, useUnmergedTree = true).fetchSemanticsNodes()
            .isNotEmpty()
    }

    private fun layout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun highlighted(text: String) =
        layout(text).layoutInput.text.spanStyles.any { it.item.color != androidx.compose.ui.graphics.Color.Unspecified }

    private fun awaitImages(state: String, count: Int = 1) {
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("markdown-image-$state", useUnmergedTree = true)
                    .fetchSemanticsNodes().size == count
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val errors = org.robolectric.shadows.ShadowLog.getLogsForTag("MaryMarkdown")
                .joinToString("\n") { it.throwable?.stackTraceToString().orEmpty() }
            throw AssertionError(
                "$errors\n${
                    compose.onRoot(useUnmergedTree = true).printToString()
                }", failure
            )
        }
    }

    private fun startServer(): MockWebServer {
        // Robolectric's native ImageDecoder is not implemented on Windows. Use Android's
        // real BitmapFactory decoder here; requests still travel through the real HTTP backend.
        loader = ImageLoader.Builder(RuntimeEnvironment.getApplication())
            .memoryCache {
                coil3.memory.MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build()
            }
            .diskCache(null)
            .components { add(coil3.decode.BitmapFactoryDecoder.Factory()) }.build()
        return MockWebServer().also { it.start(); server = it }
    }

    private fun imageResponse(width: Int = 8, height: Int = 8): MockResponse {
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(android.graphics.Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        return MockResponse.Builder().setHeader("Content-Type", "image/png")
            .body(Buffer().write(bytes)).build()
    }

    @Test
    fun networkLoaderActuallyFetchesAndDecodesPng() {
        val http = startServer()
        http.enqueue(imageResponse())
        val result = kotlinx.coroutines.runBlocking {
            loader!!.execute(
                coil3.request.ImageRequest.Builder(RuntimeEnvironment.getApplication())
                    .data(http.url("/direct.png").toString()).size(1024, 1024).build()
            )
        }
        assertTrue(
            (result as? coil3.request.ErrorResult)?.throwable?.stackTraceToString()
                ?: result.toString(), result is coil3.request.SuccessResult
        )
        assertEquals(1, http.requestCount)
    }

    @Test
    fun taskMarkersRenderReadOnlyStatesIncludingUppercaseAndNestedOrderedTasks() {
        host { MarkdownText("- [ ] todo\n- [x] done\n- [X] uppercase\n  - [ ] nested\n\n1. [X] ordered") }
        listOf("todo", "done", "uppercase", "nested", "ordered").forEach { awaitText(it, true) }
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
        compose.waitUntil(10_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 5
        }
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(
            listOf(
                ToggleableState.Off,
                ToggleableState.On,
                ToggleableState.On,
                ToggleableState.Off,
                ToggleableState.On
            ),
            nodes.map { it.config[SemanticsProperties.ToggleableState] })
        nodes.forEach {
            assertEquals(
                androidx.compose.ui.semantics.Role.Checkbox,
                it.config[SemanticsProperties.Role]
            )
            assertTrue(it.config.contains(SemanticsProperties.Disabled))
            assertFalse(it.config.contains(SemanticsActions.OnClick))
        }
        assertTrue(
            compose.onAllNodesWithText("[X]", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun fencedCodeHighlightsAndCopiesExactCodeWithoutFenceOrLanguage() {
        val code = "val answer = 42\nprintln(\"中文\")"
        host { MarkdownText("```kotlin\n$code\n```") }
        awaitText(code)
        compose.waitUntil(15_000) { highlighted(code) }
        compose.onNodeWithContentDescription("Copy code", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            val clipboard =
                RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
            assertEquals(code, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test
    fun fencedCodeCopyPreservesMeaningfulIndentationBlankLinesAndTrailingSpaces() {
        val code = "\n    print(1)  \n      print(2)\n"
        host { MarkdownText("```python\n$code\n```") }
        awaitText(code)
        compose.onNodeWithContentDescription("Copy code", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(
                code,
                RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString()
            )
        }
    }

    @Test
    fun longCodeScrollsHorizontallyButCopiesTheWholeSource() {
        val code = "val longValue = \"" + "中文long-code-".repeat(80) + "\""
        host { MarkdownText("```kotlin\n$code\n```") }
        awaitText(code)
        val horizontal =
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        compose.waitUntil(15_000) {
            compose.onAllNodes(horizontal, useUnmergedTree = true).fetchSemanticsNodes().any {
                it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0
            }
        }
        compose.onAllNodes(horizontal, useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(80f, 0f) }
        compose.onNodeWithContentDescription("Copy code", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(
                code,
                RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString()
            )
        }
    }

    @Test
    fun unknownLanguageAndIndentedCodeRemainPlainAndCopyable() {
        val code = "not_a_real_language(42)"
        host { MarkdownText("```made-up-language\n$code\n```\n\n    indented value") }
        awaitText(code); awaitText("indented value")
        assertFalse(highlighted(code))
        assertEquals(
            2,
            compose.onAllNodesWithContentDescription("Copy code", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
        compose.onAllNodesWithContentDescription(
            "Copy code",
            useUnmergedTree = true
        )[1].performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                "indented value",
                RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString()
            )
        }
    }

    @Test
    fun popularLanguageBlocksUseOnlySupportedHighlightersWithoutSharingBuilderState() {
        val cases = linkedMapOf(
            "kotlin" to "val kotlinValue = 1",
            "java" to "class JavaValue { int number = 2; }",
            "javascript" to "const jsValue = 3;",
            "json" to "{\"jsonValue\": 4}",
            "python" to "def python_value(): return 5",
            "bash" to "echo \"shell value\"",
            "lua" to "local lua_value = 7",
        )
        host { MarkdownText(cases.entries.joinToString("\n\n") { (language, code) -> "```$language\n$code\n```" }) }
        cases.forEach { (language, code) ->
            awaitText(code)
            if (dev.snipme.highlights.model.SyntaxLanguage.getByName(language) != null) {
                compose.waitUntil(15_000) { highlighted(code) }
            } else assertFalse(
                "Unsupported $language must stay plain rather than use another lexer",
                highlighted(code)
            )
        }
        assertEquals(
            cases.size,
            compose.onAllNodesWithContentDescription("Copy code", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun themeLanguageAndStreamReplacementRefreshHighlightAndCopyContent() {
        val code = "val answer = 42"
        val markdown = mutableStateOf("```kotlin\n$code\n```")
        val mode = mutableStateOf(ColorSchemeMode.Light)
        host(mode) { MarkdownText(markdown.value) }
        awaitText(code)
        compose.waitUntil(15_000) { highlighted(code) }
        val light = layout(code).layoutInput.text.spanStyles
        compose.runOnIdle { mode.value = ColorSchemeMode.Dark }
        compose.waitUntil(15_000) { highlighted(code) && layout(code).layoutInput.text.spanStyles != light }
        compose.runOnIdle { markdown.value = "```made-up-language\n$code\n```" }
        compose.waitUntil(15_000) { !highlighted(code) }
        compose.runOnIdle { markdown.value = "```kotlin\nval changed = 7" }
        awaitText("val changed = 7")
        compose.runOnIdle { markdown.value += "\n```" }
        compose.onNodeWithContentDescription("Copy code", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(
                "val changed = 7",
                RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString()
            )
        }
    }

    @Test
    fun blockInlineAndReferenceRemoteImagesLoadFromRealHttpWithoutDuplicateRequestsOnRecompose() {
        val http = startServer()
        repeat(3) { http.enqueue(imageResponse()) }
        val markdown = mutableStateOf(
            "![block](${http.url("/block.png")})\n\nBefore ![inline](${http.url("/inline.png")}) after\n\n![reference][picture]\n\n[picture]: ${
                http.url("/reference.png")
            }"
        )
        host { MarkdownText(markdown.value) }
        awaitImages("success", 3)
        assertEquals(3, http.requestCount)
        compose.runOnIdle { markdown.value += "\n\n**Recomposed**" }
        awaitText("Recomposed"); awaitImages("success", 3)
        assertEquals(3, http.requestCount)
        compose.onAllNodesWithTag("markdown-image-success", useUnmergedTree = true)
            .fetchSemanticsNodes().forEach {
            assertTrue(it.boundsInRoot.width <= 320f * RuntimeEnvironment.getApplication().resources.displayMetrics.density + 1f)
        }
    }

    @Test
    fun largeImagePromotionAndNewAstKeepOneRequestAndAspectRatio() {
        val http = startServer()
        http.enqueue(imageResponse(200, 120))
        val markdown = mutableStateOf("Before ![large image](${http.url("/large.png")}) after")
        host { MarkdownText(markdown.value) }
        awaitImages("success")
        compose.waitUntil(10_000) {
            val bounds = compose.onNodeWithTag("markdown-image-success", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            kotlin.math.abs(bounds.width.value / bounds.height.value - 200f / 120f) < .05f
        }
        assertEquals(1, http.requestCount)
        compose.runOnIdle { markdown.value += "\n\n**Updated after promotion**" }
        awaitText("Updated after promotion"); awaitImages("success")
        assertEquals(1, http.requestCount)
    }

    @Test
    fun imageLoadingAnd404ExposeStableStatesAndAlternativeText() {
        val http = startServer()
        host { MarkdownText("![missing alternative](${http.url("/missing.png")})") }
        compose.waitUntil(15_000) { http.requestCount >= 1 }
        awaitImages("loading")
        http.enqueue(MockResponse.Builder().code(404).body("not found").build())
        awaitImages("error")
        compose.onNodeWithContentDescription("missing alternative", useUnmergedTree = true)
            .assertExists()
        assertEquals("An error must not cause automatic duplicate fetches", 1, http.requestCount)
    }

    @Test
    fun corruptRemoteImageFailsGracefullyAndSnapshotReplacementRemovesIt() {
        val http = startServer()
        http.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "image/png").body("corrupt").build()
        )
        val markdown = mutableStateOf("![broken](${http.url("/broken.png")})")
        host { MarkdownText(markdown.value) }
        awaitImages("error")
        compose.runOnIdle { markdown.value = "**New session**" }
        awaitText("New session")
        assertTrue(
            compose.onAllNodesWithTag("markdown-image-error", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun localAndScriptImageSchemesAreBlockedWithoutNetworkRequests() {
        val http = startServer()
        host { MarkdownText("![local](file:///private/image.png)\n\n![provider](content://private/image)\n\n![script](javascript:alert)") }
        awaitImages("blocked", 3)
        assertEquals(0, http.requestCount)
    }
}
