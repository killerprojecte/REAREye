package hk.uwu.reareye.agent.catalog

import hk.uwu.reareye.agent.ModelDescriptor
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.data.MaryModelCapability
import hk.uwu.reareye.agent.data.MaryModelValueSource
import hk.uwu.reareye.ui.screen.MaryModelCandidate
import hk.uwu.reareye.ui.screen.MarySettingsModelCatalog

internal data class MaryModelCatalogSnapshot(
    private val remote: ModelsDevModelIndex? = null,
    private val builtIn: ModelsDevModelIndex = ModelsDevModelIndex.Empty,
) : MarySettingsModelCatalog {
    override fun models(baseUrl: String, protocol: Protocol): List<MaryModelCandidate> =
        remote?.models(baseUrl, protocol).orEmpty().ifEmpty { builtIn.models(baseUrl, protocol) }.map { model ->
            MaryModelCandidate(
                details = ModelDescriptor(model.id),
                displayName = model.displayName,
                context = model.context,
                output = model.maxOutput,
                capabilities = buildSet {
                    if (model.supports(ModelsDevModelCapability.TEXT)) add(MaryModelCapability.TEXT)
                    if (model.supports(ModelsDevModelCapability.IMAGE)) add(MaryModelCapability.IMAGE)
                    if (model.supports(ModelsDevModelCapability.AUDIO)) add(MaryModelCapability.AUDIO)
                    if (model.supports(ModelsDevModelCapability.TOOLS)) add(MaryModelCapability.TOOLS)
                },
                contextSource = MaryModelValueSource.CATALOG,
                outputSource = MaryModelValueSource.CATALOG,
            )
        }
}
