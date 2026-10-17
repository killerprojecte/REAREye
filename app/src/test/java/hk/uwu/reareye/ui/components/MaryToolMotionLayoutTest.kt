package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.PendingApproval
import hk.uwu.reareye.agent.loop.ToolCallRequest
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import kotlin.math.abs

/** Sample actual tool-card measure/draw passes with the animation clock stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryToolMotionLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null

    @Before
    fun requireExplicitResourceMode() {
        assumeTrue(
            "Run with -PmaryUiResources=true",
            java.lang.Boolean.getBoolean("mary.ui.resources")
        )
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun ordinaryToolExpandsWithoutInitialShrinkAndCollapsesWithoutBounceOrEndJump() {
        val entry = ordinaryEntry("ordinary-motion")
        hostCard(entry)
        val compact = height(entry.id)
        compose.mainClock.autoAdvance = false

        node(header(entry.id)).performClick()
        val opening = listOf(compact) + sample(entry.id, screenshotPrefix = "tool-expand-frame")
        val expanded = opening.last()
        assertTrue("Use a real nonempty multiline result", expanded > compact + 40f)
        opening.forEach {
            assertTrue(
                "Opening must never shrink below the compact card",
                it >= compact - TOLERANCE
            )
        }
        assertMonotonic(opening, increasing = true)
        assertIntermediate(opening, compact, expanded)
        assertEndpointContinuity(opening, expanded)

        node(header(entry.id)).performClick()
        val closing = listOf(expanded) + sample(entry.id)
        assertMonotonic(closing, increasing = false)
        assertIntermediate(closing, compact, expanded)
        assertEndpointContinuity(closing, compact)
        assertEquals("Return to the exact compact shell", compact, closing.last(), TOLERANCE)
    }

    @Test
    fun approvalParametersAnimateTheirOwnHeightAndSpacingAtBothEnds() {
        val entry = approvalEntry("parameters-motion")
        hostCard(entry, approval(entry))
        val compact = height(entry.id)
        compose.mainClock.autoAdvance = false

        node(arguments(entry.id)).performClick()
        val opening = listOf(compact) + sample(
            entry.id, approval = true,
            screenshotPrefix = "tool-parameters-frame", parameterBase = compact
        )
        val expanded = opening.last()
        assertMonotonic(opening, increasing = true)
        assertIntermediate(opening, compact, expanded)
        assertEndpointContinuity(opening, expanded)

        node(arguments(entry.id)).performClick()
        val closing = listOf(expanded) + sample(
            entry.id,
            approval = true,
            screenshotPrefix = "tool-parameters-frame",
            firstScreenshot = 30,
            parameterBase = compact
        )
        assertMonotonic(closing, increasing = false)
        assertIntermediate(closing, compact, expanded)
        assertEndpointContinuity(closing, compact)
        assertEquals(compact, closing.last(), TOLERANCE)
        assertEquals("The exit subtree must eventually be removed", 0, count(details(entry.id)))
    }

    @Test
    fun rapidHeaderAndParameterReversalsRecoverWithoutDuplicateHeadersOrActions() {
        val entry = approvalEntry("reverse-motion")
        hostCard(entry, approval(entry))
        val base = height(entry.id)
        compose.mainClock.autoAdvance = false
        node(arguments(entry.id)).performClick()
        val fullyExpanded = sample(entry.id, approval = true).last()

        node(arguments(entry.id)).performClick()
        val partial = sample(entry.id, count = 6, approval = true).last()
        assertTrue(
            "Reverse a genuinely unfinished parameter animation",
            partial > base + TOLERANCE && partial < fullyExpanded - TOLERANCE
        )
        node(arguments(entry.id)).performClick()
        val recovered = sample(entry.id, approval = true)
        assertTrue(
            "Reversal must not immediately snap to its target",
            recovered.first() < fullyExpanded - TOLERANCE
        )
        assertEquals(fullyExpanded, recovered.last(), TOLERANCE)

        node(header(entry.id)).performClick()
        val shrinking = sample(entry.id, count = 6).last()
        assertTrue(
            "Reverse while the shell is still collapsing",
            shrinking < fullyExpanded - TOLERANCE
        )
        node(header(entry.id)).performClick()
        val reopened = sample(entry.id, approval = true)
        assertEquals(
            "Recover body, parameters and actions together",
            fullyExpanded,
            reopened.last(),
            TOLERANCE
        )
        assertEquals(1, count(arguments(entry.id)))
        assertEquals(1, count(details(entry.id)))
    }

    @Test
    fun realToolExpansionFollowsTheLazyColumnTailButDoesNotStealADraggedReadersAnchor() {
        val entry = ordinaryEntry("tail-motion")
        lateinit var list: LazyListState
        lateinit var following: MaryTailFollowState
        lateinit var expansion: ToolCardExpansion
        host {
            list = rememberLazyListState()
            following = rememberMaryTailFollowState(list, entry.id)
            expansion = remember { ToolCardExpansion() }
            MaryFollowConversationTail(
                list,
                following.following,
                entry.id,
                canFollow = { following.following })
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier
                    .requiredSize(360.dp, 480.dp)
                    .nestedScroll(following.connection)
                    .testTag("tool-tail-list"),
            ) {
                items(14, key = { "older-$it" }) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    ) { BasicText("Earlier message $index") }
                }
                item(key = entry.id) { tool(entry, expansion) }
            }
        }
        fun assertBottom() {
            compose.runOnIdle {
                assertTrue(following.following)
                assertFalse("Expansion must keep the true end visible", list.canScrollForward)
                val info = list.layoutInfo
                val tail = info.visibleItemsInfo.last()
                assertEquals(info.totalItemsCount - 1, tail.index)
                assertEquals(
                    "Include bottom content padding",
                    (info.viewportEndOffset - info.afterContentPadding).toFloat(),
                    (tail.offset + tail.size).toFloat(),
                    TOLERANCE
                )
            }
        }
        assertBottom()
        val compactHeight = height(entry.id)
        compose.mainClock.autoAdvance = false
        node(header(entry.id)).performClick()
        repeat(24) {
            advance()
            assertBottom()
        }

        // Leave enough scrollback for the whole later collapse; otherwise natural end-of-list
        // clamping (not tail following) would legitimately move the first visible row.
        // The mdpi fixture makes these measured dp values also gesture pixels.
        val dragDistance = height(entry.id) - compactHeight + 48f
        // Let only this real gesture/fling settle automatically, never the measured card animation.
        compose.mainClock.autoAdvance = true
        node("tool-tail-list").performTouchInput {
            swipeDown(startY = 12f, endY = 12f + dragDistance, durationMillis = 800)
        }
        compose.waitForIdle()
        var anchorIndex = 0
        var anchorOffset = 0
        compose.runOnIdle {
            assertFalse("Nested-scroll user intent disarms follow", following.following)
            assertTrue(list.canScrollForward)
            anchorIndex = list.firstVisibleItemIndex
            anchorOffset = list.firstVisibleItemScrollOffset
        }
        compose.mainClock.autoAdvance = false
        repeat(2) {
            // Mutate the same expansion owner used by the header; do not scrollTo an offscreen node.
            compose.runOnIdle { expansion.toggle(entry.id) }
            repeat(24) {
                advance()
                compose.runOnIdle {
                    assertFalse(following.following)
                    assertTrue(list.canScrollForward)
                    assertEquals(anchorIndex, list.firstVisibleItemIndex)
                    assertEquals(
                        anchorOffset.toFloat(),
                        list.firstVisibleItemScrollOffset.toFloat(),
                        TOLERANCE
                    )
                }
            }
        }
    }

    private fun sample(
        id: String,
        count: Int = 24,
        approval: Boolean = false,
        screenshotPrefix: String? = null,
        firstScreenshot: Int = 0,
        parameterBase: Float? = null,
    ): List<Float> = (0 until count).map { index ->
        advance()
        assertEquals("One header throughout motion", 1, count(header(id)))
        assertTrue("Never duplicate actions during reversal", count(actions(id)) <= 1)
        if (approval) {
            assertEquals("Parameter motion retains exactly one actions row", 1, count(actions(id)))
            assertEquals(1, count(arguments(id)))
        }
        height(id).also { cardHeight ->
            if (parameterBase != null) {
                // The AnimatedVisibility container owns ALL added height, including the 8dp gap.
                // An external spacedBy gap appears/disappears instantly and violates this equality.
                val detailsHeight = if (count(details(id)) == 0) 0f else boundsHeight(details(id))
                assertEquals(
                    "No instant external gap at either endpoint", detailsHeight,
                    cardHeight - parameterBase, TOLERANCE
                )
            }
            if (screenshotPrefix != null) screenshot(
                "$screenshotPrefix-${
                    (firstScreenshot + index).toString().padStart(2, '0')
                }.png"
            )
        }
    }

    private fun assertMonotonic(heights: List<Float>, increasing: Boolean) {
        heights.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "Non-bouncy ${if (increasing) "opening" else "closing"}: $heights",
                if (increasing) b >= a - TOLERANCE else b <= a + TOLERANCE
            )
        }
    }

    private fun assertIntermediate(heights: List<Float>, compact: Float, expanded: Float) {
        val middle = heights.filter { it > compact + TOLERANCE && it < expanded - TOLERANCE }
        assertTrue(
            "Several genuinely different intermediate measured heights: $heights",
            middle.map { it.toInt() }.distinct().size >= 4
        )
    }

    private fun assertEndpointContinuity(heights: List<Float>, endpoint: Float) {
        // Ignore 0ms target scheduling: locate arrival from observed geometry, not frame number.
        val arrival = heights.indexOfFirst { abs(it - endpoint) <= TOLERANCE }
        assertTrue("The animation must reach its endpoint", arrival > 0)
        assertTrue(
            "No last-frame height jump: $heights",
            abs(heights[arrival] - heights[arrival - 1]) <= 4f
        )
        heights.drop(arrival).forEach { assertEquals(endpoint, it, TOLERANCE) }
    }

    private fun advance() {
        check(!compose.mainClock.autoAdvance)
        compose.mainClock.advanceTimeBy(16)
        // Force Android's measure/draw as well as Compose's paused animation clock.
        compose.waitForIdle()
    }

    private fun hostCard(entry: ChatEntry.ToolCall, approval: PendingApproval? = null) = host {
        Column(
            Modifier
                .requiredSize(360.dp, 700.dp)
                .background(Color(0xFFF4F5F7))
                .padding(16.dp)
                .testTag(HOST)
        ) {
            tool(entry, remember { ToolCardExpansion() }, approval)
        }
    }

    @Composable
    private fun tool(
        entry: ChatEntry.ToolCall,
        expansion: ToolCardExpansion,
        approval: PendingApproval? = null
    ) {
        MaryToolCallCard(
            entry, expansion, pendingApproval = approval,
            onAnswer = { _, _, _ -> false }, onApprove = { _, _ -> false })
    }

    private fun host(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                MiuixTheme(ThemeController(ColorSchemeMode.Light)) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val directory = File(requireNotNull(System.getProperty("mary.ui.screenshotDir")))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        val image = node(HOST).captureToImage().asAndroidBitmap()
        File(directory, name).outputStream()
            .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun ordinaryEntry(id: String) = ChatEntry.ToolCall(
        id = id, name = "Read", argumentsJson = """{"path":"notes.txt","start_line":1}""",
        result = ToolResult("First result line\nSecond result line\nThird result line\nFourth result line"),
        running = false, callId = "call-$id",
    )

    private fun approvalEntry(id: String) = ChatEntry.ToolCall(
        id = id,
        name = "Write",
        argumentsJson = """{"path":"notes.txt","content":"First line\nSecond line\nThird line","overwrite":true}""",
        running = true,
        callId = "call-$id",
    )

    private fun approval(entry: ChatEntry.ToolCall) = PendingApproval(
        ToolCallRequest("call-${entry.id}", entry.name, entry.argumentsJson, ToolRisk.MUTATING),
        CompletableDeferred(), "approval-${entry.id}",
    )

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun count(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun boundsHeight(tag: String) = node(tag).getUnclippedBoundsInRoot().height.value
    private fun height(id: String) = boundsHeight("mary-tool-card-$id")
    private fun header(id: String) = "mary-tool-header-$id"
    private fun actions(id: String) = "mary-confirmation-actions-$id"
    private fun arguments(id: String) = "mary-tool-arguments-$id"
    private fun details(id: String) = "mary-tool-details-$id"

    private companion object {
        const val HOST = "tool-motion-host"
        const val TOLERANCE = 2f // mdpi host: allow 1–2px integer measure rounding.
    }
}
