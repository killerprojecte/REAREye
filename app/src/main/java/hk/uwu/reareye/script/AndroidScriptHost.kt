package hk.uwu.reareye.script

import android.content.Context
import hk.uwu.reareye.utils.RootHelper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AndroidScriptHost(context: Context) : ScriptHost {
    private val applicationContext = context.applicationContext
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun rootExec(request: ScriptRootCommand): ScriptRootResult {
        val result = RootHelper.executeRootCommandDetailed(
            command = request.command,
            stdin = request.stdin,
            timeoutMillis = request.timeoutMillis,
        )
        return ScriptRootResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
            outputLimitExceeded = result.outputLimitExceeded,
        )
    }

    override fun httpRequest(request: ScriptHttpRequest): ScriptHttpResponse {
        val client = httpClient.newBuilder()
            .followRedirects(request.followRedirects)
            .followSslRedirects(request.followSslRedirects)
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .connectTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val urlBuilder = request.url.toHttpUrl().newBuilder()
        request.query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val builder = Request.Builder().url(urlBuilder.build())
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        val body = request.body?.toRequestBody(
            request.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.toMediaType() ?: "text/plain; charset=utf-8".toMediaType()
        )
        when (request.method.uppercase()) {
            "GET", "HEAD" -> builder.method(request.method.uppercase(), null)
            "POST", "PUT", "PATCH", "DELETE" -> builder.method(
                request.method.uppercase(),
                body ?: "".toRequestBody()
            )

            else -> error("Unsupported HTTP method: ${request.method}")
        }

        client.newCall(builder.build()).execute().use { response ->
            val headers = response.headers.toMultimap()
                .mapValues { (_, values) -> values.joinToString(",") }
            return ScriptHttpResponse(
                statusCode = response.code,
                ok = response.isSuccessful,
                headers = headers,
                body = response.body?.string().orEmpty(),
                contentType = response.header("Content-Type").orEmpty(),
                url = response.request.url.toString(),
            )
        }
    }

    override fun log(message: String) {
        android.util.Log.d("AndroidScriptHost", "${applicationContext.packageName}: $message")
    }
}
