package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ActivityHostBackendPresentationTest {
    private val owner = "rear-card:00000000-0000-0000-0000-000000000001"
    private val component = "example/example.Main"
    private fun selection(): ActivityHostInputs {
        val request = "$owner|$component|450|1"
        return ActivityHostInputs(
            component,
            450,
            gutterDp = 0f,
            instance = owner,
            userRequest = request,
            launchPermit = ActivityHostLaunchPermit(owner, component, 450, request),
            showControls = false
        )
    }

    private fun buttons(view: View): List<Button> =
        (if (view is Button) listOf(view) else emptyList()) +
                (if (view is ViewGroup) (0 until view.childCount).flatMap {
                    buttons(
                        view.getChildAt(
                            it
                        )
                    )
                } else emptyList())

    private fun pending(backend: ActivityHostBackend) = ActivityHostBackend::class.java
        .getDeclaredField("pendingLaunch").apply { isAccessible = true }.get(backend)

    private fun visible(backend: ActivityHostBackend) = ActivityHostBackend::class.java
        .getDeclaredField("visible").apply { isAccessible = true }.getBoolean(backend)

    @Test
    fun passiveEmbeddingHasBlackBackgroundAndNoNativeButtons() {
        val backend = ActivityHostBackend(ActivityHostInputs(component, showControls = false))
        try {
            val host = backend.createView(RuntimeEnvironment.getApplication())
            assertTrue(buttons(host).isEmpty())
            assertEquals(Color.BLACK, (host.background as ColorDrawable).color)
            assertFalse(host.isClickable)
        } finally {
            backend.onFinish()
        }
    }

    @Test
    fun changingToPassiveModeRemovesExistingButtonsRatherThanOnlyHidingThem() {
        val backend = ActivityHostBackend(ActivityHostInputs(component))
        try {
            val host = backend.createView(RuntimeEnvironment.getApplication())
            assertEquals(3, buttons(host).size)
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test", "activity", visible = true,
                    activityHost = ActivityHostInputs(component, showControls = false)
                )
            )
            assertTrue(buttons(host).isEmpty())
        } finally {
            backend.onFinish()
        }
    }

    @Test
    fun firstVisibleClickWaitsForInitAndInitialAttachmentWithoutLosingPermit() {
        val backend = ActivityHostBackend()
        val spec = selection()
        try {
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test",
                    "activity",
                    visible = true,
                    activityHost = spec
                )
            )
            assertTrue(visible(backend))
            assertSame(spec, pending(backend))
            backend.onAttachmentChanged(false) // initial observation, not an actual detach
            backend.onInit()
            backend.onResume()
            assertSame(spec, pending(backend))
            assertTrue(spec.launchPermit!!.isValid())
            assertFalse(spec.launchPermit.claim(owner, component, 450, spec.userRequest))
            backend.onAttachmentChanged(true)
            backend.onAttachmentChanged(false) // actual detach revokes waiting click
            assertNull(pending(backend))
            assertFalse(spec.launchPermit.isValid())
        } finally {
            backend.onFinish()
        }
    }

    @Test
    fun inheritedGroupHideFromInputSnapshotCancelsPendingLaunch() {
        val backend = ActivityHostBackend()
        val spec = selection()
        try {
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test",
                    "activity",
                    visible = true,
                    activityHost = spec
                )
            )
            assertNotNull(pending(backend))
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test",
                    "activity",
                    visible = false,
                    activityHost = spec
                )
            )
            assertFalse(visible(backend))
            assertNull(pending(backend))
            assertFalse(spec.launchPermit!!.isValid())
        } finally {
            backend.onFinish()
        }
    }

    @Test
    fun failedSessionRevokesRequestSoPassiveTemplateCanReturnToPicker() {
        val backend = ActivityHostBackend()
        val spec = selection()
        try {
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test",
                    "activity",
                    visible = true,
                    activityHost = spec
                )
            )
            assertNotNull(pending(backend))
            backend.onClosed(0, "task_launch_failed")
            assertNull(pending(backend))
            assertFalse(spec.launchPermit!!.isValid())
        } finally {
            backend.onFinish()
        }
    }

    @Test
    fun restoredSelectionAndBareRequestNeverStartAnApplication() {
        val backend = ActivityHostBackend()
        try {
            val spec = selection().copy(launchPermit = null)
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test",
                    "activity",
                    visible = true,
                    activityHost = spec
                )
            )
            backend.onInit(); backend.onResume(); backend.onAttachmentChanged(true)
            assertNull(pending(backend))
        } finally {
            backend.onFinish()
        }
    }

    @Test
    fun sameRectangleNeedsNewAcknowledgementAfterRevoke() {
        val gate = ActivityHostInputGate()
        val bounds = Rect(296, 0, 976, 596)
        gate.submit(1, 1, bounds)
        assertTrue(gate.acknowledge(1, 1, bounds))
        assertTrue(gate.allows(true, true, false))
        gate.revoke()
        gate.submit(1, 2, bounds)
        assertFalse(gate.acknowledge(1, 1, bounds))
        assertFalse(gate.allows(true, true, false))
        assertTrue(gate.acknowledge(1, 2, bounds))
        assertTrue(gate.allows(true, true, false))
        assertFalse(gate.allows(true, true, true))
        assertFalse(gate.allows(false, true, false))
    }
}
