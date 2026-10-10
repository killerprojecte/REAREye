package hk.uwu.reareye.ui.components.motion

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ArtVisibilityMotionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null

    @After
    fun tearDown() {
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun noticeInsertionAndRemovalKeepChildrenAtTheirLayoutPositions() {
        val showNotice = mutableStateOf(false)
        setContent { HomeFixture(showNotice.value) }
        compose.waitForIdle()
        assertGap("working", "following", 12f)

        repeat(3) {
            compose.runOnIdle { showNotice.value = true }
            compose.waitForIdle()
            assertGap("working", "notice", 10f)
            assertGap("notice", "following", 12f)

            compose.runOnIdle { showNotice.value = false }
            compose.waitForIdle()
            assertGap("working", "following", 12f)
        }
    }

    @Test
    fun contentResizeDuringEntranceLeavesNoTranslationAfterAnimation() {
        val showNotice = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        setContent { HomeFixture(showNotice.value) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(48)
        compose.runOnUiThread { showNotice.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertGap("working", "notice", 10f)
        assertGap("notice", "following", 12f)

        compose.runOnIdle { showNotice.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("notice").assertDoesNotExist()
        assertGap("working", "following", 12f)
    }

    @Test
    fun staggeredAboutCardsDoNotAddSpaceBelowUnanimatedResourceRow() {
        setContent {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .width(360.dp)
                        .height(73.dp)
                        .testTag("resource-row")
                )
                ArtStaggeredReveal(visible = true, revealKey = "contributors", delayMillis = 36) {
                    Box(
                        Modifier
                            .width(360.dp)
                            .height(73.dp)
                            .testTag("contributors")
                    )
                }
                ArtStaggeredReveal(visible = true, revealKey = "links", delayMillis = 54) {
                    Box(
                        Modifier
                            .width(360.dp)
                            .height(73.dp)
                            .testTag("links")
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertGap("resource-row", "contributors", 8f)
        assertGap("contributors", "links", 8f)
        compose.onNodeWithTag("contributors").assertHeightIsEqualTo(73.dp)
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent(content = content)
        }
    }

    private fun assertGap(above: String, below: String, expectedDp: Float) {
        // Inspect the children inside the animated layer, not the untransformed wrapper bounds.
        val aboveBounds = compose.onNodeWithTag(above).getUnclippedBoundsInRoot()
        val belowBounds = compose.onNodeWithTag(below).getUnclippedBoundsInRoot()
        assertEquals(expectedDp, (belowBounds.top - aboveBounds.bottom).value, 0.5f)
    }

    @Composable
    private fun HomeFixture(showNotice: Boolean) {
        AnimatedContent(targetState = "home") { screen ->
            check(screen == "home")
            LazyColumn(
                Modifier
                    .width(360.dp)
                    .height(600.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ArtVisibilityMotion(
                            visible = true,
                            enterAlphaDurationMillis = 320,
                            enterTransformDurationMillis = 420,
                            exitAlphaDurationMillis = 120,
                            exitTransformDurationMillis = 120,
                            hiddenEnterScale = 1f,
                            hiddenExitScale = 1f,
                            slideDivisor = 8,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier
                                        .width(360.dp)
                                        .height(190.dp)
                                        .testTag("working")
                                )
                                if (showNotice) {
                                    Box(
                                        Modifier
                                            .width(360.dp)
                                            .height(100.dp)
                                            .testTag("notice")
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .width(360.dp)
                                .height(80.dp)
                                .testTag("following")
                        )
                    }
                }
            }
        }
    }
}
