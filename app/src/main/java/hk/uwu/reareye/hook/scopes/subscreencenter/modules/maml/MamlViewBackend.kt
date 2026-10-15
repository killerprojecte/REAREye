package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import android.view.View
import org.w3c.dom.Element

/** Backends never reference host MAML classes. All callbacks run on the main thread. */
interface MamlViewBackend {
    fun createView(context: Context): View
    fun onInputsChanged(inputs: MamlBackendInputs) {}
    fun onInit() {}
    fun onResume() {}
    fun onPause() {}
    fun onVisibilityChanged(visible: Boolean) {}
    fun onAttachmentChanged(attached: Boolean) {}
    fun onSizeChanged(widthPx: Int, heightPx: Int) {}
    fun onFinish() {}
}

/** Immutable native-thread snapshot; no host Variables or Expression crosses into Android main. */
data class MamlBackendInputs(
    val key: String,
    val provider: String,
    val visible: Boolean = false,
    /** Ephemeral explicit user action, never restored from the card's persisted selection. */
    val userRequest: String = "",
    val activityHost: ActivityHostInputs? = null,
)

/** Activity pixels are independent of MAML units and the requested task density. */
data class ActivityHostInputs(
    val component: String,
    val dpi: Int = 0,
    val bounds: List<Float>? = null,
    val mamlScale: Float = 1f,
    val gutterDp: Float = 24f,
    /** Carrier logical width, when available, lets the backend recover the actual MAML scale. */
    val carrierWidth: Float? = null,
    val validationErrors: List<String> = emptyList(),
    val instance: String = "",
    val userRequest: String = "",
    val launchPermit: ActivityHostLaunchPermit? = null,
    /** Passive embedding has no native buttons or input-consuming overlays. */
    val showControls: Boolean = true,
)

fun interface MamlStringExpressionCompiler {
    /** The returned evaluator must only be called on the native MAML tick thread. */
    fun compile(expression: String): () -> String?

    /** Optional scope-issued capability; a string expression alone is not authorization. */
    fun activityLaunchPermit(
        instance: String,
        component: String,
        dpi: Int,
        request: String
    ): ActivityHostLaunchPermit? = null
}

class MamlBackendBinding(
    val createBackend: () -> MamlViewBackend,
    val evaluateInputs: () -> MamlBackendInputs,
)

/** Optional capability; plain registered factories retain their existing create(Element) ABI. */
interface MamlExpressionBackendFactory : MamlViewBackendFactory {
    fun prepare(element: Element, expressions: MamlStringExpressionCompiler): MamlBackendBinding
}

fun interface MamlViewBackendFactory {
    /** Parse only here: factories may run on a MAML loading thread, before a View exists. */
    fun create(element: Element): MamlViewBackend
}
