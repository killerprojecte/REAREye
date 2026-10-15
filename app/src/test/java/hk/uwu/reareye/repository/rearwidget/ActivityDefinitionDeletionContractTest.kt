package hk.uwu.reareye.repository.rearwidget

import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.NotificationRouteBridgeContract as Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDefinitionDeletionContractTest {
    private val id = "a9c34fda-36d8-4e79-9063-42b79bc101bc"

    @Test
    fun deletionRequiresExactActualModuleUidAndSameUser() {
        assertTrue(Contract.isTrustedDeletionCaller(10234, 10234, 10567))
        assertFalse(Contract.isTrustedDeletionCaller(1000, 10234, 10567))
        assertFalse(Contract.isTrustedDeletionCaller(10567, 10234, 10567))
        assertFalse(Contract.isTrustedDeletionCaller(110234, 110234, 10567))
        assertFalse(Contract.isTrustedDeletionCaller(-1, -1, 10567))
        assertTrue(Contract.isTrustedDeletionCaller(110234, 110234, 110567))
    }

    @Test
    fun deletionRejectsOtherPackagesPathsNonCanonicalIdsAndBlankBusiness() {
        assertTrue(Contract.validDeletionTarget(Contract.MODULE_PACKAGE, "activity_picker", id))
        assertFalse(Contract.validDeletionTarget("com.android.systemui", "activity_picker", id))
        assertFalse(Contract.validDeletionTarget(Contract.MODULE_PACKAGE, "../other/card", id))
        assertFalse(Contract.validDeletionTarget(Contract.MODULE_PACKAGE, "", id))
        assertFalse(
            Contract.validDeletionTarget(
                Contract.MODULE_PACKAGE,
                "activity_picker",
                id.uppercase()
            )
        )
        assertFalse(Contract.validDeletionTarget(Contract.MODULE_PACKAGE, "activity_picker", "42"))
        assertFalse(
            Contract.validDeletionTarget(
                Contract.MODULE_PACKAGE,
                "activity_picker",
                "$id/../other"
            )
        )
        assertFalse(Contract.validDeletionTarget(Contract.MODULE_PACKAGE, "b".repeat(129), id))
        assertFalse(Contract.validDeletionTarget(null, "activity_picker", id))
    }

    @Test
    fun payloadHasOnlyExactNativeIdentityFields() {
        val keys = setOf(Contract.Keys.PACKAGE_NAME, Contract.Keys.BUSINESS, Contract.Keys.CARD_ID)
        assertTrue(Contract.validDeletionKeys(keys))
        assertFalse(Contract.validDeletionKeys(keys + "path"))
        assertFalse(Contract.validDeletionKeys(keys + "xml"))
        assertFalse(Contract.validDeletionKeys(keys - Contract.Keys.CARD_ID))
        assertFalse(Contract.validDeletionKeys(emptySet()))
    }

    @Test
    fun persistedRecordsCannotEncodeCrossCardPathsOrExtraFields() {
        assertEquals("activity_picker" to id, Contract.decodeDeletionRecord("activity_picker:$id"))
        assertNull(Contract.decodeDeletionRecord("activity_picker:$id:other"))
        assertNull(Contract.decodeDeletionRecord("../other:$id"))
        assertNull(Contract.decodeDeletionRecord("activity_picker:"))
    }
}
