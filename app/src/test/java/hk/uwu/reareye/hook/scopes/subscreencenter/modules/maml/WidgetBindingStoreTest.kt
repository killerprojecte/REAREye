package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.SharedPreferences
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetBindingStoreTest {
    private val binding = WidgetBinding(7, "pkg/Provider", true, WidgetPhase.COMPLETE)
    private val raw = """{"id":7,"provider":"pkg/Provider","complete":true,"phase":"COMPLETE"}"""

    @Test
    fun roundTripAndRemovalPreserveHostIdAndOtherKeys() {
        val prefs = Preferences().apply {
            values["host_id"] = 0x52455747
            values["binding:other"] = raw
        }
        val store = WidgetBindingStore(prefs)
        assertNull(store.read("key"))
        store.write("key", binding)
        assertEquals(binding, store.read("key"))
        store.write("key", null)
        assertNull(store.read("key"))
        assertEquals(0x52455747, prefs.values["host_id"])
        assertEquals(raw, prefs.values["binding:other"])
    }

    @Test
    fun legacyMissingNullAndEmptyPhaseRemainReadable() {
        for (phase in listOf("", ",\"phase\":null", ",\"phase\":\"\"")) {
            val legacy = """{"id":7,"provider":"pkg/Provider","complete":true$phase}"""
            assertEquals(binding.copy(phase = null), WidgetBindingStore.decode(legacy))
        }
    }

    @Test
    fun everyPersistedPhaseRoundTrips() {
        WidgetPhase.entries.forEach { phase ->
            val expected = binding.copy(complete = phase == WidgetPhase.COMPLETE, phase = phase)
            assertEquals(expected, WidgetBindingStore.decode(WidgetBindingStore.encode(expected)))
        }
    }

    @Test
    fun substitutedTypedGetterRecoversExactSavedBindingAndReportsOriginalError() {
        val prefs = Preferences().apply {
            values["binding:key"] = raw
            substitutedString = "hijacked_bound"
        }
        val failures = mutableListOf<Throwable>()
        val store = WidgetBindingStore(prefs, failures::add)
        assertEquals(binding, store.read("key"))
        assertTrue(failures.single() is JSONException)
        assertTrue(failures.single().message!!.contains("hijacked_bound"))
        assertEquals(raw, prefs.values["binding:key"])
        assertEquals(0, prefs.edits)
    }

    @Test
    fun recoveredReadSurvivesLoggerFailure() {
        val prefs = Preferences().apply {
            values["binding:key"] = raw
            substitutedString = "hijacked_bound"
        }
        assertEquals(binding, WidgetBindingStore(prefs) { throw AssertionError("logger") }.read("key"))
    }

    @Test
    fun malformedStoredStringsStayIntactAndAreNeverTreatedAsMissing() {
        listOf("hijacked_bound", "\"hijacked_bound\"", "null", "[]", "42", "true", "{}", "{")
            .forEach { invalid ->
                val prefs = Preferences().apply { values["binding:key"] = invalid }
                val failure = assertThrows(IllegalStateException::class.java) {
                    WidgetBindingStore(prefs).read("key")
                }
                assertTrue(failure.cause is JSONException)
                assertTrue(failure.suppressed.single() is JSONException)
                assertTrue(failure.message!!.contains("preserved"))
                assertEquals(invalid, prefs.values["binding:key"])
                assertEquals(0, prefs.edits)
            }
    }

    @Test
    fun wrongPreferenceTypeStaysIntactWithOriginalCause() {
        val prefs = Preferences().apply { values["binding:key"] = 23 }
        val failure = assertThrows(IllegalStateException::class.java) { WidgetBindingStore(prefs).read("key") }
        assertTrue(failure.cause is ClassCastException)
        assertEquals(23, prefs.values["binding:key"])
        assertEquals(0, prefs.edits)
    }

    @Test
    fun invalidGetterAndAbsentSnapshotDoNotAllowNewAllocation() {
        val prefs = Preferences().apply { substitutedString = "hijacked_bound" }
        assertThrows(IllegalStateException::class.java) { WidgetBindingStore(prefs).read("key") }
        assertEquals(0, prefs.edits)
    }

    @Test
    fun storageAccessFailureIsNotMistakenForMalformedDataOrRetriedThroughSnapshot() {
        val failure = SecurityException("preference access unavailable")
        val prefs = Preferences().apply {
            values["binding:key"] = raw
            readFailure = failure
        }
        assertSame(failure, assertThrows(SecurityException::class.java) { WidgetBindingStore(prefs).read("key") })
        assertEquals(0, prefs.snapshotReads)
        assertEquals(0, prefs.edits)
    }

    @Test
    fun snapshotFailurePreservesBothOriginalAndSecondaryEvidence() {
        val failure = SecurityException("snapshot unavailable")
        val prefs = Preferences().apply {
            substitutedString = "hijacked_bound"
            snapshotFailure = failure
        }
        val error = assertThrows(IllegalStateException::class.java) { WidgetBindingStore(prefs).read("key") }
        assertTrue(error.cause is JSONException)
        assertSame(failure, error.suppressed.single())
        assertEquals(0, prefs.edits)
    }

    @Test
    fun trailingJsonValuesAndGarbageAreNotAcceptedAsBindings() {
        listOf("garbage", "{}", "[]", "true", "0", "\u0000", "\u0000{}").forEach { suffix ->
            assertThrows(JSONException::class.java) { WidgetBindingStore.decode(raw + suffix) }
        }
        assertEquals(binding, WidgetBindingStore.decode("  $raw \n\t"))
    }

    @Test
    fun providerMustBeAFlattenedComponentWhileLegacyShortClassNamesRemainReadable() {
        listOf("Provider", "/Provider", "pkg/", "pkg/Provider/other", "pkg /Provider", "pkg/ Provider")
            .forEach { provider ->
                assertThrows(JSONException::class.java) {
                    WidgetBindingStore.decode(raw.replace("pkg/Provider", provider))
                }
            }
        assertEquals("pkg/.Provider", WidgetBindingStore.decode(raw.replace("pkg/Provider", "pkg/.Provider")).provider)
    }

    @Test
    fun malformedFieldsCannotBecomeUsableBindingsThroughJsonCoercion() {
        listOf(
            raw.replace("\"id\":7", "\"id\":0"),
            raw.replace("\"id\":7", "\"id\":-1"),
            raw.replace("\"id\":7", "\"id\":7.5"),
            raw.replace("\"id\":7", "\"id\":2147483648"),
            raw.replace("\"id\":7", "\"id\":\"7\""),
            raw.replace("\"id\":7,", ""),
            raw.replace("\"provider\":\"pkg/Provider\"", "\"provider\":42"),
            raw.replace("pkg/Provider", ""),
            raw.replace("\"complete\":true", "\"complete\":\"true\""),
            raw.replace("\"complete\":true", "\"complete\":false"),
            raw.replace("COMPLETE", "unknown"),
            raw.replace("\"phase\":\"COMPLETE\"", "\"phase\":1")
        ).forEach { invalid ->
            assertThrows("Accepted $invalid", JSONException::class.java) { WidgetBindingStore.decode(invalid) }
        }
    }

    @Test
    fun realMalformedRecordKeepsSessionInErrorAcrossClicksAndExplicitRemoval() {
        val prefs = Preferences().apply { values["binding:key"] = "hijacked_bound" }
        val platform = Platform(WidgetBindingStore(prefs))
        val sessions = WidgetSessions(platform)
        val session = sessions.open("key", "pkg/Provider") {}
        session.initialize()
        repeat(3) { session.click() }
        assertEquals(WidgetState.ERROR, session.state)
        assertNull(session.id)
        assertFalse(sessions.deleteCardBinding("key"))
        assertEquals(0, platform.allocations)
        assertEquals(0, platform.deletions)
        assertEquals(0, prefs.edits)
        assertEquals("hijacked_bound", prefs.values["binding:key"])
        // Repairing the source allows a later open to restore the original owned ID.
        prefs.values["binding:key"] = raw
        val restored = sessions.open("key", "pkg/Provider") {}
        restored.initialize()
        assertEquals(WidgetState.READY, restored.state)
        assertEquals(7, restored.id)
        assertEquals(0, platform.allocations)
    }

    @Test
    fun validSnapshotRestoresReadySessionWithoutRebindingOrRewriting() {
        val prefs = Preferences().apply {
            values["binding:key"] = raw
            substitutedString = "hijacked_bound"
        }
        val platform = Platform(WidgetBindingStore(prefs))
        val session = WidgetSessions(platform).open("key", "pkg/Provider") {}
        session.initialize()
        session.click()
        assertEquals(WidgetState.READY, session.state)
        assertEquals(7, session.id)
        assertEquals(0, platform.allocations)
        assertEquals(0, platform.deletions)
        assertEquals(0, prefs.edits)
    }

    private class Platform(private val store: WidgetBindingStore) : WidgetPlatform {
        var allocations = 0
        var deletions = 0
        override fun read(key: String) = store.read(key)
        override fun write(key: String, binding: WidgetBinding?) = store.write(key, binding)
        override fun ownedIds() = setOf(7, 8)
        override fun providerOf(id: Int) = "pkg/Provider"
        override fun providerExists(provider: String) = true
        override fun needsConfiguration(provider: String) = false
        override fun allocate(): Int { allocations++; error("Unexpected allocation") }
        override fun delete(id: Int) { deletions++; error("Unexpected deletion") }
        override fun bind(id: Int, provider: String): Boolean = error("Unexpected bind")
        override fun request(kind: WidgetRequest, id: Int, provider: String, result: (WidgetResult) -> Unit): (() -> Unit)? =
            error("Unexpected authorization request")
        override fun startListening() {}
        override fun stopListening() {}
    }

    @Test
    fun failedCommitRemainsAnError() {
        val prefs = Preferences().apply { commitsSucceed = false }
        assertThrows(IllegalStateException::class.java) { WidgetBindingStore(prefs).write("key", binding) }
        // Android applies the in-memory edit before reporting whether the disk write succeeded.
        assertEquals(binding, WidgetBindingStore(prefs).read("key"))
    }

    /** No Android host is needed; JSON is still the real Android implementation under Robolectric. */
    private class Preferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var substitutedString: String? = null
        var readFailure: RuntimeException? = null
        var snapshotFailure: RuntimeException? = null
        var snapshotReads = 0
        var edits = 0
        var commitsSucceed = true
        override fun getAll(): MutableMap<String, *> {
            snapshotReads++
            snapshotFailure?.let { throw it }
            return values.toMutableMap()
        }
        override fun getString(key: String?, defValue: String?): String? {
            readFailure?.let { throw it }
            return substitutedString ?: (values[key] ?: defValue) as String?
        }
        override fun contains(key: String?) = values.containsKey(key)
        override fun getInt(key: String?, defValue: Int) = (values[key] ?: defValue) as Int
        override fun getLong(key: String?, defValue: Long) = (values[key] ?: defValue) as Long
        override fun getFloat(key: String?, defValue: Float) = (values[key] ?: defValue) as Float
        override fun getBoolean(key: String?, defValue: Boolean) = (values[key] ?: defValue) as Boolean
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            (values[key] ?: defValues) as MutableSet<String>?
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor {
            edits++
            return object : SharedPreferences.Editor {
                val pending = values.toMutableMap()
                private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { pending[key!!] = value }
                override fun putString(key: String?, value: String?) = put(key, value)
                override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
                override fun putInt(key: String?, value: Int) = put(key, value)
                override fun putLong(key: String?, value: Long) = put(key, value)
                override fun putFloat(key: String?, value: Float) = put(key, value)
                override fun putBoolean(key: String?, value: Boolean) = put(key, value)
                override fun remove(key: String?): SharedPreferences.Editor = apply { pending.remove(key) }
                override fun clear(): SharedPreferences.Editor = apply { pending.clear() }
                override fun commit(): Boolean {
                    values.clear(); values.putAll(pending)
                    return commitsSucceed
                }
                override fun apply() { commit() }
            }
        }
    }
}
