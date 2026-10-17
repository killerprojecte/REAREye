package hk.uwu.reareye.agent.data

import android.content.Context
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.ui.components.ModelDraft
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MarySettingsTest {
    private val settings = MarySettings(
        configuration = AgentConfig(
            provider = ProviderConfig(
                apiKey = "dialogue-secret",
                model = "dialogue",
                protocol = Protocol.RESPONSES
            ),
            toolTimeoutMs = 123456,
            maxRepeatedToolCalls = 9,
            systemPrompt = "user-customized prompt",
        ),
        approvalMode = ApprovalMode.AUTO,
        inheritApprovalModel = false,
        approvalProvider = ProviderConfig(
            apiKey = "approval-secret",
            model = "review",
            protocol = Protocol.ANTHROPIC
        ),
    )

    @Test
    fun providerPresetsChooseTheRequestedProtocolWithoutChangingOtherSettings() {
        val existing =
            settings.configuration.provider.copy(contextWindowTokens = 64000, temperature = 0.5)
        val openAi = hk.uwu.reareye.agent.ProviderPreset.OPENAI.applyTo(existing)
        assertEquals(Protocol.RESPONSES, openAi.protocol)
        assertEquals("https://api.openai.com/v1", openAi.baseUrl)
        assertEquals(existing.copy(baseUrl = openAi.baseUrl, protocol = Protocol.RESPONSES), openAi)
        val deepSeek = hk.uwu.reareye.agent.ProviderPreset.DEEPSEEK.applyTo(existing)
        assertEquals(Protocol.CHAT_COMPLETIONS, deepSeek.protocol)
        assertEquals("https://api.deepseek.com/v1", deepSeek.baseUrl)
        assertEquals(
            Protocol.ANTHROPIC,
            hk.uwu.reareye.agent.ProviderPreset.ANTHROPIC.applyTo(existing).protocol
        )
        assertEquals(Protocol.RESPONSES, existing.protocol)
    }

    @Test
    fun snapshotRoundTripPreservesUneditedFieldsWithoutPlaintextKeys() {
        val prepared = requireNotNull(
            prepareSettingsWrite(
                settings,
                "new-dialogue",
                "new-approval"
            ) { "encrypted:$it" })
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(prepared.snapshot)
        assertFalse(encoded.contains("dialogue-secret"))
        assertFalse(encoded.contains("approval-secret"))
        assertFalse(encoded.contains("new-dialogue"))
        assertFalse(encoded.contains("new-approval"))
        val decoded = json.decodeFromString<SettingsSnapshot>(encoded)
        assertEquals(
            settings.configuration.copy(
                provider = settings.configuration.provider.copy(
                    apiKey = ""
                )
            ), decoded.configuration
        )
        assertEquals(settings.approvalProvider.copy(apiKey = ""), decoded.approvalProvider)
        assertEquals("AUTO", decoded.approvalMode)
        assertFalse(decoded.inheritApprovalModel)
    }

    @Test
    fun secondEncryptionFailureCannotProducePartialWriteBatch() {
        var calls = 0
        val result = prepareSettingsWrite(settings, "one", "two") {
            calls++
            if (calls == 1) "cipher-one" else null
        }
        assertEquals(2, calls)
        assertNull(result)
    }

    @Test
    fun clearAndPreserveKeysDoNotInvokeEncryption() {
        val batch =
            requireNotNull(prepareSettingsWrite(settings, "", null) { error("must not encrypt") })
        assertNull(batch.dialogueCipher)
        assertNull(batch.approvalCipher)
        assertEquals("", batch.snapshot.configuration.provider.apiKey)
    }

    @Test
    fun legacySnapshotDefaultsNeverGrantAutomaticApproval() {
        val decoded = Json.decodeFromString<SettingsSnapshot>("""{"configuration":{}}""")
        assertEquals("ASK", decoded.approvalMode)
        assertTrue(decoded.inheritApprovalModel)
        assertEquals(128000, decoded.configuration.provider.contextWindowTokens)
    }

    @Test
    fun modelDraftValidatesContextReserveEndpointAndNumbers() {
        val draft = ModelDraft(ProviderConfig(model = "test"))
        assertTrue(draft.valid())
        assertFalse(draft.copy(context = "4095").valid())
        assertFalse(draft.copy(context = "4096", output = "3687").valid())
        assertTrue(draft.copy(context = "4096", output = "3686").valid())
        assertFalse(draft.copy(output = "0").valid())
        assertFalse(draft.copy(temperature = "NaN").valid())
        assertFalse(draft.copy(temperature = "3").valid())
        assertFalse(
            draft.copy(provider = draft.provider.copy(baseUrl = "file:///tmp/model")).valid()
        )
        assertFalse(draft.copy(provider = draft.provider.copy(baseUrl = "https:///models")).valid())
        assertTrue(
            draft.copy(provider = draft.provider.copy(baseUrl = "http://localhost:8080/v1")).valid()
        )
    }

    @Test
    fun legacySourceRejectsApprovalUpdatesInsteadOfSilentlySavingOnlyConfiguration() {
        val source = object : AgentSettingsSource {
            override fun load() = settings.configuration
            override fun isReady() = true
            override fun save(config: AgentConfig) =
                error("Approval must not fall back to config-only save")

            override fun saveApiKey(key: String) = false
            override fun loadApiKey() = ""
            override fun isApiKeyUnreadable() = false
        }
        assertThrows(IllegalStateException::class.java) {
            source.updateMarySettings { it.copy(approvalMode = ApprovalMode.AUTO) }
        }
    }

    @Test
    fun keyDraftCanPreserveReplaceAndExplicitlyClear() {
        val draft = ModelDraft(ProviderConfig(apiKey = "stored"))
        assertEquals("stored", draft.build().apiKey)
        assertEquals("new", draft.copy(keyDraft = " new ").build().apiKey)
        assertEquals("", draft.copy(clearKey = true).build().apiKey)
        assertEquals("", draft.copy(clearKey = true, keyDraft = "new").build().apiKey)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MarySettingsStoreAtomicTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val prefs
        get() = context.getSharedPreferences(
            "reareye_agent_secrets",
            Context.MODE_PRIVATE
        )

    @Test
    fun approvalUpdateMigratesOldCanonicalSnapshotAndPreservesCiphertext() {
        prefs.edit().clear()
            .putString(
                "settings_snapshot_v1",
                """{"configuration":{"systemPrompt":"old custom prompt"}}"""
            )
            .putString("api_key_ciphertext", "unreadable-dialogue-cipher")
            .putString("approval_api_key_ciphertext", "unreadable-approval-cipher").commit()
        val store = AgentSettingsStore(context)
        assertEquals(ApprovalMode.ASK, store.loadMarySettings().approvalMode)
        val before = store.loadMarySettings()
        val updated = store.updateMarySettings { it.copy(approvalMode = ApprovalMode.AUTO) }
        assertEquals(before.copy(approvalMode = ApprovalMode.AUTO), updated)
        assertEquals(updated, AgentSettingsStore(context).loadMarySettings())
        assertEquals("unreadable-dialogue-cipher", prefs.getString("api_key_ciphertext", null))
        assertEquals(
            "unreadable-approval-cipher",
            prefs.getString("approval_api_key_ciphertext", null)
        )
        val encoded = requireNotNull(prefs.getString("settings_snapshot_v1", null))
        assertFalse(encoded.contains("unreadable-dialogue-cipher"))
        assertFalse(encoded.contains("unreadable-approval-cipher"))
    }

    @Test
    fun concurrentStoreInstancesSerializeReadModifyCommitAndPreserveOtherFields() {
        val original = MarySettings(
            configuration = AgentConfig(provider = ProviderConfig(model = "dialogue")),
            inheritApprovalModel = false, approvalProvider = ProviderConfig(model = "reviewer")
        )
        prefs.edit().clear().putString(
            "settings_snapshot_v1", Json.encodeToString(
            requireNotNull(
                prepareSettingsWrite(
                    original,
                    null,
                    null
                ) { error("no encryption") }).snapshot
        )
        ).commit()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val first = thread {
            try {
                AgentSettingsStore(context).updateMarySettings {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    it.copy(approvalMode = ApprovalMode.AUTO)
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        var second: Thread? = null
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            second = thread {
                try {
                    secondStarted.countDown()
                    AgentSettingsStore(context).updateMarySettings {
                        it.copy(configuration = it.configuration.copy(systemPrompt = "new prompt"))
                    }
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            first.join(10000)
            second?.join(10000)
        }
        assertFalse(first.isAlive)
        assertFalse(second?.isAlive == true)
        error.get()?.let { throw AssertionError(it) }
        assertEquals(
            original.copy(
                approvalMode = ApprovalMode.AUTO,
                configuration = original.configuration.copy(systemPrompt = "new prompt")
            ),
            AgentSettingsStore(context).loadMarySettings()
        )
    }
}
