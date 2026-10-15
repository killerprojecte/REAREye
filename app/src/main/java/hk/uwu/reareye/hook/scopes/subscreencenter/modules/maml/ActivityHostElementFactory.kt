package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import hk.uwu.reareye.hook.activityhost.ActivityHostSpec
import org.w3c.dom.Element

/** Leaf carrier retains the host's x/y/w/h layout and gesture pipeline. */
class ActivityHostElementFactory : MamlExpressionBackendFactory {
    override fun prepare(
        element: Element,
        expressions: MamlStringExpressionCompiler
    ): MamlBackendBinding {
        fun sample(name: String, default: String = ""): () -> String {
            val exp = element.getAttribute("${name}Exp").trim()
            if (exp.isNotEmpty()) {
                val evaluate = expressions.compile(exp)
                return { evaluate().orEmpty().trim() }
            }
            val fixed = element.getAttribute(name).trim().ifEmpty { default }
            return { fixed }
        }

        fun numeric(source: String, fallback: () -> String): () -> String {
            val compiler = expressions as? MamlNumericExpressionCompiler ?: return fallback
            if (source.isBlank()) return fallback
            val evaluate = compiler.compileNumber(source)
            return { evaluate()?.takeIf { it.isFinite() }?.toString() ?: "invalid-number" }
        }

        val component = sample("component")
        val instance = sample("instance")
        val request = sample("userRequest")
        val dpi = numeric(element.getAttribute("dpiExp").trim(), sample("dpi", "0"))
        val bounds = sample("bounds")
        val gutter = sample("navigationGutterDp", "24")
        val width = numeric(
            element.getAttribute("wExp").trim().ifBlank { element.getAttribute("w").trim() },
            sample("w")
        )
        val conflicts = conflictErrors(element)
        return MamlBackendBinding(
            createBackend = { ActivityHostBackend() },
            evaluateInputs = {
                val payload = parse(
                    component(), dpi(), bounds(), gutter(), width(), conflicts,
                    element.getAttribute("showControls").trim().ifEmpty { "true" }
                )
                val owner = instance()
                val action = request()
                MamlBackendInputs(
                    "", "", activityHost = payload.copy(
                        instance = owner, userRequest = action,
                        launchPermit = expressions.activityLaunchPermit(
                            owner,
                            payload.component,
                            payload.dpi,
                            action
                        )
                    )
                )
            },
        )
    }

    override fun create(element: Element): MamlViewBackend = ActivityHostBackend(
        parse(
            element.getAttribute("component"),
            element.getAttribute("dpi").ifBlank { "0" },
            element.getAttribute("bounds"),
            element.getAttribute("navigationGutterDp").ifBlank { "24" },
            element.getAttribute("w"),
            conflictErrors(element),
            element.getAttribute("showControls").trim().ifEmpty { "true" }
        ),
    )

    companion object {
        const val TAG = "ActivityHost"
        private fun conflictErrors(element: Element): List<String> =
            listOf("component", "dpi", "bounds")
                .filter {
                    element.getAttribute(it).isNotBlank() && element.getAttribute("${it}Exp")
                        .isNotBlank()
                }
                .map { "$it and ${it}Exp are mutually exclusive" } +
                    if (element.getAttribute("showControlsExp").isNotBlank())
                        listOf("showControls only supports a fixed true or false attribute") else emptyList()

        internal fun parse(
            component: String,
            dpi: String,
            bounds: String,
            gutter: String,
            width: String = "",
            conflicts: List<String> = emptyList(),
            showControls: String = "true"
        ): ActivityHostInputs {
            val errors = conflicts.toMutableList()
            val controls = showControls.toBooleanStrictOrNull()
            if (controls == null) errors += "showControls must be true or false"
            val number = dpi.toDoubleOrNull()
            val density =
                if (number != null && number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number % 1.0 == 0.0)
                    number.toInt() else 0.also { errors += "dpi must be an integer" }
            if (width.isNotBlank() && width.toFloatOrNull()
                    ?.let { it.isFinite() && it > 0f } != true
            )
                errors += "carrier w expression could not be evaluated; MAML scale is unavailable"
            val navigation = gutter.toFloatOrNull() ?: Float.NaN
            val spec = ActivityHostSpec(
                component.trim(),
                density,
                navigation,
                bounds.trim().takeIf { it.isNotEmpty() })
            errors += spec.validate()
            val parsed =
                spec.bounds?.let { runCatching { ActivityHostSpec.parseBounds(it) }.getOrNull() }
            return ActivityHostInputs(
                spec.component,
                density,
                parsed,
                gutterDp = navigation,
                carrierWidth = width.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f },
                validationErrors = errors,
                showControls = controls ?: true
            )
        }
    }
}
