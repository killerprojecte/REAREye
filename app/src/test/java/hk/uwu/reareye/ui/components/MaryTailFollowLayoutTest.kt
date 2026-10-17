package hk.uwu.reareye.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.ui.screen.EntryAppearance
import kotlinx.coroutines.launch
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

/** Exercise the real nested-scroll user-intent path, not the state's public helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryTailFollowLayoutTest {
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
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun draggingAwayPausesAppendsAndExpansionUntilAnExplicitJumpRestoresFollowing() {
        exerciseTouchDrivenFollowing(tailHeight = 80.dp, contentPadding = PaddingValues())
    }

    @Test
    fun draggingWithinATallTailWithContentPaddingPreservesTheReaderAndJumpFindsItsTrueBottom() {
        exerciseTouchDrivenFollowing(
            tailHeight = 980.dp,
            contentPadding = PaddingValues(top = 24.dp, bottom = 96.dp),
        )
    }

    @Test
    fun recycledMessageKeepsItsEntranceStateWhileNewMessagesStillAnimate() {
        val visible = mutableStateOf(0)
        val firstPositions = mutableMapOf<Int, MutableList<Float>>()
        host {
            val holder = rememberSaveableStateHolder()
            Box(Modifier
                .requiredSize(360.dp, 240.dp)
                .testTag("appearance-list")) {
                val id = visible.value
                androidx.compose.runtime.key(id) {
                    holder.SaveableStateProvider(id) {
                        EntryAppearance {
                            val recorded = remember { booleanArrayOf(false) }
                            Box(Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .onGloballyPositioned {
                                    if (!recorded[0]) {
                                        recorded[0] = true
                                        firstPositions.getOrPut(id) { mutableListOf() }
                                            .add(it.positionInRoot().y)
                                    }
                                }) { BasicText("Message $id") }
                        }
                    }
                }
            }
        }
        val top = compose.onNodeWithTag("appearance-list").fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { visible.value = 1 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(
                "A new message starts with its entrance lift",
                firstPositions.getValue(1).single() > top + 1f
            )
            visible.value = 0
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val oldPositions = firstPositions.getValue(0)
            assertEquals("The old row must be disposed and composed again", 2, oldPositions.size)
            assertEquals(
                "The old row must not replay its entrance lift",
                top,
                oldPositions.last(),
                0.5f
            )
        }
    }

    private data class Row(val id: Int, val height: Dp)
    private data class Anchor(val index: Int, val offset: Int)

    private fun exerciseTouchDrivenFollowing(tailHeight: Dp, contentPadding: PaddingValues) {
        val rows = mutableStateOf(List(12) { Row(it, 80.dp) } + Row(12, tailHeight))
        lateinit var list: LazyListState
        lateinit var tailFollow: MaryTailFollowState
        lateinit var jumpToLatest: () -> Unit
        var userInputEvents = 0
        var completedJumps = 0
        val observeInput = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) userInputEvents++
                return Offset.Zero
            }
        }
        host {
            list = rememberLazyListState()
            tailFollow = rememberMaryTailFollowState(list, "touch-tail-test")
            val scope = rememberCoroutineScope()
            SideEffect {
                // Match MaryScreen: finish the explicit jump, then re-arm streaming follow.
                jumpToLatest = {
                    scope.launch {
                        scrollMaryConversationToBottom(list)
                        tailFollow.followLatest()
                        completedJumps++
                    }
                }
            }
            MaryFollowConversationTail(
                list,
                following = tailFollow.following,
                sessionKey = "touch-tail-test",
                canFollow = { tailFollow.following },
            )
            Box(
                Modifier
                    .requiredSize(360.dp, 480.dp)
                    .nestedScroll(observeInput)
                    .nestedScroll(tailFollow.connection),
            ) {
                LazyColumn(
                    state = list,
                    contentPadding = contentPadding,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("mary-tail-list"),
                ) {
                    items(rows.value, key = { it.id }) { row ->
                        Box(Modifier
                            .fillMaxWidth()
                            .height(row.height)) {
                            BasicText(
                                "End of message ${row.id}",
                                Modifier.align(Alignment.BottomStart)
                            )
                        }
                    }
                }
            }
        }

        fun assertFollowingAtTrueBottom() {
            compose.runOnIdle {
                assertTrue("Following must be armed", tailFollow.following)
                assertFalse("The list must be idle", list.isScrollInProgress)
                assertFalse("The actual end must be reached", list.canScrollForward)
                val info = list.layoutInfo
                val last = info.visibleItemsInfo.last()
                assertEquals(rows.value.lastIndex, last.index)
                val tailBottom = last.offset + last.size
                val paddedEdge = info.viewportEndOffset - info.afterContentPadding
                assertTrue(
                    "Respect bottom content padding, including for oversized rows",
                    tailBottom in (paddedEdge - 2)..(paddedEdge + 2)
                )
            }
        }

        fun growLastRow() {
            compose.runOnIdle {
                rows.value = rows.value.dropLast(1) + rows.value.last()
                    .let { it.copy(height = it.height + 200.dp) }
            }
        }

        fun appendRow() {
            compose.runOnIdle { rows.value = rows.value + Row(rows.value.size, tailHeight) }
        }

        assertFollowingAtTrueBottom()
        growLastRow()
        assertFollowingAtTrueBottom()
        appendRow()
        assertFollowingAtTrueBottom()

        // A real downward finger gesture scrolls back towards older content. A slow
        // swipe avoids a large fling; waitForIdle also lets any remaining fling finish.
        compose.onNodeWithTag("mary-tail-list").performTouchInput {
            swipeDown(startY = height * 0.2f, endY = height * 0.8f, durationMillis = 800)
        }
        compose.waitForIdle()
        lateinit var readerAnchor: Anchor
        compose.runOnIdle {
            assertTrue(
                "The gesture must dispatch NestedScrollSource.UserInput",
                userInputEvents > 0
            )
            assertFalse("A real user drag must pause following", tailFollow.following)
            assertFalse("The gesture must have finished", list.isScrollInProgress)
            assertTrue("The reader must really have left the bottom", list.canScrollForward)
            readerAnchor = Anchor(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }

        fun assertReaderNotPulledBack() {
            compose.runOnIdle {
                assertFalse("Incoming content must not re-arm following", tailFollow.following)
                assertTrue(
                    "Incoming content must not pull the reader to the end",
                    list.canScrollForward
                )
                assertEquals(
                    "Keep the same visible row",
                    readerAnchor.index,
                    list.firstVisibleItemIndex
                )
                assertEquals(
                    "Keep the same offset within that row",
                    readerAnchor.offset,
                    list.firstVisibleItemScrollOffset
                )
            }
        }

        growLastRow()
        assertReaderNotPulledBack()
        appendRow()
        assertReaderNotPulledBack()
        growLastRow()
        assertReaderNotPulledBack()

        compose.runOnIdle { jumpToLatest() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "The explicit scrolling coroutine must finish",
                1,
                completedJumps
            )
        }
        assertFollowingAtTrueBottom()
        growLastRow()
        assertFollowingAtTrueBottom()
        appendRow()
        assertFollowingAtTrueBottom()
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
}
