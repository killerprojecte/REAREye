package hk.uwu.reareye.agent.data

import android.content.Context
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.hook.support.RemotePreferencesSnapshot
import hk.uwu.reareye.hook.support.XposedRemoteHookPrefs
import hk.uwu.reareye.ui.components.ModelDraft
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.screen.MarySettingsDraft
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
                """{"configuration":{"systemPrompt":"old custom prompt"},"inheritApprovalModel":false}"""
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
        val actual = AgentSettingsStore(context).loadMarySettings()
        assertEquals(ApprovalMode.AUTO, actual.approvalMode)
        assertEquals(original.configuration.copy(systemPrompt = "new prompt"), actual.configuration)
        assertEquals(original.approvalProvider, actual.approvalProvider)
        assertEquals(LEGACY_DIALOGUE_MODEL_ID, actual.mainModelId)
        assertEquals(LEGACY_APPROVAL_MODEL_ID, actual.approvalModelId)
    }

    @Test
    fun migratesBothLegacyRolesWithStableIdsAndTheirOwnParametersAndKeys() {
        val main = ProviderConfig(baseUrl = "https://primary.invalid/v1", model = "main-remote-id", protocol = Protocol.RESPONSES,
            contextWindowTokens = 64_000, maxOutputTokens = 4_096, temperature = 0.4)
        val approval = ProviderConfig(baseUrl = "https://approval.invalid/v1", model = "approval-remote-id", protocol = Protocol.ANTHROPIC,
            contextWindowTokens = 32_000, maxOutputTokens = 1_024, temperature = 0.1)
        prefs.edit().clear()
            .putString("settings_snapshot_v1", Json.encodeToString(SettingsSnapshot(AgentConfig(provider = main),
                inheritApprovalModel = false, approvalProvider = approval)))
            .putString("api_key_ciphertext", seal("main-test-key"))
            .putString("approval_api_key_ciphertext", seal("approval-test-key")).commit()
        val store = fakeCryptoStore()
        val legacy = store.loadMarySettings()
        assertTrue(legacy.agentMode)
        assertEquals(LEGACY_DIALOGUE_MODEL_ID, legacy.mainModelId)
        assertEquals(LEGACY_APPROVAL_MODEL_ID, legacy.approvalModelId)
        assertEquals(main.copy(apiKey = "main-test-key"), legacy.resolveModel(legacy.mainModelId))
        assertEquals(approval.copy(apiKey = "approval-test-key"), legacy.resolveModel(legacy.approvalModelId))
        assertEquals(legacy.resolveModel(legacy.mainModelId), store.load().provider)
        assertEquals(legacy.resolveModel(legacy.approvalModelId), store.loadMarySettings().approvalProvider)
        assertTrue(store.saveRegistrySettings(legacy))
        assertEquals(legacy, fakeCryptoStore().loadMarySettings())
        val encoded = requireNotNull(prefs.getString("settings_snapshot_v1", null))
        assertFalse(encoded.contains("main-test-key")); assertFalse(encoded.contains("approval-test-key"))
    }

    @Test
    fun followingLegacySnapshotDoesNotRegisterDormantApprovalOrRestoreItLater() {
        val snapshot = SettingsSnapshot(AgentConfig(provider = ProviderConfig(model = "dialogue")),
            approvalProvider = ProviderConfig(model = "old-approval", baseUrl = "https://approval.invalid/v1"))
        prefs.edit().clear().putString("settings_snapshot_v1", Json.encodeToString(snapshot))
            .putString("approval_api_key_ciphertext", seal("dormant-key")).commit()
        val store = fakeCryptoStore()
        val preview = store.loadMarySettings()
        assertEquals(listOf(LEGACY_DIALOGUE_PROVIDER_ID), preview.providers.map { it.id })
        assertEquals(listOf(LEGACY_DIALOGUE_MODEL_ID), preview.models.map { it.id })
        assertNull(preview.approvalModelId)
        assertTrue(store.saveRegistrySettings(preview.copy(inheritApprovalModel = false)))
        assertNull(fakeCryptoStore().loadMarySettings().approvalModelId)
        assertFalse(prefs.contains("approval_api_key_ciphertext"))
    }

    @Test
    fun remoteLegacyMigrationUsesApprovalModeAndNeverWritesBackToRemotePrefs() {
        val remote = context.getSharedPreferences("legacy_remote_test_fixture", Context.MODE_PRIVATE)
        val base = "https://legacy-approval.invalid/v1"
        remote.edit().clear().putBoolean(ConfigKeys.AGENT_APPROVAL_INHERIT, true)
            .putString(ConfigKeys.AGENT_APPROVAL_BASE_URL, base)
            .putString(ConfigKeys.AGENT_APPROVAL_MODEL, "legacy-approval")
            .putString(ConfigKeys.AGENT_APPROVAL_PROTOCOL, Protocol.ANTHROPIC.name)
            .putInt(ConfigKeys.AGENT_APPROVAL_CONTEXT_WINDOW, 64_000)
            .putString(ConfigKeys.AGENT_MODEL, "legacy-dialogue").commit()
        val adapter = PrefsManager(XposedRemoteHookPrefs.fromProvider("fixture") { RemotePreferencesSnapshot(remote, 1) })
        for (inherit in listOf(true, false)) {
            remote.edit().putBoolean(ConfigKeys.AGENT_APPROVAL_INHERIT, inherit).commit()
            val originalRemote = remote.all.toMap()
            prefs.edit().clear().putString("api_key_ciphertext", seal("dialogue-key"))
                .putString("approval_api_key_ciphertext", seal("approval-key")).commit()
            val store = AgentSettingsStore(context, ::seal, ::unseal) { adapter }
            assertTrue(store.isReady())
            val loaded = store.loadMarySettings()
            assertEquals("legacy-dialogue", loaded.resolveModel(loaded.mainModelId)?.model)
            assertEquals("dialogue-key", loaded.resolveModel(loaded.mainModelId)?.apiKey)
            if (inherit) {
                assertEquals(listOf(LEGACY_DIALOGUE_PROVIDER_ID), loaded.providers.map { it.id })
                assertEquals(listOf(LEGACY_DIALOGUE_MODEL_ID), loaded.models.map { it.id })
                assertNull(loaded.approvalModelId)
            } else {
                assertEquals(LEGACY_APPROVAL_MODEL_ID, loaded.approvalModelId)
                assertEquals(ProviderConfig(baseUrl = base, model = "legacy-approval", protocol = Protocol.ANTHROPIC,
                    contextWindowTokens = 64_000, apiKey = "approval-key"), loaded.resolveModel(loaded.approvalModelId))
            }
            assertTrue(store.saveRegistrySettings(loaded))
            assertEquals(loaded, fakeCryptoStore().loadMarySettings())
            assertEquals(originalRemote, remote.all)
        }
    }

    @Test
    fun onlyAnUnusedUnmodifiedMigratedApprovalPlaceholderIsRemoved() {
        val original = legacyRegistry(ProviderConfig(model = "main"), ProviderConfig())
        val approvalModel = original.models.last()
        val approvalProvider = original.providers.last()
        fun read(registry: MaryRegistrySnapshot, inherit: Boolean = true, cipher: String? = null): MarySettings {
            val snapshot = SettingsSnapshot(AgentConfig(), registry = registry, inheritApprovalModel = inherit)
            prefs.edit().clear().putString("settings_snapshot_v1", Json.encodeToString(snapshot)).apply {
                if (cipher != null) putString("approval_api_key_ciphertext", cipher)
            }.commit()
            return fakeCryptoStore().loadMarySettings()
        }
        val hidden = read(original)
        assertNull(hidden.approvalModelId)
        assertEquals(listOf(LEGACY_DIALOGUE_PROVIDER_ID), hidden.providers.map { it.id })
        assertEquals(listOf(LEGACY_DIALOGUE_MODEL_ID), hidden.models.map { it.id })
        assertTrue(fakeCryptoStore().saveRegistrySettings(hidden.copy(webEnabled = false)))
        val saved = Json.decodeFromString<SettingsSnapshot>(requireNotNull(prefs.getString("settings_snapshot_v1", null)))
        assertEquals(hidden.models, saved.registry?.models)
        assertTrue(read(original, inherit = false).providers.contains(approvalProvider))
        assertTrue(read(original, cipher = "unreadable").isProviderKeyUnreadable(approvalProvider.id))
        for (registry in listOf(
            original.copy(mainModelId = approvalModel.id),
            original.copy(subagentModelId = approvalModel.id),
            original.copy(models = listOf(original.models.first(), approvalModel.copy(contextWindowTokens = 8_192))),
            original.copy(models = original.models + MaryModelConfig("shared", approvalProvider.id, "")),
            original.copy(providers = listOf(original.providers.first(), approvalProvider.copy(displayName = "Kept"))),
        )) {
            val kept = read(registry)
            assertTrue(kept.providers.any { it.id == approvalProvider.id })
            assertTrue(kept.models.any { it.id == approvalModel.id })
        }
    }

    @Test
    fun webPolicyReadsLocalSnapshotWithoutConsultingRemoteOrDecrypting() {
        prefs.edit().clear().commit()
        val reader = AgentSettingsStore(context, { error("must not encrypt") }, { error("must not decrypt") }) {
            error("must not consult remote")
        }
        assertTrue(reader.webToolsEnabled())
        prefs.edit().putString("settings_snapshot_v1", """{"configuration":{},"webSearchEngine":"GOOGLE"}""")
            .putString("api_key_ciphertext", seal("key")).commit()
        assertTrue(reader.webToolsEnabled())
        val writer = fakeCryptoStore()
        val defaults = writer.loadMarySettings()
        assertTrue(defaults.webEnabled)
        assertEquals(MaryWebSearchEngine.BING, defaults.webSearchEngine)
        assertTrue(writer.saveRegistrySettings(defaults.copy(webEnabled = false)))
        assertFalse(reader.webToolsEnabled())
        val reloaded = fakeCryptoStore().loadMarySettings()
        assertFalse(reloaded.webEnabled)
        assertEquals(MaryWebSearchEngine.BING, reloaded.webSearchEngine)
        assertTrue(writer.saveRegistrySettings(reloaded.copy(webEnabled = true)))
        assertTrue(reader.webToolsEnabled())
        prefs.edit().putString("settings_snapshot_v1", """{"configuration":{},"registry":{"mainModelId":"missing"}}""").commit()
        assertFalse(reader.webToolsEnabled())
        prefs.edit().putString("settings_snapshot_v1", "broken snapshot").commit()
        assertFalse(reader.webToolsEnabled())
    }

    @Test
    fun persistsIndependentEntriesAndRoleChoicesWithScopedKeyRotationAndDeletion() {
        val store = seedRegistry()
        var settings = store.loadMarySettings()
        assertFalse(settings.agentMode)
        assertEquals("beta-model", settings.mainModelId)
        assertEquals("alpha-model", settings.approvalModelId)
        assertEquals("helper-model", settings.subagentModelId)
        assertEquals("Alpha custom name", settings.models.first().name)
        assertEquals(setOf(MaryModelCapability.IMAGE, MaryModelCapability.TOOLS), settings.models.first().capabilities)
        assertEquals(MaryModelValueSource.CATALOG, settings.models.first().contextSource)
        assertEquals(17, settings.providers.first().modelCount)
        assertEquals("https://beta.invalid/v1", store.load().provider.baseUrl)
        assertEquals("beta-key", store.loadApiKey())
        assertEquals("alpha-key", store.loadApprovalApiKey())
        assertEquals("alpha-key", settings.resolveModel(settings.subagentModelId)?.apiKey)
        assertTrue(store.saveApiKey("rotated-key"))
        settings = store.loadMarySettings()
        assertEquals("rotated-key", settings.resolveModel("beta-model")?.apiKey)
        assertEquals("alpha-key", settings.resolveModel("helper-model")?.apiKey)

        val snapshot = prefs.all.toMap()
        assertFalse(store.saveRegistrySettings(settings.copy(providers = settings.providers.drop(1))))
        for (referenced in listOf("alpha-model", "beta-model", "helper-model")) {
            assertFalse(store.saveRegistrySettings(settings.copy(models = settings.models.filterNot { it.id == referenced })))
        }
        assertEquals(snapshot, prefs.all)
        val detached = settings.copy(mainModelId = "alpha-model", models = settings.models.filterNot { it.id == "beta-model" },
            providers = settings.providers.filterNot { it.id == "beta" })
        assertTrue(store.saveRegistrySettings(detached, mapOf("alpha" to "")))
        assertFalse(prefs.contains("provider_api_key_ciphertext.beta"))
        assertFalse(prefs.contains("provider_api_key_ciphertext.alpha"))
        assertNull(fakeCryptoStore().loadMarySettings().resolveModel("beta-model"))
    }

    @Test
    fun unrelatedSaveKeepsInvalidUntouchedLegacyValuesAndRejectsInvalidEdits() {
        val legacyConfig = AgentConfig(
            provider = ProviderConfig(baseUrl = "file:///legacy", model = "legacy-model ",
                contextWindowTokens = 2_048, maxOutputTokens = 9_192, temperature = 2.5),
            maxIterations = 100, maxConsecutiveToolFailures = 99, maxToolResultChars = 250_000, turnBudgetMs = 25_123,
        )
        prefs.edit().clear().putString("settings_snapshot_v1", Json.encodeToString(SettingsSnapshot(legacyConfig))).commit()
        val store = fakeCryptoStore()
        val before = store.loadMarySettings()
        val draft = MarySettingsDraft.from(before)
        assertTrue(draft.valid())
        assertFalse(draft.copy(iterations = "101").valid())
        val provider = draft.providers.single()
        assertFalse(draft.updateProvider(provider.copy(displayName = "edited")).valid())
        val model = draft.models.single()
        assertFalse(draft.updateModel(model.copy(displayName = "edited")).valid())
        val edited = draft.copy(webEnabled = false, approvalMode = ApprovalMode.AUTO)
        assertTrue(store.saveRegistrySettings(edited.build(), expected = before))
        val saved = fakeCryptoStore().loadMarySettings()
        assertEquals(legacyConfig, saved.configuration)
        assertEquals(before.models, saved.models)
        assertEquals(before.providers, saved.providers)
        assertEquals(ApprovalMode.AUTO, saved.approvalMode)
        assertFalse(saved.webEnabled)
    }

    @Test
    fun modelAndProviderDeletionClearRolesKeysAndRejectStaleSave() {
        val store = seedRegistry()
        val initial = store.loadMarySettings()
        val draft = MarySettingsDraft.from(initial)
        val removedModel = draft.removeModel("beta-model")
        assertTrue(removedModel.valid())
        assertTrue(store.saveRegistrySettings(removedModel.build(), expected = initial))
        val afterModel = fakeCryptoStore().loadMarySettings()
        assertNull(afterModel.mainModelId)
        assertEquals("alpha-model", afterModel.approvalModelId)
        assertEquals("helper-model", afterModel.subagentModelId)
        assertEquals("beta-key", afterModel.resolveProvider("beta")?.apiKey)
        assertNull(afterModel.resolveModel("beta-model"))
        val removedProvider = MarySettingsDraft.from(afterModel).removeProvider("alpha")
        assertTrue(removedProvider.valid())
        assertTrue(store.saveRegistrySettings(removedProvider.build(), expected = afterModel))
        assertFalse(prefs.contains("provider_api_key_ciphertext.alpha"))
        assertEquals("beta-key", fakeCryptoStore().loadMarySettings().resolveProvider("beta")?.apiKey)
        assertFalse(store.saveRegistrySettings(draft.copy(webEnabled = false).build(), expected = initial))
        val afterProvider = fakeCryptoStore().loadMarySettings()
        assertNull(afterProvider.approvalModelId)
        assertNull(afterProvider.subagentModelId)
        assertTrue(afterProvider.models.isEmpty())
        assertTrue(store.saveRegistrySettings(MarySettingsDraft.from(afterProvider).removeProvider("beta").build()))
        store.save(store.load().copy(systemPrompt = "still empty"))
        val reloaded = fakeCryptoStore().loadMarySettings()
        assertTrue(reloaded.providers.isEmpty())
        assertTrue(reloaded.models.isEmpty())
        assertFalse(prefs.contains("provider_api_key_ciphertext.beta"))
    }

    @Test
    fun failedEncryptionCannotCommitAnyRegistryOrKeyChange() {
        val store = seedRegistry()
        val before = prefs.all.toMap()
        var encryptionCalls = 0
        val failing = AgentSettingsStore(context, { value -> if (++encryptionCalls == 2) null else seal(value) }, ::unseal)
        assertFalse(failing.saveRegistrySettings(store.loadMarySettings().copy(agentMode = true),
            linkedMapOf("alpha" to "new-alpha-key", "beta" to "new-beta-key")))
        assertEquals(2, encryptionCalls)
        assertEquals(before, prefs.all)
    }

    @Test
    fun legacyConfigUpdateDetachesOnlyItsModelWhenProviderWasShared() {
        val store = seedRegistry()
        val settings = store.loadMarySettings()
        assertTrue(store.saveRegistrySettings(settings.copy(mainModelId = "alpha-model")))
        val before = store.load()
        store.save(before.copy(provider = before.provider.copy(baseUrl = "https://moved.invalid/v1", temperature = 0.6)))
        val reloaded = fakeCryptoStore().loadMarySettings()
        assertEquals("alpha-model", reloaded.mainModelId)
        assertEquals("https://moved.invalid/v1", reloaded.configuration.provider.baseUrl)
        assertEquals(0.6, reloaded.configuration.provider.temperature)
        assertEquals("alpha-key", reloaded.configuration.provider.apiKey)
        assertEquals("https://alpha.invalid/v1", reloaded.resolveModel("helper-model")?.baseUrl)
        assertEquals("alpha-key", reloaded.resolveModel("helper-model")?.apiKey)
        assertEquals("https://beta.invalid/v1", reloaded.resolveModel("beta-model")?.baseUrl)
        assertTrue(reloaded.models.first { it.id == "alpha-model" }.providerId != "alpha")
    }

    private fun seedRegistry(): AgentSettingsStore {
        check(prefs.edit().clear().putString("settings_snapshot_v1", """{"configuration":{}}""").commit())
        val store = fakeCryptoStore()
        val settings = MarySettings(agentMode = false, inheritApprovalModel = false,
            providers = listOf(
                MaryProviderConfig("alpha", "Alpha", "https://alpha.invalid/v1", Protocol.RESPONSES, modelCount = 17),
                MaryProviderConfig("beta", "Beta", "https://beta.invalid/v1", Protocol.ANTHROPIC),
            ),
            models = listOf(
                MaryModelConfig("alpha-model", "alpha", "remote-alpha", "Alpha custom name", contextWindowTokens = 60_000,
                    contextSource = MaryModelValueSource.CATALOG, maxOutputTokens = 8_000,
                    capabilities = setOf(MaryModelCapability.IMAGE, MaryModelCapability.TOOLS)),
                MaryModelConfig("beta-model", "beta", "remote-beta"),
                MaryModelConfig("helper-model", "alpha", "remote-helper"),
            ), mainModelId = "beta-model", approvalModelId = "alpha-model", subagentModelId = "helper-model",
        )
        check(store.saveRegistrySettings(settings, mapOf("alpha" to "alpha-key", "beta" to "beta-key")))
        return store
    }

    private fun fakeCryptoStore() = AgentSettingsStore(context, ::seal, ::unseal)
    private fun seal(value: String) = "fixture:" + value.reversed()
    private fun unseal(value: String) = value.takeIf { it.startsWith("fixture:") }?.removePrefix("fixture:")?.reversed()
}
