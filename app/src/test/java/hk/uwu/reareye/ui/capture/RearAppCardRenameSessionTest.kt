package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearAppCardRepository
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.RearAppCardManagementContent
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.widgetapi.RearAppApiClient
import hk.uwu.reareye.widgetapi.RearAppApiContract
import hk.uwu.reareye.widgetapi.RearAppCardInfo
import hk.uwu.reareye.widgetapi.RearAppOperationResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real list -> repository -> controlled IO -> the still-mounted management page.
 * No synthetic rename scene or Activity recreation is used to isolate sessions.
 * Native Robolectric exercises both backends; it is not physical-device/IME proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearAppCardRenameSessionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null

    @Before fun enabled() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    private data class Request(val appId: String, val title: String)

    private class PendingRename(val success: Boolean = true) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val timedOut = AtomicBoolean(false)
    }

    private class ControlledClient(private val pending: List<PendingRename>) : RearAppApiClient() {
        val requests = CopyOnWriteArrayList<Request>()
        val active = AtomicInteger(0)
        private val currentTitle = AtomicReference(ORIGINAL_TITLE)

        override fun bind(context: Context, onConnected: (() -> Unit)?): Boolean {
            onConnected?.invoke()
            return true
        }

        override fun getCatalog(): Bundle = Bundle().apply {
            putParcelableArrayList(
                RearAppApiContract.BundleKeys.ITEMS,
                ArrayList(listOf(currentTitle.get(), "Weather forecast", "Train journey")
                    .mapIndexed { index, title ->
                        RearAppCardInfo(
                            "app-$index", title, null, "", null,
                            true, true, true, false, 0,
                        ).toBundle()
                    }),
            )
        }

        override fun renameAppCard(appId: String, title: String): Bundle {
            active.incrementAndGet()
            val index = requests.size
            requests += Request(appId, title)
            val gate = pending.getOrNull(index)
            try {
                checkNotNull(gate) { "Unexpected duplicate rename request: $appId/$title" }
                gate.entered.countDown()
                // Only the repository's IO worker blocks. The test keeps pumping Compose/main.
                if (!gate.release.await(30, TimeUnit.SECONDS)) {
                    gate.timedOut.set(true)
                    error("Timed out waiting for controlled rename release")
                }
                if (gate.success) currentTitle.set(title)
                return RearAppOperationResult(
                    success = gate.success,
                    error = if (gate.success) null else "Rename rejected by fixture",
                ).toBundle()
            } finally {
                gate?.finished?.countDown()
                active.decrementAndGet()
            }
        }
    }

    private fun settle() {
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            condition()
        }
    }

    private fun hasTextNode(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasDialog() =
        compose.onAllNodesWithTag("app-card-rename-actions").fetchSemanticsNodes().isNotEmpty()

    // Mochi's decoration container has the tag, but its editable child owns SetText.
    private fun editor() = compose.onNode(
        hasSetTextAction() and (hasTestTag("app-card-rename-title") or
            hasAnyAncestor(hasTestTag("app-card-rename-title"))),
        useUnmergedTree = true,
    )

    private fun action(resource: Int) = compose.onNode(
        hasText(activity!!.get().getString(resource)) and
            hasAnyAncestor(hasTestTag("app-card-rename-actions")),
    )

    private fun confirm() = action(R.string.rear_widget_confirm)
    private fun cancel() = action(R.string.rear_widget_cancel)

    private fun assertDraft(title: String) {
        editor().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString(title),
        ))
    }

    private fun replaceTitle(title: String) {
        editor().performTextReplacement(title)
        settle()
        assertDraft(title)
    }

    private fun openFromList(title: String) {
        compose.onNodeWithText(title).performClick()
        settle()
        confirm().assertIsDisplayed()
        // Native dialogs request focus themselves; do not fake it with an editor tap.
        editor().assertIsFocused()
        assertDraft(title)
    }

    private fun waitForRequest(client: ControlledClient, gate: PendingRename, count: Int) {
        awaitUi { gate.entered.count == 0L }
        settle()
        assertEquals(count, client.requests.size)
        confirm().assertIsNotEnabled()
    }

    private fun waitForSaved(title: String) {
        awaitUi { !hasDialog() && hasTextNode(title) }
        settle()
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    private fun withManagement(
        backend: String,
        pending: List<PendingRename>,
        test: (ControlledClient) -> Unit,
    ) {
        val clientField = RearAppCardRepository::class.java.getDeclaredField("remoteClient")
            .apply { isAccessible = true }
        val previousClient = clientField.get(RearAppCardRepository)
        val client = ControlledClient(pending)
        try {
            clientField.set(RearAppCardRepository, client)
            val prefs = PrefsManager(GuideMemoryPrefs())
            compose.runOnUiThread {
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme(backend, false) {
                        RearAppCardManagementContent(prefsManager = prefs)
                    }
                }
            }
            awaitUi { hasTextNode(ORIGINAL_TITLE) }
            settle()
            test(client)
            assertTrue("Controlled IO must not reach its timeout", pending.none { it.timedOut.get() })
        } finally {
            // Release even when a UI assertion fails; never leak a blocked IO worker or fake client.
            pending.forEach { it.release.countDown() }
            try {
                awaitUi { client.active.get() == 0 }
            } finally {
                try {
                    compose.runOnUiThread { activity?.close(); activity = null }
                } finally {
                    clientField.set(RearAppCardRepository, previousClient)
                }
            }
        }
    }

    @Test fun pendingSubmissionIsSingleFlightAndUsesAnImmutableTitleSnapshot() {
        for (backend in listOf("miuix", "mochi")) {
            val request = PendingRename()
            withManagement(backend, listOf(request)) { client ->
                openFromList(ORIGINAL_TITLE)
                replaceTitle("  Submitted snapshot  ")
                confirm().performClick()
                waitForRequest(client, request, 1)
                replaceTitle("Edited while the request is pending")
                repeat(4) {
                    // Actual taps, including on a disabled action, must not enqueue more writes.
                    confirm().performTouchInput { click(center) }
                }
                settle()
                assertEquals(listOf(Request("app-0", "Submitted snapshot")), client.requests.toList())
                request.release.countDown()
                waitForSaved("Submitted snapshot")
                assertEquals("Repeated pending taps must not be queued behind repository IO", 1, client.requests.size)
                openFromList("Submitted snapshot")
                cancel().performClick()
                settle()
            }
        }
    }

    @Test fun cancelledPendingResultCannotDismissOrOverwriteTheNextLiveSession() {
        for (backend in listOf("miuix", "mochi")) {
            val oldRequest = PendingRename()
            val newRequest = PendingRename()
            withManagement(backend, listOf(oldRequest, newRequest)) { client ->
                val sameActivity = activity!!.get()
                openFromList(ORIGINAL_TITLE)
                replaceTitle("Old request snapshot")
                confirm().performClick()
                waitForRequest(client, oldRequest, 1)
                cancel().assertIsEnabled().performClick()
                awaitUi { !hasDialog() }
                // Reopen the real card while the original repository call is still blocked.
                openFromList(ORIGINAL_TITLE)
                replaceTitle("New session draft")
                assertEquals(1, client.requests.size)
                assertEquals(1L, oldRequest.finished.count)

                oldRequest.release.countDown()
                // The old successful result has returned and its delayed catalogue reload is visible.
                awaitUi {
                    oldRequest.finished.count == 0L && hasTextNode("Old request snapshot")
                }
                settle()
                assertSame("The management Activity must survive across both sessions", sameActivity, activity!!.get())
                confirm().assertIsDisplayed()
                assertDraft("New session draft")
                replaceTitle("New session saved after old completion")
                confirm().assertIsEnabled().performClick()
                waitForRequest(client, newRequest, 2)
                assertEquals(
                    listOf(Request("app-0", "Old request snapshot"),
                        Request("app-0", "New session saved after old completion")),
                    client.requests.toList(),
                )
                newRequest.release.countDown()
                waitForSaved("New session saved after old completion")
                assertSame(sameActivity, activity!!.get())
                assertEquals(2, client.requests.size)
            }
        }
    }

    @Test fun failedRequestKeepsItsDraftAndCanBeRetriedOnTheSameManagementPage() {
        for (backend in listOf("miuix", "mochi")) {
            val failure = PendingRename(success = false)
            val retry = PendingRename()
            withManagement(backend, listOf(failure, retry)) { client ->
                val sameActivity = activity!!.get()
                openFromList(ORIGINAL_TITLE)
                replaceTitle("Draft retained for retry")
                confirm().performClick()
                waitForRequest(client, failure, 1)
                failure.release.countDown()
                awaitUi {
                    failure.finished.count == 0L && hasDialog() &&
                        !confirm().fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
                }
                settle()
                confirm().assertIsDisplayed().assertIsEnabled()
                assertDraft("Draft retained for retry")
                assertTrue("Failed rename must not update the real list", hasTextNode(ORIGINAL_TITLE))
                assertSame(sameActivity, activity!!.get())
                confirm().performClick()
                waitForRequest(client, retry, 2)
                assertEquals(
                    listOf(Request("app-0", "Draft retained for retry"),
                        Request("app-0", "Draft retained for retry")),
                    client.requests.toList(),
                )
                retry.release.countDown()
                waitForSaved("Draft retained for retry")
                assertEquals(2, client.requests.size)
                assertSame(sameActivity, activity!!.get())
            }
        }
    }

    private companion object {
        const val ORIGINAL_TITLE = "Calendar agenda"
    }
}
