package hk.uwu.reareye.ui.components

import hk.uwu.reareye.agent.CapabilityScope
import hk.uwu.reareye.agent.CapabilitySource
import hk.uwu.reareye.agent.ModelCapabilityParser
import hk.uwu.reareye.agent.ModelDescriptor
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ProviderPreset
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningMapping
import hk.uwu.reareye.agent.ReasoningPolicy
import hk.uwu.reareye.agent.ReasoningStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No settings store, network, Android context or real credentials are touched. */
class MarySettingsModelDraftTest {
    private fun draft() = ModelDraft(
        provider = ProviderConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "saved-test-key",
            model = "custom-model",
            temperature = 0.7,
            maxOutputTokens = 2048,
            contextWindowTokens = 32000,
        ),
        keyDraft = "unsaved-test-key",
        temperature = "0.4",
        output = "1024",
        context = "64000",
    )

    @Test
    fun manualCapabilityUsesEffectiveDraftCredentialAndDoesNotCrossRoutes() {
        val manual = draft().withReasoning(
            ReasoningStatus.SUPPORTED,
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningMapping.OPENAI_EFFORT
        )
        assertEquals(CapabilitySource.MANUAL, ReasoningPolicy.support(manual.build()).source)
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningPolicy.support(manual.build()).levels
        )
        assertEquals(
            ReasoningStatus.UNKNOWN,
            ReasoningPolicy.support(manual.withModel("different").build()).status
        )
        assertEquals(
            ReasoningStatus.UNKNOWN,
            ReasoningPolicy.support(manual.copy(keyDraft = "changed-key").build()).status
        )
        assertEquals(
            ReasoningStatus.UNKNOWN,
            ReasoningPolicy.support(manual.copy(clearKey = true).build()).status
        )
        assertEquals(
            ReasoningStatus.UNKNOWN,
            ReasoningPolicy.support(manual.withPreset(ProviderPreset.ANTHROPIC).build()).status
        )
        assertEquals(
            manual.provider.reasoningEffort,
            manual.withReasoning(null).provider.reasoningEffort
        )
    }

    @Test
    fun metadataSelectionRetainsManualOverrideAndMissingMetadataRetainsExistingFields() {
        val manual = draft().withReasoning(ReasoningStatus.UNSUPPORTED)
        val details = ModelCapabilityParser.parse(
            Json.parseToJsonElement(
                """{"id":"custom-model","reasoning_capabilities":{"supported":true,"levels":["high"],"mapping":"openai_effort"}}"""
            ).jsonObject, manual.connectionConfig()
        )
        val selected = manual.withModelDetails(details)
        assertEquals(CapabilitySource.MANUAL, ReasoningPolicy.support(selected.build()).source)
        assertEquals(ReasoningStatus.UNSUPPORTED, ReasoningPolicy.support(selected.build()).status)
        val missing = selected.withModelDetails(ModelDescriptor("custom-model"))
        assertEquals(selected, missing)
        val coarse = ModelCapabilityParser.parse(
            Json.parseToJsonElement(
                """{"id":"custom-model","supported_parameters":["reasoning"]}"""
            ).jsonObject, selected.connectionConfig()
        )
        assertEquals(selected, selected.withModelDetails(coarse))
        val automatic = selected.withReasoning(null)
        assertEquals(
            CapabilitySource.SERVICE_METADATA,
            ReasoningPolicy.support(automatic.build()).source
        )
        assertEquals(manual.provider.reasoningEffort, automatic.provider.reasoningEffort)
    }

    @Test
    fun mappingChangesPruneImpossibleLevelsAndKeepAtLeastOne() {
        val anthropic =
            draft().copy(provider = draft().provider.copy(protocol = Protocol.ANTHROPIC))
        val budget = anthropic.withReasoning(
            ReasoningStatus.SUPPORTED,
            ReasoningEffort.entries.toList(),
            ReasoningMapping.CLAUDE_BUDGET
        )
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            budget.provider.reasoningOverride!!.levels
        )
        val adaptive = anthropic.withReasoning(
            ReasoningStatus.SUPPORTED,
            listOf(ReasoningEffort.NONE),
            ReasoningMapping.CLAUDE_ADAPTIVE
        )
        assertEquals(listOf(ReasoningEffort.LOW), adaptive.provider.reasoningOverride!!.levels)
        val deepseek = draft().withReasoning(
            ReasoningStatus.SUPPORTED,
            ReasoningEffort.entries.toList(),
            ReasoningMapping.DEEPSEEK_THINKING
        )
        assertEquals(
            listOf(
                ReasoningEffort.NONE,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX
            ), deepseek.provider.reasoningOverride!!.levels
        )
        assertTrue(ReasoningPolicy.support(deepseek.build()).levels.isNotEmpty())
    }

    @Test
    fun selectingCoarseSupportKeepsEffortAndPersistsUnknownLevelHint() {
        val original = draft()
        val details = ModelCapabilityParser.parse(
            Json.parseToJsonElement(
                """{"id":"custom-model","supported_parameters":["reasoning"]}"""
            ).jsonObject, original.connectionConfig()
        )
        val selected = original.withModelDetails(details)
        assertEquals(true, selected.build().reasoningMetadata!!.supportsReasoning)
        assertEquals(ReasoningStatus.UNKNOWN, selected.build().reasoningMetadata!!.status)
        assertEquals(original.provider.reasoningEffort, selected.provider.reasoningEffort)
        assertEquals(original.context, selected.context)
    }

    @Test
    fun sanitizedPublicCredentialTagIsPreservedUnlessUserChangesOrClearsKey() {
        val original = draft().copy(keyDraft = "")
        val public = original.copy(provider = CapabilityScope.publicView(original.provider))
        assertEquals(
            CapabilityScope.from(original.provider),
            CapabilityScope.from(public.connectionConfig())
        )
        assertEquals(
            original.provider.credentialScopeTag,
            public.copy(clearKey = true).build().credentialScopeTag
        )
        val manual = public.withReasoning(ReasoningStatus.SUPPORTED)
        assertEquals(CapabilitySource.MANUAL, ReasoningPolicy.support(manual.build()).source)
        assertEquals(null, manual.copy(keyDraft = "replacement").build().credentialScopeTag)
        assertEquals(null, manual.copy(clearKey = true).build().credentialScopeTag)
    }

    @Test
    fun selectingModelAppliesOnlyVerifiedCapacityAndKeepsOtherEdits() {
        val original = draft()
        val changed = original.withModel("listed-model", 200000)
        assertEquals("listed-model", changed.provider.model)
        assertEquals("200000", changed.context)
        assertEquals(original.keyDraft, changed.keyDraft)
        assertEquals(original.temperature, changed.temperature)
        assertEquals(original.output, changed.output)
        assertEquals(original.provider.apiKey, changed.provider.apiKey)
    }

    @Test
    fun unknownOrInvalidCapacityNeverInventsOrOverwritesManualValue() {
        val original = draft()
        listOf(null, 0, -1).forEach { capacity ->
            val selected = original.withModel("custom-or-listed", capacity)
            assertEquals("custom-or-listed", selected.provider.model)
            assertEquals(original.context, selected.context)
        }
    }

    @Test
    fun modelListingDoesNotDependOnAdvancedFieldsOrAnExistingModel() {
        val incomplete = draft().copy(
            context = "", output = "invalid",
            provider = draft().provider.copy(model = "")
        )
        assertFalse(incomplete.valid())
        assertTrue(incomplete.connectionValid())
        assertEquals("unsaved-test-key", incomplete.connectionConfig().apiKey)
        assertEquals("", incomplete.copy(clearKey = true).connectionConfig().apiKey)
        assertFalse(
            incomplete.copy(provider = incomplete.provider.copy(baseUrl = "not a url"))
                .connectionValid()
        )
    }

    @Test
    fun presetsChangeOnlyEndpointAndProtocol() {
        val original = draft()
        ProviderPreset.entries.forEach { preset ->
            val changed = original.withPreset(preset)
            assertEquals(preset.baseUrl, changed.provider.baseUrl)
            assertEquals(preset.protocol, changed.provider.protocol)
            assertEquals(
                original, changed.copy(
                    provider = changed.provider.copy(
                        baseUrl = original.provider.baseUrl,
                        protocol = original.provider.protocol,
                    )
                )
            )
            assertTrue(changed.valid())
            assertEquals("unsaved-test-key", changed.build().apiKey)
            assertEquals("custom-model", changed.build().model)
        }
    }

    @Test
    fun presetPreservesExplicitKeyRemoval() {
        val original = draft().copy(clearKey = true)
        ProviderPreset.entries.forEach { preset ->
            val changed = original.withPreset(preset)
            assertTrue(changed.clearKey)
            assertEquals(original.keyDraft, changed.keyDraft)
            assertEquals("", changed.build().apiKey)
        }
    }

    @Test
    fun protocolSelectionKeepsDraftFieldsAndBuildsSelectedProtocol() {
        val original = draft()
        Protocol.entries.forEachIndexed { index, protocol ->
            val selected =
                original.copy(provider = original.provider.copy(protocol = Protocol.entries[index]))
            assertEquals(protocol, selected.build().protocol)
            assertEquals(original.keyDraft, selected.keyDraft)
            assertEquals(original.output, selected.output)
            assertEquals(original.context, selected.context)
        }
    }

    @Test
    fun applyingToApprovalDraftDoesNotChangeDialogueOrBypassValidation() {
        val dialogue = draft()
        val approval = draft().copy(context = "1024", output = "not-a-number")
        val changedApproval = approval.withPreset(ProviderPreset.ANTHROPIC)
        assertEquals(draft(), dialogue)
        assertEquals(Protocol.ANTHROPIC, changedApproval.provider.protocol)
        assertFalse(changedApproval.valid())
        assertEquals(approval.context, changedApproval.context)
        assertEquals(approval.output, changedApproval.output)
    }
}
