package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppEmbedCallerPolicyTest {
    @Test
    fun exactPackageMayBootstrapFromSharedUidAndUseMintedCapability() {
        val policy = AppEmbedCallerPolicy(
            expectedPackage = EXPECTED_PACKAGE,
            expectedUid = { EXPECTED_UID },
            packagesForUid = { uid ->
                require(uid == EXPECTED_UID)
                arrayOf("com.example.shared", EXPECTED_PACKAGE)
            },
        )

        val authorized = policy.authorizeBootstrap(
            sentUid = EXPECTED_UID,
            sentPackage = EXPECTED_PACKAGE,
        )
        policy.enforceBinderCaller(authorized, callingUid = EXPECTED_UID)

        assertEquals(EXPECTED_UID, authorized.uid)
        assertEquals(EXPECTED_PACKAGE, authorized.packageName)
    }

    @Test
    fun bootstrapRejectsMissingOrMismatchedBroadcastIdentity() {
        val policy = policy()

        assertThrows(SecurityException::class.java) {
            policy.authorizeBootstrap(sentUid = -1, sentPackage = EXPECTED_PACKAGE)
        }
        assertThrows(SecurityException::class.java) {
            policy.authorizeBootstrap(sentUid = EXPECTED_UID, sentPackage = null)
        }
        assertThrows(SecurityException::class.java) {
            policy.authorizeBootstrap(sentUid = EXPECTED_UID, sentPackage = "com.example.attacker")
        }
    }

    @Test
    fun bootstrapRejectsUidThatDoesNotMatchInstalledExpectedPackage() {
        val policy = AppEmbedCallerPolicy(
            expectedPackage = EXPECTED_PACKAGE,
            expectedUid = { EXPECTED_UID + 1 },
            packagesForUid = { arrayOf(EXPECTED_PACKAGE) },
        )

        assertThrows(SecurityException::class.java) {
            policy.authorizeBootstrap(EXPECTED_UID, EXPECTED_PACKAGE)
        }
    }

    @Test
    fun bootstrapRejectsUidPackageSetThatDoesNotContainExpectedPackage() {
        listOf<Array<String>?>(
            null,
            emptyArray(),
            arrayOf("com.example.shared")
        ).forEach { packages ->
            val policy = AppEmbedCallerPolicy(
                expectedPackage = EXPECTED_PACKAGE,
                expectedUid = { EXPECTED_UID },
                packagesForUid = { packages },
            )

            assertThrows(SecurityException::class.java) {
                policy.authorizeBootstrap(EXPECTED_UID, EXPECTED_PACKAGE)
            }
        }
    }

    @Test
    fun binderCallRejectsDifferentUidEvenWhenCapabilityObjectIsKnown() {
        val policy = policy()
        val authorized = policy.authorizeBootstrap(EXPECTED_UID, EXPECTED_PACKAGE)

        assertThrows(SecurityException::class.java) {
            policy.enforceBinderCaller(authorized, callingUid = EXPECTED_UID + 1)
        }
    }

    @Test
    fun blankExpectedPackageFailsFast() {
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedCallerPolicy(
                expectedPackage = " ",
                expectedUid = { EXPECTED_UID },
                packagesForUid = { arrayOf(EXPECTED_PACKAGE) },
            )
        }
    }

    private fun policy(): AppEmbedCallerPolicy = AppEmbedCallerPolicy(
        expectedPackage = EXPECTED_PACKAGE,
        expectedUid = { EXPECTED_UID },
        packagesForUid = { arrayOf(EXPECTED_PACKAGE) },
    )

    private companion object {
        const val EXPECTED_PACKAGE = "com.xiaomi.subscreencenter"
        const val EXPECTED_UID = 10_123
    }
}
