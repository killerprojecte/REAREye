package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.content.Context
import android.os.Build
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.json.JSONObject
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Rewrites device identity only while PersonalAssistant is building a back-screen request.
 *
 * The request builder and CommonParamsUtil are resolved from stable string/shape anchors with
 * DexKit. No obfuscated application class or method name is embedded here.
 */
@OptIn(DexKitExperimentalApi::class)
class PersonalAssistantBackScreenDeviceHook : RoxyHooker() {
    companion object {
        private const val TAG = "REAREye-PersonalAssistantBackScreen"
        private const val MODEL = "M1544F"
        private const val DEVICE = "madrid"
        private const val BACK_SCREEN_PAGE = "component/store/backPage"
        private const val BACK_SCREEN_DETAIL = "component/store/impl/tail"
        private const val BACK_SCREEN_UPDATE = "component/store/updateInfo/backScreen"

        private const val WRAPPER_CACHE_KEY = "PA_BACKSCREEN_COMMON_PARAMS_WRAPPER"
        private const val COMMON_PARAMS_CACHE_KEY = "PA_BACKSCREEN_COMMON_PARAMS_BUILDER"
        private const val DEVICE_FORM_CACHE_KEY = "PA_BACKSCREEN_DEVICE_FORM_BUILDER"
        private const val ENCRYPTOR_CACHE_KEY = "PA_BACKSCREEN_ENCRYPT_INTERCEPTOR"
        private const val CHAIN_PROCEED_CACHE_KEY = "PA_BACKSCREEN_CHAIN_PROCEED"
    }

    private val backScreenRequest = ThreadLocal.withInitial { false }
    private val encryptedBackScreenRequest = ThreadLocal.withInitial { false }

    override fun PackageScope.onHook() {
        loadApp("com.miui.personalassistant") {
            val bridge = runCatching { createBridge() }
                .onFailure { YLog.warn("[$TAG] DexKit init failed: $it") }
                .getOrNull()
                ?: return@loadApp

            val wrapper = resolveWrapper(bridge)
            val commonParams = resolveCommonParams(bridge)
            val deviceForm = resolveDeviceForm(bridge)
            val encryptor = resolveEncryptor(bridge)
            val chainProceed = resolveChainProceed(bridge)
            if (wrapper == null && commonParams == null && deviceForm == null &&
                encryptor == null && chainProceed == null
            ) {
                YLog.warn("[$TAG] no compatible injection point found")
                return@loadApp
            }

            if (wrapper != null) installWrapperMarker(wrapper)
            installJsonRewrite()
            if (commonParams != null) installCommonParamsRewrite(commonParams)
            if (deviceForm != null) installDeviceFormRewrite(deviceForm)
//            if (encryptor != null) installEncryptorDebug(encryptor)
//            if (chainProceed != null) installFinalRequestDebug(chainProceed)

            YLog.info(
                "[$TAG] installed wrapper=${wrapper != null}, " +
                        "commonParams=${commonParams != null}, deviceForm=${deviceForm != null}, " +
                        "encryptor=${encryptor != null}, chainProceed=${chainProceed != null}",
            )
        }
    }

    private fun PackageScope.createBridge(): DexKitCacheBridge.RecyclableBridge {
        val versionCode = resolveHookPackageVersionCode(
            context = hookSystemContext,
            packageName = hookAppInfo.packageName,
            sourceDir = hookAppInfo.sourceDir,
        )
        return runtime.manage(
            createDexKitCacheBridge(
                packageName = hookAppInfo.packageName,
                packageVersionCode = versionCode,
                sourceDir = hookAppInfo.sourceDir,
                dataDir = hookAppInfo.dataDir,
            ),
        )
    }

    private fun PackageScope.resolveWrapper(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ) = resolveDexKitMethodInjectionPoint(bridge, WRAPPER_CACHE_KEY) {
        // This is the interceptor that creates userSignal/environmentSignal/timeSignal/eventSignal.
        // Its owner and method name are obfuscated in the target APK, so only code anchors are used.
        findMethod {
            matcher {
                paramCount(1)
                usingStrings("userSignal", "environmentSignal", "timeSignal", "eventSignal")
            }
        }.singleOrNull()
    }

    private fun PackageScope.resolveCommonParams(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ) = resolveDexKitMethodInjectionPoint(bridge, COMMON_PARAMS_CACHE_KEY) {
        findMethod {
            searchPackages("com.miui.personalassistant.network.util")
            matcher {
                paramCount(2)
                returnType = "org.json.JSONObject"
                usingStrings("phoneModel", "phoneDevice", "backScreenVersion")
            }
        }.singleOrNull()
    }

    private fun PackageScope.resolveDeviceForm(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ) = resolveDexKitMethodInjectionPoint(bridge, DEVICE_FORM_CACHE_KEY) {
        findMethod {
            searchPackages("com.miui.personalassistant.maml.expand.device")
            matcher {
                paramCount(0)
                usingStrings("imei", "product", "model", "langType", "device", "version")
            }
        }.singleOrNull()
    }

    private fun PackageScope.resolveEncryptor(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ) = resolveDexKitMethodInjectionPoint(bridge, ENCRYPTOR_CACHE_KEY) {
        findMethod {
            matcher {
                paramCount(1)
                usingStrings("EncryptInterceptor", "decryptResponse", "application/octet-stream")
            }
        }.singleOrNull()
    }

    private fun PackageScope.resolveChainProceed(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ) = resolveDexKitMethodInjectionPoint(bridge, CHAIN_PROCEED_CACHE_KEY) {
        // RealInterceptorChain.proceed(Request) is called after BridgeInterceptor has
        // added Host, Accept-Encoding and User-Agent. Resolve it from its stable guard
        // strings instead of embedding its obfuscated owner or method name.
        findMethod {
            matcher {
                paramCount(1)
                usingStrings(
                    "Check failed.",
                    "network interceptor",
                    "must call proceed() exactly once",
                )
            }
        }.singleOrNull()
    }

    private fun PackageScope.installWrapperMarker(point: hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint) {
        point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 1
        }.hook {
            before {
                backScreenRequest.set(containsBackScreenUrl(args.firstOrNull()))
            }
            after {
                backScreenRequest.remove()
            }
        }
    }

    private fun PackageScope.installJsonRewrite() {
        JSONObject::class.java.resolve().firstMethod {
            parameters(String::class.java, Any::class.java)
            returnType = JSONObject::class.java
        }.hook {
            before {
            if (backScreenRequest.get() != true) return@before
            val key = args.getOrNull(0) as? String ?: return@before
            val value = args.getOrNull(1) ?: return@before
            args[1] = rewriteJsonValue(key, value)
            }
        }
    }

    private fun PackageScope.rewriteJsonValue(key: String, value: Any): Any {
        val normalized = key.lowercase()
        return when (normalized) {
            "phonemodel", "model" -> MODEL
            "phonedevice", "device", "product" -> DEVICE
            "os" -> rewriteIncremental(value.toString())
            "useragent", "user-agent" -> value.toString()
                .replace(Build.MODEL, MODEL)
                .replace(Build.DEVICE, DEVICE)
                .replace(Build.PRODUCT, DEVICE)
                .replace(Build.VERSION.INCREMENTAL, rewriteIncremental(Build.VERSION.INCREMENTAL))

            else -> value
        }
    }

    private fun PackageScope.installCommonParamsRewrite(
        point: hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint,
    ) {
        point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameters(Context::class.java, String::class.java)
            returnType = JSONObject::class.java
        }.hook {
            after {
            if (backScreenRequest.get() != true) return@after
            val json = result as? JSONObject ?: return@after
            val currentIncremental = json.optString("os")
            val rewrittenIncremental = rewriteIncremental(currentIncremental)
            json.put("phoneModel", MODEL)
            json.put("phoneDevice", DEVICE)
            if (rewrittenIncremental.isNotEmpty()) json.put("os", rewrittenIncremental)
            YLog.debug(
                "[$TAG] environmentSignal.after=$json",
            )
            }
        }
    }

    private fun PackageScope.installDeviceFormRewrite(
        point: hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint,
    ) {
        point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 0
        }.hook {
            after {
            if (backScreenRequest.get() != true) return@after
            val body = result ?: return@after
            rewriteFormBody(body)?.let {
                result = it
                YLog.debug("[$TAG] device-info form.after=${describeFormBody(it)}")
            }
            }
        }
    }

    private fun PackageScope.installEncryptorDebug(
        point: hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint,
    ) {
        point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 1
        }.hook {
            before {
                val request = findBackScreenRequest(args.firstOrNull())
                val matched = request != null
                encryptedBackScreenRequest.set(matched)
                if (matched) {
                    debug("request.before-encrypt", describeRequest(request))
                }
            }
            after {
                if (encryptedBackScreenRequest.get() == true) {
                    val captured = describeResponse(result)
                    debug("response.after-decrypt", captured.text)
                    captured.restoredResponse?.let { result = it }
                }
                encryptedBackScreenRequest.remove()
            }
        }
    }

    private fun PackageScope.installFinalRequestDebug(
        point: hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint,
    ) {
        point.className.toClass().resolve().firstMethod {
            name = point.methodName
            parameterCount = 1
        }.hook {
            before {
            val request = args.firstOrNull() ?: return@before
            if (!isBackScreenRequest(request)) return@before
            debug("request.after-bridge", describeRequest(request))
            }
        }
    }

    private fun PackageScope.rewriteIncremental(value: String): String {
        if (value.isEmpty()) return value
        val parts = value.split(".").toMutableList()
        if (parts.size <= 2) return value
        parts[2] = "499"
        //parts[4] = "XFRCNXM"
        return parts.joinToString(".")
    }

    private fun PackageScope.fieldsOf(type: Class<*>): Sequence<java.lang.reflect.Field> =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }

    private fun PackageScope.methodsOf(type: Class<*>): Sequence<java.lang.reflect.Method> =
        type.methods.asSequence() + generateSequence(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }

    private fun PackageScope.rewriteFormBody(body: Any): Any? {
        val listFields = fieldsOf(body.javaClass).filter { field ->
            !Modifier.isStatic(field.modifiers) && List::class.java.isAssignableFrom(field.type)
        }.toList()
        if (listFields.size < 2) return null

        val values = listFields.mapNotNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(body) as? List<*>
            }.getOrNull()
        }
        val names = values.firstOrNull { list ->
            list.any { it == "product" } && list.any { it == "model" } && list.any { it == "device" }
        } ?: return null
        val encodedValues =
            values.firstOrNull { it !== names && it.size == names.size } ?: return null

        val newNames = ArrayList(names.map { it?.toString().orEmpty() })
        val newValues = ArrayList(encodedValues.map { it?.toString().orEmpty() })
        for (index in newNames.indices) {
            when (newNames[index]) {
                "model" -> newValues[index] = MODEL
                "device", "product" -> newValues[index] = DEVICE
            }
        }

        val constructor = body.javaClass.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterTypes.size == 2 && ctor.parameterTypes.all { type ->
                type.isAssignableFrom(ArrayList::class.java)
            }
        } ?: return null
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(newNames, newValues)
        }.getOrNull()
    }

    private fun PackageScope.describeFormBody(body: Any): String {
        val fields = fieldsOf(body.javaClass).filter { field ->
            !Modifier.isStatic(field.modifiers) && List::class.java.isAssignableFrom(field.type)
        }
        return fields.joinToString(prefix = "{", postfix = "}") { field ->
            val value = runCatching {
                field.isAccessible = true
                field.get(body)
            }.getOrNull()
            "${field.name}=$value"
        }
    }

    private fun PackageScope.describeRequest(request: Any?): String {
        if (request == null) return "<null>"
        val body = findBody(request)
        val headers = findHeaders(request)
        return buildString {
            append(request)
            if (headers != null) append("\nheaders=").append(headers)
            if (body != null) {
                append("\nbody=")
                append(readRequestBody(body) ?: extractBytes(body) ?: body)
            } else {
                append("\nbody=<unavailable>")
            }
        }
    }

    private data class ResponseCapture(
        val text: String,
        val restoredResponse: Any?,
    )

    private fun PackageScope.findHeaders(request: Any): Any? {
        val headerMethod = methodsOf(request.javaClass).firstOrNull { method ->
            method.parameterTypes.isEmpty() &&
                    looksLikeHeadersType(method.returnType)
        }
        val fromMethod = runCatching {
            headerMethod?.let {
                it.isAccessible = true
                it.invoke(request)
            }
        }.getOrNull()
        if (fromMethod != null) return fromMethod

        return fieldsOf(request.javaClass).firstNotNullOfOrNull { field ->
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@firstNotNullOfOrNull null
            runCatching {
                field.isAccessible = true
                field.get(request).takeIf { looksLikeHeaders(it) }
            }.getOrNull()
        }
    }

    private fun PackageScope.looksLikeHeadersType(type: Class<*>): Boolean =
        type.name.startsWith("okhttp3.") &&
                Iterable::class.java.isAssignableFrom(type) &&
                methodsOf(type).any { method ->
                    method.parameterTypes.isEmpty() && method.returnType == Int::class.javaPrimitiveType
                }

    private fun PackageScope.looksLikeHeaders(value: Any?): Boolean =
        value != null && looksLikeHeadersType(value.javaClass)

    private fun PackageScope.describeResponse(response: Any?): ResponseCapture {
        if (response == null) return ResponseCapture("<null>", null)
        val body = findResponseBody(response)
            ?: return ResponseCapture("$response\nbody=<unavailable>", null)

        val bytes = readResponseBytes(body)
            ?: return ResponseCapture("$response\nbody=<unavailable>", null)
        val restored = rebuildResponseWithBody(response, body, bytes)
        return ResponseCapture(
            text = buildString {
                append(response)
                append("\nbody=")
                append(String(bytes, StandardCharsets.UTF_8))
            },
            restoredResponse = restored,
        )
    }

    private fun PackageScope.findResponseBody(response: Any): Any? {
        return fieldsOf(response.javaClass).firstNotNullOfOrNull { field ->
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@firstNotNullOfOrNull null
            val nested = runCatching {
                field.isAccessible = true
                field.get(response)
            }.getOrNull()
            if (looksLikeResponseBody(nested)) nested else null
        }
    }

    private fun PackageScope.looksLikeResponseBody(value: Any?): Boolean {
        if (value == null) return false
        val methods = methodsOf(value.javaClass)
        val hasBytes = methods.any { method ->
            method.parameterTypes.isEmpty() && method.returnType == ByteArray::class.java
        }
        val hasLength = methods.any { method ->
            method.parameterTypes.isEmpty() && method.returnType == Long::class.javaPrimitiveType
        }
        return hasBytes && hasLength
    }

    private fun PackageScope.readResponseBytes(body: Any): ByteArray? {
        val method = methodsOf(body.javaClass).firstOrNull { candidate ->
            candidate.parameterTypes.isEmpty() && candidate.returnType == ByteArray::class.java
        } ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(body) as? ByteArray
        }.onFailure {
            YLog.debug("[$TAG] response body bytes unavailable: $it")
        }.getOrNull()
    }

    private fun PackageScope.rebuildResponseWithBody(
        response: Any,
        originalBody: Any,
        bytes: ByteArray
    ): Any? {
        return runCatching {
            val mediaType = methodsOf(originalBody.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                        method.returnType.simpleName == "s" &&
                        method.returnType.name == "okhttp3.s"
            }?.let { method ->
                method.isAccessible = true
                method.invoke(originalBody)
            }

            val bodyFactory = methodsOf(originalBody.javaClass).firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == ByteArray::class.java &&
                        method.returnType.name == "okhttp3.a0"
            } ?: return@runCatching null
            bodyFactory.isAccessible = true
            val replacementBody =
                bodyFactory.invoke(null, bytes, mediaType) ?: return@runCatching null

            val builderMethod = methodsOf(response.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                        method.returnType != response.javaClass &&
                        methodsOf(method.returnType).any { nested ->
                            nested.parameterTypes.isEmpty() && nested.returnType == response.javaClass
                        }
            } ?: return@runCatching null
            builderMethod.isAccessible = true
            val builder = builderMethod.invoke(response) ?: return@runCatching null

            val bodyField = fieldsOf(builder.javaClass).firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) &&
                        field.type.name == "okhttp3.a0" &&
                        field.type.isAssignableFrom(replacementBody.javaClass)
            } ?: return@runCatching null
            bodyField.isAccessible = true
            bodyField.set(builder, replacementBody)

            val buildMethod = methodsOf(builder.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() && method.returnType == response.javaClass
            } ?: return@runCatching null
            buildMethod.isAccessible = true
            buildMethod.invoke(builder)
        }.onFailure {
            YLog.debug("[$TAG] response body restore unavailable: $it")
        }.getOrNull()
    }

    private fun PackageScope.readBodyText(body: Any): String? {
        val methods = methodsOf(body.javaClass)
        val textMethod = methods.firstOrNull { method ->
            method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java &&
                    method.name != "toString" &&
                    method.declaringClass != Any::class.java
        }
        if (textMethod != null) {
            val text = runCatching {
                textMethod.isAccessible = true
                textMethod.invoke(body) as? String
            }.getOrNull()
            if (text != null) return text
        }
        val bytesMethod = methods.firstOrNull { method ->
            method.parameterTypes.isEmpty() && method.returnType == ByteArray::class.java
        }
        return runCatching {
            bytesMethod?.let {
                it.isAccessible = true
                (it.invoke(body) as? ByteArray)?.let { bytes ->
                    String(bytes, StandardCharsets.UTF_8)
                }
            }
        }.getOrNull()
    }

    private fun PackageScope.readRequestBody(body: Any): String? {
        // JADX shows okhttp3.w stores the payload in an okio.ByteString field and
        // forwards it through qo.h.d0(ByteString). Read that ByteString directly.
        return extractBytes(body)
    }

    private fun PackageScope.findBody(root: Any?): Any? {
        if (root == null) return null
        // Request/Response have a direct body field. Do not recursively classify URL or
        // path-segment objects: they also expose one-argument methods and were previously
        // mistaken for ResponseBody.
        val direct = fieldsOf(root.javaClass).firstNotNullOfOrNull { field ->
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@firstNotNullOfOrNull null
            val nested = runCatching {
                field.isAccessible = true
                field.get(root)
            }.getOrNull()
            if (looksLikeBody(nested)) nested else null
        }
        if (direct != null) return direct

        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?, depth: Int): Any? {
            if (value == null || depth > 2 || !visited.add(value)) return null
            if (looksLikeBody(value)) return value
            fieldsOf(value.javaClass).forEach { field ->
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
                val found = visit(nested, depth + 1)
                if (found != null) return found
            }
            return null
        }
        return visit(root, 0)
    }

    private fun PackageScope.looksLikeBody(value: Any?): Boolean {
        if (value == null) return false
        val methods = methodsOf(value.javaClass)
        val hasWrite = methods.any { method ->
            method.parameterTypes.size == 1 && method.returnType == Void.TYPE
        }
        val hasLength = methods.any { method ->
            method.parameterTypes.isEmpty() && method.returnType == Long::class.javaPrimitiveType
        }
        return hasWrite && hasLength
    }

    private fun PackageScope.extractBytes(root: Any?): String? {
        if (root == null) return null
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(value: Any?, depth: Int): String? {
            if (value == null || depth > 4 || !visited.add(value)) return null
            if (value is ByteArray) {
                return String(value, StandardCharsets.UTF_8)
            }
            if (value.javaClass.name == "okio.ByteString") {
                val toByteArray = methodsOf(value.javaClass).firstOrNull { method ->
                    method.parameterTypes.isEmpty() && method.returnType == ByteArray::class.java
                }
                val bytes = runCatching {
                    toByteArray?.let {
                        it.isAccessible = true
                        it.invoke(value) as? ByteArray
                    }
                }.getOrNull()
                if (bytes != null) return String(bytes, StandardCharsets.UTF_8)
            }
            fieldsOf(value.javaClass).forEach { field ->
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
                val found = visit(nested, depth + 1)
                if (found != null) return found
            }
            return null
        }

        return visit(root, 0)
    }

    private fun PackageScope.findBackScreenRequest(root: Any?): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(value: Any?, depth: Int): Any? {
            if (value == null || depth > 4 || !visited.add(value)) return null
            val text = runCatching { value.toString() }.getOrDefault("")
            if (isRequestText(text)) return value
            if (value is String || value is Number || value is Boolean || value is Enum<*>) return null
            fieldsOf(value.javaClass).forEach { field ->
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
                val found = visit(nested, depth + 1)
                if (found != null) return found
            }
            return null
        }

        return visit(root, 0)
    }

    private fun PackageScope.isBackScreenText(text: String): Boolean =
        text.contains(BACK_SCREEN_PAGE) ||
                text.contains(BACK_SCREEN_DETAIL) ||
                text.contains(BACK_SCREEN_UPDATE)

    private fun PackageScope.isRequestText(text: String): Boolean =
        (text.contains("Request{method=") || text.contains("Response{")) &&
                isBackScreenText(text)

    private fun PackageScope.isBackScreenRequest(value: Any): Boolean =
        isRequestText(runCatching { value.toString() }.getOrDefault(""))

    private fun PackageScope.debug(label: String, value: String) {
        val text = value.ifEmpty { "<empty>" }
        text.chunked(2000).forEachIndexed { index, chunk ->
            YLog.debug("[$TAG] $label[${index + 1}/${(text.length + 1999) / 2000}] $chunk")
        }
    }

    private fun PackageScope.containsBackScreenUrl(root: Any?): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(value: Any?, depth: Int): Boolean {
            if (value == null || depth > 3 || !visited.add(value)) return false
            val text = runCatching { value.toString() }.getOrDefault("")
            if (text.contains(BACK_SCREEN_PAGE) ||
                text.contains(BACK_SCREEN_DETAIL) ||
                text.contains(BACK_SCREEN_UPDATE)
            ) return true
            if (value is String || value is Number || value is Boolean || value is Enum<*>) return false

            fieldsOf(value.javaClass).forEach { field ->
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                runCatching {
                    field.isAccessible = true
                    if (visit(field.get(value), depth + 1)) return true
                }
            }
            return false
        }

        return visit(root, 0)
    }
}
