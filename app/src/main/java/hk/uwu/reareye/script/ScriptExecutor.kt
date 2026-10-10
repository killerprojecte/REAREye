package hk.uwu.reareye.script

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ScriptExecutor(context: Context) {
    private val runtime = ScriptRuntime(AndroidScriptHost(context))

    suspend fun execute(
        project: ScriptProject,
        providerId: String,
        params: Map<String, Any?> = emptyMap(),
        config: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): ScriptExecutionResult = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        runCatching {
            withTimeout(timeoutMillis) {
                runtime.open(project, timeoutMillis = timeoutMillis).use { session ->
                    require(providerId in session.descriptor.providers.map { it.id }) { "Provider '$providerId' not found" }
                    session.execute(providerId, params, config)
                }
            }
        }.fold(
            onSuccess = { it.copy(elapsedMillis = System.currentTimeMillis() - start) },
            onFailure = {
                ScriptExecutionResult(
                    project.id,
                    providerId,
                    error = it.message ?: it.javaClass.simpleName,
                    elapsedMillis = System.currentTimeMillis() - start
                )
            },
        )
    }

    suspend fun providers(project: ScriptProject): List<ScriptProvider> =
        withContext(Dispatchers.Default) {
            runtime.open(project).use { session -> session.descriptor.providers }
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
