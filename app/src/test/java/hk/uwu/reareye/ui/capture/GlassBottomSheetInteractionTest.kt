package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheet
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Text
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

/** Real Compose pointer/Back integration; not device, IME, or TalkBack coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassBottomSheetInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var allowed by mutableStateOf(true)
    private var session by mutableStateOf(0)
    private var closeOnRequest = true
    private var requests = 0
    private var finished = 0
    private var pageBacks = 0
    private val scroll = ScrollState(0)

    private fun open() {
        // Opening/revealing always has a running clock. Only freeze a settled sheet.
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Mochi, MixColorSchemeMode.Light) {
                    GlassBottomSheetScene {
                        Box(Modifier.fillMaxSize().background(Color(0xFF205080)))
                        BackHandler { pageBacks++ }
                        GlassBottomSheet(
                            show = visible,
                            sessionKey = session,
                            title = "Interaction sheet",
                            allowDismiss = allowed,
                            onDismissRequest = { requests++; if (closeOnRequest) visible = false },
                            onDismissFinished = { finished++ },
                        ) {
                            Column(Modifier.fillMaxSize().testTag("interaction-body").verticalScroll(scroll)) {
                                repeat(40) { index ->
                                    Box(Modifier.fillMaxWidth().height(64.dp)) { Text("Row $index") }
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()
        sheet().assertIsDisplayed()
        assertTrue("Fixture must have real scroll overflow", scroll.maxValue > 600)
    }

    private fun sheet() = compose.onNodeWithTag("mochi-glass-sheet", useUnmergedTree = true)
    private fun body() = compose.onNodeWithTag("interaction-body", useUnmergedTree = true)
    private fun settle() {
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun headerDrag() {
        // The first 20 dp are the real draggable handle, above the scroll content.
        sheet().performTouchInput {
            swipe(Offset(centerX, 10f), Offset(centerX, height * 0.55f), durationMillis = 800)
        }
    }
    private fun assertRemoved() {
        compose.onAllNodesWithTag("mochi-glass-sheet", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("mochi-glass-sheet-host", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun contentConsumesDownwardPointerUntilTopThenTransfersToSheet() {
        open()
        val restingTop = sheet().fetchSemanticsNode().boundsInRoot.top
        body().performTouchInput {
            swipe(Offset(centerX, height * 0.85f), Offset(centerX, height * 0.25f), durationMillis = 1_000)
        }
        settle()
        val scrolled = scroll.value
        assertTrue("Pointer up must scroll actual content", scrolled > 100)
        body().performTouchInput {
            swipe(Offset(centerX, height * 0.3f), Offset(centerX, height * 0.3f + 60f), durationMillis = 1_000)
        }
        settle()
        assertTrue("Downward pointer is consumed by content above its top", scroll.value in 1 until scrolled)
        assertEquals(0, requests)
        assertEquals(restingTop, sheet().fetchSemanticsNode().boundsInRoot.top, 1f)
        // Position the fixture at its boundary, then test transfer using pointer input.
        body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
        settle()
        assertEquals(0, scroll.value)
        body().performTouchInput {
            swipe(Offset(centerX, height * 0.1f), Offset(centerX, height * 0.8f), durationMillis = 800)
        }
        settle()
        assertFalse(visible)
        assertEquals(1, requests)
        assertEquals(1, finished)
        assertEquals(0, pageBacks)
        assertRemoved()
    }

    @Test fun dismissalVetoBlocksHeaderContentAndBackButLeavesContentScrollable() {
        allowed = false
        open()
        val restingTop = sheet().fetchSemanticsNode().boundsInRoot.top
        headerDrag()
        settle()
        body().performTouchInput { swipeDown() }
        settle()
        back()
        settle()
        assertTrue(visible)
        assertEquals(0, requests)
        assertEquals(0, finished)
        assertEquals(0, pageBacks)
        assertEquals(restingTop, sheet().fetchSemanticsNode().boundsInRoot.top, 1f)
        body().performTouchInput { swipeUp() }
        settle()
        assertTrue("Saving must not disable the scroll container", scroll.value > 0)
        compose.runOnUiThread { allowed = true }
        compose.waitForIdle()
        headerDrag()
        settle()
        assertEquals("The same real handle works once allowed", 1, requests)
        assertEquals(1, finished)
        assertRemoved()
    }

    @Test fun sessionKeyResetsSameFrameDismissGateAndCancelsInterruptedFinishedCallback() {
        closeOnRequest = false
        open()
        back()
        settle()
        assertEquals(1, requests)
        assertTrue(visible)
        back()
        settle()
        assertEquals("One request per session even before owner closes", 1, requests)
        compose.runOnUiThread {
            visible = false
            session++
            visible = true // No show=false composition: only sessionKey distinguishes this open.
            closeOnRequest = true
        }
        settle()
        compose.onAllNodesWithTag("mochi-glass-sheet", useUnmergedTree = true).assertCountEquals(1)
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertEquals("A new session must accept a new dismissal", 2, requests)
        assertEquals(0, finished)
        compose.runOnUiThread { session++; visible = true }
        settle()
        compose.onAllNodesWithTag("mochi-glass-sheet", useUnmergedTree = true).assertCountEquals(1)
        sheet().assertIsDisplayed()
        assertEquals("Disposed exit must not finish into the new session", 0, finished)
        back()
        settle()
        assertEquals(3, requests)
        assertEquals(1, finished)
        assertRemoved()
    }

    @Test fun retainedExitConsumesBackUntilAnimationFinishesThenRestoresPageBack() {
        open()
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertFalse(visible)
        assertEquals(1, requests)
        assertEquals(0, finished)
        compose.onAllNodesWithTag("mochi-glass-sheet-host", useUnmergedTree = true).assertCountEquals(1)
        repeat(3) { back() }
        assertEquals("A retained overlay must consume Back during its frozen exit", 0, pageBacks)
        assertEquals("Exit Back must not request another dismissal", 1, requests)
        assertEquals("Clock is still frozen before completion", 0, finished)
        settle()
        assertEquals(1, finished)
        assertRemoved()
        back()
        assertEquals("Page Back resumes only after host removal", 1, pageBacks)
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
}
