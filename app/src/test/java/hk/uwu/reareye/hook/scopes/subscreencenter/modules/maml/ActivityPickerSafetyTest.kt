package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ActivityPickerSafetyTest {
    private val owner = "rear-card:00000000-0000-0000-0000-000000000001"
    private val component = "example/example.Main"
    private fun request(sequence: String = "1") = "$owner|$component|320|$sequence"

    @Test
    fun requestMustMatchCompleteSnapshotAndCanonicalSequence() {
        val gate = ActivityHostLaunchGate()
        assertFalse(gate.accept(owner, component, 240, request()))
        assertFalse(gate.accept(owner, component, 320, request("1.0")))
        assertFalse(gate.accept(owner, component, 320, request("9007199254740992")))
        assertTrue(gate.accept(owner, component, 320, request("2")))
        assertFalse(gate.accept(owner, component, 320, request("1")))
        assertFalse(gate.accept(owner, component, 320, request("2")))
        assertTrue(gate.accept(owner, component, 320, request("3")))
    }

    @Test
    fun oldOwnerCannotReactivateTheBackend() {
        val gate = ActivityHostLaunchGate()
        assertTrue(gate.accept(owner, component, 320, request()))
        val other = "rear-card:00000000-0000-0000-0000-000000000002"
        assertFalse(gate.accept(other, component, 320, "$other|$component|320|2"))
    }

    @Test
    fun scopePermitIsOneShotEvenAcrossBackendRecreation() {
        val permit = ActivityHostLaunchPermit(owner, component, 320, request())
        assertFalse(permit.claim(owner, component, 240, request()))
        assertTrue(permit.claim(owner, component, 320, request()))
        assertFalse(permit.claim(owner, component, 320, request()))
        assertTrue(permit.isValid())
        permit.revoke()
        assertFalse(permit.isValid())
        assertFalse(permit.matches(owner, component, 320, request()))
    }

    @Test
    fun revokedPendingPermitCannotLaunch() {
        val permit = ActivityHostLaunchPermit(owner, component, 320, request())
        permit.revoke()
        assertFalse(permit.claim(owner, component, 320, request()))
    }

    @Test
    fun hiddenPausedDetachedAndFinishedBoundariesRejectLateSnapshots() {
        val boundaries: List<(ActivityHostBackend) -> Unit> = listOf(
            { it.onVisibilityChanged(false) }, { it.onPause() },
            { it.onAttachmentChanged(false) }, { it.onFinish() },
        )
        boundaries.forEach { suspend ->
            val backend = ActivityHostBackend()
            backend.onInit(); backend.onResume(); backend.onAttachmentChanged(true)
            backend.onVisibilityChanged(true)
            val permit = ActivityHostLaunchPermit(owner, component, 320, request())
            suspend(backend)
            // Delivery may be delayed until after the card becomes visible again.
            backend.onResume(); backend.onAttachmentChanged(true); backend.onVisibilityChanged(true)
            backend.onInputsChanged(
                MamlBackendInputs(
                    "test", "activity", visible = true, activityHost =
                        ActivityHostInputs(
                            component,
                            320,
                            instance = owner,
                            userRequest = request(),
                            launchPermit = permit
                        )
                )
            )
            assertFalse(permit.isValid())
            backend.onFinish()
        }
    }

    @Test
    fun newClickAfterHideCanStillBeAccepted() {
        val backend = ActivityHostBackend()
        backend.onInit(); backend.onResume(); backend.onAttachmentChanged(true)
        backend.onVisibilityChanged(false); backend.onVisibilityChanged(true)
        val permit = ActivityHostLaunchPermit(owner, component, 320, request())
        backend.onInputsChanged(
            MamlBackendInputs(
                "test", "activity", visible = true, activityHost =
                    ActivityHostInputs(
                        component,
                        320,
                        instance = owner,
                        userRequest = request(),
                        launchPermit = permit
                    )
            )
        )
        assertTrue(permit.isValid())
        assertFalse(permit.claim(owner, component, 320, request()))
        backend.onFinish()
        assertFalse(permit.isValid())
    }

    @Test
    fun bareStringCompilerNeverAuthorizesAStoredOrForgedRequest() {
        val compiler = MamlStringExpressionCompiler { { request() } }
        assertNull(compiler.activityLaunchPermit(owner, component, 320, request()))
        assertNull(MamlActivityCardBridge.getIssuedLaunch(Any(), owner, component, 320, request()))
    }

    @Test
    fun disabledDefinitionStillExistsButDeletedDefinitionDoesNot() {
        val id = "00000000-0000-0000-0000-000000000001"
        val raw =
            """[{"id":"$id","packageName":"hk.uwu.reareye","business":"activity","enabled":false}]"""
        assertEquals(true, MamlActivityCardInvalidation.definitionExists(raw, "activity", id))
        assertEquals(false, MamlActivityCardInvalidation.definitionExists("[]", "activity", id))
        assertEquals(false, MamlActivityCardInvalidation.definitionExists(raw, "other", id))
    }

    @Test
    fun malformedDefinitionIsNotEvidenceForErasure() {
        assertNull(MamlActivityCardInvalidation.definitionExists("broken", "activity", "id"))
        assertNull(MamlActivityCardInvalidation.definitionExists("[{}]", "activity", "id"))
    }
}
