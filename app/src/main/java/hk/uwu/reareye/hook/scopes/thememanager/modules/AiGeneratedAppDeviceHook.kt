package hk.uwu.reareye.hook.scopes.thememanager.modules

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Spoof the device parameters used by the AI generated rear-screen flow.
 *
 * AiAppPageApi puts the AI page, subject pages, product pages, and generation status
 * requests through the same ParamInterceptor. The hook therefore matches those paths at
 * the final parameter map, while leaving the rest of ThemeManager untouched.
 */
class AiGeneratedAppDeviceHook : RoxyHooker() {
    private val relatedHeaderRequest = ThreadLocal.withInitial { false }
    private val legacyDownloadConnection = ThreadLocal.withInitial { false }
    private val populatedListings = ConcurrentHashMap.newKeySet<String>()

    /** Product ids learned from AI page/subject responses. */
    private val aiProductIds = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TAG = "REAREye-AiGeneratedAppDevice"
        private const val PARAM_DEVICE = "device"
        private const val PARAM_PRODUCT = "product"
        private const val PARAM_MODEL = "model"
        private const val PARAM_VERSION = "version"
        private const val DEVICE_VALUE = "madrid"
        private const val MODEL_VALUE = "M1544F"
        private const val INCREMENTAL_VALUE = "499"
        private const val AI_PAGE_ENDPOINT = "/native/page/v3/AI_GENERATED_APP"
        private const val SUBJECT_PAGE_PREFIX = "/native/page/v3/subjects/"
        private const val NATIVE_THEME_PAGE_PREFIX = "/native/page/v3/theme/"
        private const val THEME_PAGE_PREFIX = "/page/v3/theme/"
        private const val AI_SERVICE_PREFIX = "/ai/"
        private const val AI_CHECK_UPDATE_ENDPOINT = "/checkupdate/hashpair"
        private const val AI_DOWNLOAD_PREFIX = "/download/v2/"
        private const val CACHE_CONTROL_HEADER = "Cache-Control"
        private const val CACHE_CONTROL_VALUE = "no-cache"
    }

    override fun PackageScope.onHook() {
        loadApp("com.android.thememanager") {
            val requestClass = "okhttp3.Request".toClass()
            val requestBuilderClass = "okhttp3.Request\$Builder".toClass()
            val httpUrlClass = "okhttp3.HttpUrl".toClass()
            val headersClass = "okhttp3.Headers".toClass()
            val requestBodyClass = "okhttp3.RequestBody".toClass()
            val responseClass = "okhttp3.Response".toClass()
            val responseBodyClass = "okhttp3.ResponseBody".toClass()
            val chainClass = "okhttp3.Interceptor\$Chain".toClass()
            val continuationClass = "kotlin.coroutines.Continuation".toClass()
            val contextClass = "android.content.Context".toClass()
            val interceptorClass =
                "com.android.thememanager.basemodule.network.theme.interceptors.ParamInterceptor"
                    .toClass()
            val headerInterceptorClass =
                "com.android.thememanager.basemodule.network.theme.interceptors.HeaderInterceptor"
                    .toClass()
            val deviceUtilsClass = "com.android.thememanager.basemodule.utils.DeviceUtils".toClass()
            val requestUrlClass = "com.android.thememanager.controller.online.RequestUrl".toClass()
            val pairClass = "android.util.Pair".toClass()
            val themeConnectionClass =
                "com.android.thememanager.controller.online.ThemeConnection".toClass()
            val aiUserInfoClass = "com.android.thememanager.util.ai.AIUserInfo".toClass()
            val urlClass = URL::class.java
            val urlConnectionClass = URLConnection::class.java

            // Mark the id at the AI provider boundary. The method name is obfuscated in
            // releases, so match its stable signature instead of hardcoding the name.
            runCatching {
                "com.rearScreen.aiapp.impl.ListDataProviderImpl".toClass().resolve().firstMethod {
                    parameters(String::class.java, continuationClass)
                    returnType = Any::class.java
                }.hook {
                    before {
                        val productId = args.getOrNull(0)?.toString()?.trim().orEmpty()
                        if (productId.isNotEmpty()) {
                            aiProductIds.add(productId)
                            YLog.debug("[$TAG] AI provider detail productId=$productId")
                        }
                    }
                }
            }.onFailure {
                YLog.debug("[$TAG] AI provider detail hook unavailable: $it")
            }

            interceptorClass.resolve().firstMethod {
                // Match ParamInterceptor's request builder by signature. Its
                // method name is obfuscated and must not be relied upon.
                parameters(requestClass, LinkedHashMap::class.java, String::class.java)
                returnType = requestClass
            }.hook {
                before {
                    val request = args[0] ?: return@before
                    if (!isAiGeneratedRelatedRequest(request, httpUrlClass)) {
                        val path = requestPath(request, httpUrlClass)
                        if (path != null && isThemeDetailPath(path)) {
                            YLog.debug(
                                "[$TAG] skip non-AI theme detail path=$path " +
                                        "knownAiProductIds=${aiProductIds.size}"
                            )
                        }
                        return@before
                    }

                    @Suppress("UNCHECKED_CAST")
                    val params = args[1] as? MutableMap<Any?, Any?> ?: return@before
                    val previousDevice = params[PARAM_DEVICE]
                    val previousModel = params[PARAM_MODEL]
                    val previousProduct = params[PARAM_PRODUCT]
                    val previousVer = params[PARAM_VERSION]?.toString()
                    params[PARAM_DEVICE] = DEVICE_VALUE
                    // Product is copied from the original query only when that endpoint
                    // already carries it; do not add a new parameter to the signature.
                    if (params.containsKey(PARAM_PRODUCT)) {
                        params[PARAM_PRODUCT] = DEVICE_VALUE
                    }
                    if (params.containsKey(PARAM_MODEL)) {
                        params[PARAM_MODEL] = MODEL_VALUE
                    }
                    if (previousVer != null) {
                        params[PARAM_VERSION] = rewriteIncremental(previousVer)
                    }
                    YLog.debug(
                        "[$TAG] device=$previousDevice -> $DEVICE_VALUE " +
                                "product=$previousProduct -> ${params[PARAM_PRODUCT]} " +
                                "model=$previousModel -> ${params[PARAM_MODEL]} " +
                                "version=$previousVer -> ${params[PARAM_VERSION]}"
                    )
                }
                after {
                    val request = result ?: return@after
                    val path = requestPath(request, httpUrlClass) ?: return@after
                    if (isAiThemeDetailRequest(request, httpUrlClass)) {
                        debugRequest(
                            request,
                            httpUrlClass,
                            headersClass,
                            requestBodyClass,
                        )
                    }
                    if (!isListingPath(path) || path in populatedListings) return@after
                    val uncachedRequest = addNoCacheHeaders(
                        request,
                        requestClass,
                        requestBuilderClass,
                    ) ?: return@after
                    result = uncachedRequest
                    YLog.debug("[$TAG] revalidate unpopulated listing $path")
                }
            }

            interceptorClass.resolve().firstMethod {
                parameters(chainClass)
                returnType = responseClass
            }.hook {
                after {
                    val request = chainRequest(args[0], requestClass) ?: return@after
                    val path = requestPath(request, httpUrlClass) ?: return@after
                    if (isAiThemeDetailRequest(request, httpUrlClass)) {
                        debugResponse(
                            result,
                            headersClass,
                            responseBodyClass,
                            "response.ai-detail",
                        )
                    }
                    if (!isListingPath(path) || path in populatedListings) return@after
                    if (responseHasProducts(result, responseBodyClass)) {
                        populatedListings.add(path)
                        YLog.debug("[$TAG] products found; normal cache enabled for $path")
                    }
                }
            }

            // HeaderInterceptor obtains the final User-Agent from DeviceUtils.kja0(context).
            // Keep the flag on the current thread so the system UA is changed only while one
            // of the AI/subject/theme requests is being assembled.
            headerInterceptorClass.resolve().firstMethod {
                parameters(chainClass)
                returnType = responseClass
            }.hook {
                before {
                    val request = chainRequest(args[0], requestClass)
                    relatedHeaderRequest.set(
                        request != null && isAiGeneratedRelatedRequest(request, httpUrlClass)
                    )
                }
                after {
                    relatedHeaderRequest.remove()
                }
            }

            deviceUtilsClass.resolve().firstMethod {
                parameters(contextClass)
                returnType = String::class.java
            }.hook {
                after {
                    if (relatedHeaderRequest.get() != true) return@after
                    val userAgent = result?.toString() ?: return@after
                    val spoofedUserAgent = spoofUserAgent(userAgent)
                    if (spoofedUserAgent != userAgent) {
                        result = spoofedUserAgent
                        YLog.debug("[$TAG] User-Agent model rewritten for related endpoint")
                    }
                }
            }

            // AI service request bodies snapshot DeviceUtils values in AIUserInfo before
            // Retrofit serializes them. Rewrite the snapshot itself as well as URL headers.
            /*aiUserInfoClass.resolve().firstConstructor {
                parameterCount = 0
            }.hook { after {
                rewriteAiUserInfo(instance)
            } }*/

            // AI app downloads use the legacy OnlineService/RequestUrl path instead of
            // Retrofit, so ParamInterceptor never sees them. RequestUrl.k() has already
            // merged OnlineService.was()'s environment map when it returns this Pair; mutate
            // that map before getFinalGetUrl() serializes it.
            requestUrlClass.resolve().firstMethod {
                modifiers(Modifiers.PRIVATE)
                parameterCount = 0
                returnType = pairClass
            }.hook {
                after {
                    val pair = result ?: return@after
                    spoofLegacyDownloadPair(pair, pairClass)
                }
            }

            // The legacy downloader uses HttpURLConnection, whose default UA can still carry
            // the real model. Scope the change to ThemeConnection's /download/v2/ connection.
            themeConnectionClass.resolve().firstMethod {
                modifiers(Modifiers.PRIVATE)
                parameterCount = 0
                returnType = Void.TYPE
            }.hook {
                before {
                    legacyDownloadConnection.set(isLegacyDownloadConnection(instance))
                }
                after {
                    legacyDownloadConnection.remove()
                }
            }

            urlClass.resolve().firstMethod {
                parameterCount = 0
                returnType = urlConnectionClass
            }.hook {
                after {
                    if (legacyDownloadConnection.get() != true) return@after
                    val connection = result as? HttpURLConnection ?: return@after
                    val userAgent = System.getProperty("http.agent") ?: return@after
                    val spoofedUserAgent = spoofUserAgent(userAgent)
                    if (spoofedUserAgent != userAgent) {
                        connection.setRequestProperty("User-Agent", spoofedUserAgent)
                        YLog.debug("[$TAG] legacy download User-Agent model rewritten")
                    }
                }
            }

            @Suppress("ConstantConditionIf")
            if (false) {
                interceptorClass.resolve().firstMethod {
                    parameters(chainClass)
                    returnType = responseClass
                }.hook {
                    after {
                        val request = chainRequest(args[0], requestClass) ?: return@after
                        if (isAiGeneratedRelatedRequest(request, httpUrlClass)) {
                            debugResponse(
                                result,
                                headersClass,
                                responseBodyClass,
                                "response.after-param",
                            )
                        }
                    }
                }

                val curlClass =
                    "com.android.thememanager.basemodule.network.theme.CurlLoggingInterceptor"
                        .toClass()
                curlClass.resolve().firstMethod {
                    // CurlLoggingInterceptor(Interceptor.Chain) -> Response.
                    // Match by signature because the method name is obfuscated.
                    parameters(chainClass)
                    returnType = responseClass
                }.hook {
                    before {
                        val request = chainRequest(args[0], requestClass) ?: return@before
                        if (isAiGeneratedRelatedRequest(request, httpUrlClass)) {
                            debugRequest(request, httpUrlClass, headersClass, requestBodyClass)
                        }
                    }
                }

                YLog.debug("[$TAG] installed for AI/subject/theme endpoints with request/response debug")
            }
        }
    }

    private fun PackageScope.chainRequest(chain: Any?, requestClass: Class<*>): Any? {
        if (chain == null) return null
        return runCatching {
            chain.asResolver().firstMethod {
                parameterCount = 0
                returnType = requestClass
            }.invoke()
        }.getOrNull()
    }

    private fun PackageScope.addNoCacheHeaders(
        request: Any,
        requestClass: Class<*>,
        requestBuilderClass: Class<*>,
    ): Any? {
        return runCatching {
            val builder = request.asResolver().firstMethod {
                parameterCount = 0
                returnType = requestBuilderClass
            }.invoke() ?: return@runCatching null
            builder.asResolver().firstMethod {
                parameters(String::class.java, String::class.java)
                returnType = requestBuilderClass
            }.invoke(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE)
            builder.asResolver().firstMethod {
                parameterCount = 0
                returnType = requestClass
            }.invoke()
        }.onFailure {
            YLog.debug("[$TAG] unable to add no-cache headers: $it")
        }.getOrNull()
    }

    private fun PackageScope.requestUrl(request: Any, httpUrlClass: Class<*>): String? {
        val url = runCatching {
            request.asResolver().firstMethod {
                parameterCount = 0
                returnType = httpUrlClass
            }.invoke()?.toString()
        }.getOrNull() ?: return null
        return url
    }

    private fun PackageScope.isAiGeneratedRelatedRequest(
        request: Any,
        httpUrlClass: Class<*>
    ): Boolean {
        val path = requestPath(request, httpUrlClass) ?: return false
        return isListingPath(path) ||
                isAiThemeDetailRequest(request, httpUrlClass) ||
                path.contains(AI_SERVICE_PREFIX) ||
                path.endsWith(AI_CHECK_UPDATE_ENDPOINT)
    }

    private fun PackageScope.requestPath(request: Any, httpUrlClass: Class<*>): String? {
        return requestUrl(request, httpUrlClass)?.substringBefore('?')?.substringBefore('#')
    }

    private fun PackageScope.isListingPath(path: String): Boolean {
        return path.endsWith(AI_PAGE_ENDPOINT) || path.contains(SUBJECT_PAGE_PREFIX)
    }

    /**
     * Both logged-in and logged-out detail flows use the same product id but different paths.
     * Restrict matching to ids observed in the AI listing response so ordinary ThemeManager
     * details are never sent with the AI device profile.
     */
    private fun PackageScope.isAiThemeDetailPath(path: String): Boolean {
        val productId = themeProductId(path) ?: return false
        return productId.isNotEmpty() && productId in aiProductIds
    }

    private fun PackageScope.isAiThemeDetailRequest(request: Any, httpUrlClass: Class<*>): Boolean {
        val path = requestPath(request, httpUrlClass) ?: return false
        return isAiThemeDetailPath(path)
    }

    private fun PackageScope.themeProductId(path: String): String? {
        val prefix = when {
            path.contains(NATIVE_THEME_PAGE_PREFIX) -> NATIVE_THEME_PAGE_PREFIX
            path.contains(THEME_PAGE_PREFIX) -> THEME_PAGE_PREFIX
            else -> return null
        }
        return path.substringAfter(prefix).substringBefore('/').trim().ifEmpty { null }
    }

    private fun PackageScope.isThemeDetailPath(path: String): Boolean {
        return path.contains(NATIVE_THEME_PAGE_PREFIX) || path.contains(THEME_PAGE_PREFIX)
    }

    private fun PackageScope.responseHasProducts(
        response: Any?,
        responseBodyClass: Class<*>
    ): Boolean {
        if (response == null) return false
        return runCatching {
            val peekBody = response.asResolver().firstMethod {
                parameters(Long::class.javaPrimitiveType!!)
                returnType = responseBodyClass
            }.invoke(4_194_304L) ?: return@runCatching false
            val text = peekBody.asResolver().firstMethod {
                parameterCount = 0
                returnType = String::class.java
                superclass()
            }.invoke()?.toString() ?: return@runCatching false
            containsProducts(JSONObject(text))
        }.onFailure {
            YLog.debug("[$TAG] unable to inspect listing products: $it")
        }.getOrDefault(false)
    }

    private fun PackageScope.containsProducts(value: Any?): Boolean {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key == "products" && child is JSONArray && child.length() > 0) {
                        collectAiProductIds(child)
                        return true
                    }
                    if (containsProducts(child)) return true
                }
                false
            }

            is JSONArray -> (0 until value.length()).any { containsProducts(value.opt(it)) }
            else -> false
        }
    }

    private fun PackageScope.collectAiProductIds(products: JSONArray) {
        for (index in 0 until products.length()) {
            val product = products.optJSONObject(index) ?: continue
            listOf("uuid", "productUuid", "productId", "packId", "onlineId", "id").forEach { key ->
                val id = product.optString(key).trim()
                if (id.isNotEmpty() && id != "null") aiProductIds.add(id)
            }
        }
    }

    private fun PackageScope.rewriteIncremental(previousVer: String): String {
        val parts = previousVer.split(".").toMutableList()
        if (parts.size <= 2) return previousVer
        parts[2] = INCREMENTAL_VALUE
        return parts.joinToString(".")
    }

    private fun PackageScope.spoofUserAgent(userAgent: String): String {
        // Android's Dalvik UA is normally: Android <release>; <model> Build/<id>.
        // Preserve the rest of the UA, including Android release and client tokens.
        val modelPattern = Regex("(?i)(Android\\s+[^;)]*;\\s*)([^;)]*?)(\\s+Build/)")
        return modelPattern.replace(userAgent) {
            "${it.groupValues[1]}$MODEL_VALUE${it.groupValues[3]}"
        }
    }

    private fun PackageScope.rewriteAiUserInfo(userInfo: Any?) {
        if (userInfo == null) return
        runCatching {
            val cls = userInfo.javaClass
            cls.getField(PARAM_DEVICE).set(userInfo, DEVICE_VALUE)
            val agent = cls.getField("agent").get(userInfo)?.toString()
            if (agent != null) {
                cls.getField("agent").set(userInfo, spoofUserAgent(agent))
            }
            YLog.debug("[$TAG] AIUserInfo device/agent rewritten")
        }.onFailure {
            YLog.debug("[$TAG] unable to rewrite AIUserInfo: $it")
        }
    }

    private fun PackageScope.spoofLegacyDownloadPair(pair: Any, pairClass: Class<*>) {
        runCatching {
            val baseUrl = pairClass.getField("first").get(pair)?.toString().orEmpty()
            if (!baseUrl.substringBefore('?').contains(AI_DOWNLOAD_PREFIX)) return@runCatching

            @Suppress("UNCHECKED_CAST")
            val params = pairClass.getField("second").get(pair) as? MutableMap<Any?, Any?>
                ?: return@runCatching
            val previousDevice = params[PARAM_DEVICE]
            val previousModel = params[PARAM_MODEL]
            val previousProduct = params[PARAM_PRODUCT]
            val previousVersion = params[PARAM_VERSION]?.toString()
            params[PARAM_DEVICE] = DEVICE_VALUE
            if (params.containsKey(PARAM_PRODUCT)) params[PARAM_PRODUCT] = DEVICE_VALUE
            if (params.containsKey(PARAM_MODEL)) params[PARAM_MODEL] = MODEL_VALUE
            if (previousVersion != null) params[PARAM_VERSION] = rewriteIncremental(previousVersion)
            YLog.debug(
                "[$TAG] download params url=$baseUrl device=$previousDevice -> $DEVICE_VALUE " +
                        "product=$previousProduct -> ${params[PARAM_PRODUCT]} " +
                        "model=$previousModel -> ${params[PARAM_MODEL]} " +
                        "version=$previousVersion -> ${params[PARAM_VERSION]}"
            )
        }.onFailure {
            YLog.debug("[$TAG] unable to spoof legacy download params: $it")
        }
    }

    private fun PackageScope.isLegacyDownloadConnection(connection: Any?): Boolean {
        if (connection == null) return false
        return runCatching {
            val urlField =
                connection.javaClass.declaredFields.firstOrNull { it.type == URL::class.java }
                    ?: return@runCatching false
            urlField.isAccessible = true
            val url = urlField.get(connection)?.toString().orEmpty()
            url.substringBefore('?').contains(AI_DOWNLOAD_PREFIX)
        }.getOrDefault(false)
    }

    private fun PackageScope.debugRequest(
        request: Any,
        httpUrlClass: Class<*>,
        headersClass: Class<*>,
        requestBodyClass: Class<*>,
    ) {
        debug("request", request.toString())
        debug("request.url", requestUrl(request, httpUrlClass).orEmpty())
        runCatching {
            request.asResolver().firstMethod {
                parameterCount = 0
                returnType = headersClass
            }.invoke()?.let { debug("request.headers", it.toString()) }
        }.onFailure { YLog.debug("[$TAG] request.headers unavailable: $it") }
        runCatching {
            request.asResolver().firstMethod {
                parameterCount = 0
                returnType = requestBodyClass
            }.invoke()?.let { debug("request.body", it.toString()) }
                ?: debug("request.body", "<none>")
        }.onFailure { YLog.debug("[$TAG] request.body unavailable: $it") }
    }

    private fun PackageScope.debugResponse(
        response: Any?,
        headersClass: Class<*>,
        responseBodyClass: Class<*>,
        label: String,
    ) {
        if (response == null) {
            debug(label, "<null>")
            return
        }
        debug(label, response.toString())
        runCatching {
            response.asResolver().firstMethod {
                parameterCount = 0
                returnType = headersClass
            }.invoke()?.let { debug("$label.headers", it.toString()) }
        }.onFailure { YLog.debug("[$TAG] $label.headers unavailable: $it") }
        runCatching {
            val peekBody = response.asResolver().firstMethod {
                parameters(Long::class.javaPrimitiveType!!)
                returnType = responseBodyClass
            }.invoke(1_048_576L)
            if (peekBody == null) {
                debug("$label.body", "<none>")
            } else {
                val bodyText = peekBody.asResolver().firstMethod {
                    parameterCount = 0
                    returnType = String::class.java
                    superclass()
                }.invoke()?.toString().orEmpty()
                debug("$label.body", bodyText)
            }
        }.onFailure { YLog.debug("[$TAG] $label.body unavailable: $it") }
    }

    private fun PackageScope.debug(label: String, value: String) {
        val text = value.ifEmpty { "<empty>" }
        val chunks = text.chunked(2000)
        chunks.forEachIndexed { index, chunk ->
            YLog.debug("[$TAG] $label[${index + 1}/${chunks.size}] $chunk")
        }
    }
}
