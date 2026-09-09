package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import org.junit.Assert.assertThrows
import org.junit.Test

class AppEmbedActivityLaunchPolicyTest {
    @Test
    fun startAnyActivityIssuerMayLaunchProtectedUnexportedActivity() {
        requireAllowed(
            exported = false,
            requiredPermission = TARGET_PERMISSION,
            issuerHasStartAnyActivity = true,
            issuerHasRequiredPermission = false,
        )
    }

    @Test
    fun rootAndSystemAppIdsMayLaunchProtectedUnexportedActivityAcrossUsers() {
        listOf(0, 1_000, 100_000, 101_000).forEach { uid ->
            requireAllowed(
                exported = false,
                requiredPermission = TARGET_PERMISSION,
                issuerUid = uid,
                issuerHasRequiredPermission = false,
            )
        }
    }

    @Test
    fun ordinaryIssuerCannotLaunchUnexportedActivity() {
        assertThrows(IllegalArgumentException::class.java) {
            requireAllowed(exported = false)
        }
    }

    @Test
    fun ordinaryIssuerMustHoldExportedActivityPermission() {
        assertThrows(IllegalArgumentException::class.java) {
            requireAllowed(
                exported = true,
                requiredPermission = TARGET_PERMISSION,
                issuerHasRequiredPermission = false,
            )
        }

        requireAllowed(
            exported = true,
            requiredPermission = TARGET_PERMISSION,
            issuerHasRequiredPermission = true,
        )
    }

    @Test
    fun ordinaryIssuerMayLaunchUnprotectedExportedActivity() {
        requireAllowed(exported = true)
    }

    @Test
    fun invalidIssuerIdentityFailsFast() {
        assertThrows(IllegalArgumentException::class.java) {
            requireAllowed(exported = true, issuerUid = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireAllowed(exported = true, issuerPackageName = " ")
        }
    }

    private fun requireAllowed(
        exported: Boolean,
        requiredPermission: String? = null,
        issuerUid: Int = ORDINARY_SYSTEM_UI_UID,
        issuerPackageName: String = SYSTEM_UI_PACKAGE,
        issuerHasStartAnyActivity: Boolean = false,
        issuerHasRequiredPermission: Boolean = requiredPermission == null,
    ) {
        AppEmbedActivityLaunchPolicy.requireAllowed(
            componentDescription = TARGET_COMPONENT,
            exported = exported,
            requiredPermission = requiredPermission,
            issuerUid = issuerUid,
            issuerPackageName = issuerPackageName,
            issuerHasStartAnyActivity = issuerHasStartAnyActivity,
            issuerHasRequiredPermission = issuerHasRequiredPermission,
        )
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val ORDINARY_SYSTEM_UI_UID = 10_234
        const val TARGET_COMPONENT = "com.example/.PrivateActivity"
        const val TARGET_PERMISSION = "com.example.permission.OPEN_PRIVATE_ACTIVITY"
    }
}
