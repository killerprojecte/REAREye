package hk.uwu.reareye.repository.rearstore

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import hk.uwu.reareye.BuildConfig
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperMetadataOptions
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperOperationResult
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperRepository
import hk.uwu.reareye.repository.rearwidget.RearAppCardRepository
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearCardConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.repository.rearwidget.RearWidgetSceneRouteConfig
import hk.uwu.reareye.script.ScriptProjectStore
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigNode
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.REAREyeConfig
import hk.uwu.reareye.ui.config.resolveRearStoreApiBaseUrl
import hk.uwu.reareye.widgetapi.RearWidgetSceneRouteSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private const val DEFAULT_COMPONENT_ROUTE_PACKAGE = "com.xiaomi.subscreencenter"
private const val MIUI_GET_INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
private const val WIDGET_VERSION_CHECK_DISABLED = -1L

@Keep
data class RearStoreWidgetRequirements(
    @SerializedName("packages")
    val packages: List<String> = emptyList(),
    @SerializedName("configs")
    val configs: Map<String, Any> = emptyMap(),
)

@Keep
data class RearStorePostInstall(
    @SerializedName("uri")
    val uri: String = "",
)

data class RearStoreWidgetRequirementsResult(
    val satisfied: Boolean,
    val appListPermissionGranted: Boolean = true,
    val missingPackages: List<String> = emptyList(),
    val failedConfigKeys: List<String> = emptyList(),
    val failedConfigRequirements: Map<String, String> = emptyMap(),
    val failedConfigReasons: Map<String, String> = emptyMap(),
)

private data class RearStoreConfigNodeIndex(
    val titleResByKey: Map<String, Int>,
    val itemTypeByKey: Map<String, ConfigType>,
)

private enum class RearStoreRequirementOperator(val token: String) {
    GTE(">="),
    LTE("<="),
    EQ("=="),
    NE("!="),
    GT(">"),
    LT("<");

    companion object {
        private val ordered = entries.sortedByDescending { it.token.length }

        fun parse(expression: String): Pair<RearStoreRequirementOperator, String> {
            val normalized = expression.trim()
            ordered.forEach { operator ->
                if (normalized.startsWith(operator.token)) {
                    return operator to normalized.removePrefix(operator.token).trim()
                }
            }
            return EQ to normalized
        }
    }
}

private fun String?.normalizedOrNull(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

enum class RearStoreWidgetInfoType(val rawValue: String) {
    WIDGET("widget"),
    WALLPAPER("wallpaper");

    val supportedInCurrentVersion: Boolean
        get() = true

    companion object {
        fun fromRaw(raw: String?): RearStoreWidgetInfoType {
            return when (raw?.trim()?.lowercase(Locale.ROOT)) {
                WALLPAPER.rawValue -> WALLPAPER
                else -> WIDGET
            }
        }
    }
}

enum class RearStoreWidgetMetadataType(val rawValue: String) {
    CARD("card"),
    NOTIFICATION("notification"),
    ENHANCED("enhanced"),
    WALLPAPER("wallpaper"),
    UNKNOWN("unknown");

    companion object {
        fun fromRaw(raw: String?): RearStoreWidgetMetadataType {
            return when (raw?.trim()?.lowercase(Locale.ROOT)) {
                CARD.rawValue -> CARD
                NOTIFICATION.rawValue -> NOTIFICATION
                ENHANCED.rawValue -> ENHANCED
                WALLPAPER.rawValue -> WALLPAPER
                else -> UNKNOWN
            }
        }
    }
}

fun RearStoreWidgetInfo?.resolvedType(): RearStoreWidgetInfoType {
    return RearStoreWidgetInfoType.fromRaw(this?.type)
}

fun RearStoreWidgetInfo?.supportsModuleVersion(
    versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
): Boolean {
    val minVersion = this?.minVersion ?: WIDGET_VERSION_CHECK_DISABLED
    val maxVersion = this?.maxVersion ?: WIDGET_VERSION_CHECK_DISABLED
    return (minVersion == WIDGET_VERSION_CHECK_DISABLED || versionCode >= minVersion) &&
            (maxVersion == WIDGET_VERSION_CHECK_DISABLED || versionCode <= maxVersion)
}

fun RearStoreWidgetInfo?.evaluateRequirements(
    context: Context,
    prefsManager: PrefsManager,
): RearStoreWidgetRequirementsResult {
    val requirements = this?.requirements
    val requiredPackages = requirements.normalizedPackages()
    val requiredConfigs = requirements.normalizedConfigExpressions()
    val configNodeIndex = buildConfigNodeIndex()
    if (requiredPackages.isEmpty() && requiredConfigs.isEmpty()) {
        return RearStoreWidgetRequirementsResult(satisfied = true)
    }

    val installedPackages = if (requiredPackages.isEmpty()) {
        emptySet()
    } else {
        loadInstalledPackagesOrNull(context)
    }
    val missingPackages = when {
        requiredPackages.isEmpty() -> emptyList()
        installedPackages == null -> requiredPackages
        else -> requiredPackages.filterNot(installedPackages::contains)
    }
    val failedConfigReasons = linkedMapOf<String, String>()
    requiredConfigs.forEach { (key, expression) ->
        if (!configNodeIndex.titleResByKey.containsKey(key)) {
            failedConfigReasons[key] = context.getString(
                R.string.rear_store_requirement_config_unknown_item,
                key,
            )
            return@forEach
        }
        val actualValue = prefsManager.getRequirementValue(key)
        val result = evaluateConfigRequirement(
            context = context,
            key = key,
            actualValue = actualValue,
            expression = expression,
            configNodeIndex = configNodeIndex,
        )
        if (!result.satisfied) {
            failedConfigReasons[key] = result.reason
        }
    }
    val failedConfigKeys = failedConfigReasons.keys.toList()

    return RearStoreWidgetRequirementsResult(
        satisfied = missingPackages.isEmpty() && failedConfigKeys.isEmpty(),
        appListPermissionGranted = requiredPackages.isEmpty() || installedPackages != null,
        missingPackages = missingPackages,
        failedConfigKeys = failedConfigKeys,
        failedConfigRequirements = requiredConfigs.filterKeys(failedConfigKeys::contains),
        failedConfigReasons = failedConfigReasons,
    )
}

fun RearStoreWidgetMetadata?.resolvedType(): RearStoreWidgetMetadataType {
    return RearStoreWidgetMetadataType.fromRaw(this?.type)
}

fun RearStoreWidgetMetadata?.shouldInstallAsApp(): Boolean {
    return this?.installAsApp == true && resolvedType() == RearStoreWidgetMetadataType.CARD
}

private fun RearStoreWidgetRequirements?.normalizedPackages(): List<String> {
    return this?.packages
        ?.mapNotNull(String::normalizedOrNull)
        ?.distinct()
        .orEmpty()
}

private fun RearStoreWidgetRequirements?.normalizedConfigExpressions(): Map<String, String> {
    return this?.configs
        ?.mapNotNull { (key, value) ->
            val normalizedKey = key.normalizedOrNull() ?: return@mapNotNull null
            val expression = value.toRequirementExpression() ?: return@mapNotNull null
            normalizedKey to expression
        }
        ?.toMap(linkedMapOf())
        .orEmpty()
}

private fun Any?.toRequirementExpression(): String? {
    return when (this) {
        null -> null
        is String -> normalizedOrNull()
        is Boolean, is Number -> "== $this"
        is Collection<*> -> null
        else -> toString().normalizedOrNull()
    }
}

private fun loadInstalledPackagesOrNull(context: Context): Set<String>? {
    val canReadAppList = ContextCompat.checkSelfPermission(
        context,
        MIUI_GET_INSTALLED_APPS_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!canReadAppList) return null
    return runCatching {
        context.packageManager
            .getInstalledApplications(PackageManager.GET_META_DATA)
            .mapNotNullTo(linkedSetOf()) { it.packageName.normalizedOrNull() }
    }.getOrNull()
}

private data class RearStoreRequirementCheckResult(
    val satisfied: Boolean,
    val reason: String,
)

private fun evaluateConfigRequirement(
    context: Context,
    key: String,
    actualValue: Any?,
    expression: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): RearStoreRequirementCheckResult {
    if (actualValue == null) {
        return RearStoreRequirementCheckResult(
            satisfied = false,
            reason = buildConfigMissingReason(context, key, configNodeIndex),
        )
    }
    val (operator, operand) = RearStoreRequirementOperator.parse(expression)
    return when (actualValue) {
        is Boolean -> evaluateBooleanRequirement(
            context,
            key,
            actualValue,
            operator,
            operand,
            configNodeIndex
        )

        is Int -> evaluateNumericRequirement(
            context,
            key,
            actualValue.toDouble(),
            operator,
            operand,
            configNodeIndex
        )

        is Long -> evaluateNumericRequirement(
            context,
            key,
            actualValue.toDouble(),
            operator,
            operand,
            configNodeIndex
        )

        is Float -> evaluateNumericRequirement(
            context,
            key,
            actualValue.toDouble(),
            operator,
            operand,
            configNodeIndex
        )

        is Double -> evaluateNumericRequirement(
            context,
            key,
            actualValue,
            operator,
            operand,
            configNodeIndex
        )

        is Set<*> -> evaluateCollectionRequirement(
            context,
            key,
            actualValue,
            operator,
            operand,
            configNodeIndex
        )

        is Collection<*> -> evaluateCollectionRequirement(
            context,
            key,
            actualValue,
            operator,
            operand,
            configNodeIndex
        )

        else -> evaluateStringRequirement(
            context,
            key,
            actualValue.toString(),
            operator,
            operand,
            configNodeIndex
        )
    }
}

private fun evaluateBooleanRequirement(
    context: Context,
    key: String,
    actual: Boolean,
    operator: RearStoreRequirementOperator,
    operand: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): RearStoreRequirementCheckResult {
    val expected = operand.toBooleanStrictOrNull()
    val satisfied = when (operator) {
        RearStoreRequirementOperator.EQ -> expected != null && actual == expected
        RearStoreRequirementOperator.NE -> expected != null && actual != expected
        else -> false
    }
    if (satisfied) return RearStoreRequirementCheckResult(true, "")

    val reason = when {
        expected == true && operator == RearStoreRequirementOperator.EQ -> {
            buildConfigNeedEnabledReason(context, key, configNodeIndex)
        }

        expected == false && operator == RearStoreRequirementOperator.EQ -> {
            buildConfigNeedDisabledReason(context, key, configNodeIndex)
        }

        expected == true && operator == RearStoreRequirementOperator.NE -> {
            buildConfigNotEnabledReason(context, key, configNodeIndex)
        }

        expected == false && operator == RearStoreRequirementOperator.NE -> {
            buildConfigNotDisabledReason(context, key, configNodeIndex)
        }

        else -> buildConfigNeedEnabledReason(context, key, configNodeIndex)
    }
    return RearStoreRequirementCheckResult(false, reason)
}

private fun buildConfigNodeIndex(): RearStoreConfigNodeIndex {
    val titleResByKey = linkedMapOf<String, Int>()
    val itemTypeByKey = linkedMapOf<String, ConfigType>()

    fun walk(nodes: List<ConfigNode>) {
        nodes.forEach { node ->
            when (node) {
                is ConfigItem -> {
                    titleResByKey[node.key] = node.titleRes
                    itemTypeByKey[node.key] = node.type
                }

                is ConfigCategory -> walk(node.children)
                is ConfigGroup -> walk(node.children)
            }
        }
    }

    walk(REAREyeConfig)
    return RearStoreConfigNodeIndex(titleResByKey = titleResByKey, itemTypeByKey = itemTypeByKey)
}

private fun buildConfigMissingReason(
    context: Context,
    key: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): String {
    val titleRes = configNodeIndex.titleResByKey[key]
    return if (titleRes != null) {
        context.getString(
            R.string.rear_store_requirement_config_missing_named_item,
            quotedConfigTitle(context.getString(titleRes)),
        )
    } else {
        context.getString(R.string.rear_store_requirement_config_unknown_item, key)
    }
}

private fun quotedConfigTitle(title: String): String {
    return "\"$title\""
}

private fun buildConfigNeedEnabledReason(
    context: Context,
    key: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): String {
    val title =
        configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
            .orEmpty()
    return context.getString(R.string.rear_store_requirement_config_need_enabled_named_item, title)
}

private fun buildConfigNeedDisabledReason(
    context: Context,
    key: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): String {
    val title =
        configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
            .orEmpty()
    return context.getString(R.string.rear_store_requirement_config_need_disabled_named_item, title)
}

private fun buildConfigNotEnabledReason(
    context: Context,
    key: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): String {
    val title =
        configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
            .orEmpty()
    return context.getString(R.string.rear_store_requirement_config_not_enabled_named_item, title)
}

private fun buildConfigNotDisabledReason(
    context: Context,
    key: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): String {
    val title =
        configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
            .orEmpty()
    return context.getString(R.string.rear_store_requirement_config_not_disabled_named_item, title)
}

private fun evaluateNumericRequirement(
    context: Context,
    key: String,
    actual: Double,
    operator: RearStoreRequirementOperator,
    operand: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): RearStoreRequirementCheckResult {
    val expected = operand.toDoubleOrNull()
    val satisfied = when (operator) {
        RearStoreRequirementOperator.GTE -> expected != null && actual >= expected
        RearStoreRequirementOperator.LTE -> expected != null && actual <= expected
        RearStoreRequirementOperator.EQ -> expected != null && actual == expected
        RearStoreRequirementOperator.NE -> expected != null && actual != expected
        RearStoreRequirementOperator.GT -> expected != null && actual > expected
        RearStoreRequirementOperator.LT -> expected != null && actual < expected
    }
    if (satisfied) return RearStoreRequirementCheckResult(true, "")

    val reason = when (operator) {
        RearStoreRequirementOperator.GTE -> context.getString(
            R.string.rear_store_requirement_config_number_gte_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )

        RearStoreRequirementOperator.LTE -> context.getString(
            R.string.rear_store_requirement_config_number_lte_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )

        RearStoreRequirementOperator.EQ -> context.getString(
            R.string.rear_store_requirement_config_number_eq_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )

        RearStoreRequirementOperator.NE -> context.getString(
            R.string.rear_store_requirement_config_number_ne_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )

        RearStoreRequirementOperator.GT -> context.getString(
            R.string.rear_store_requirement_config_number_gt_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )

        RearStoreRequirementOperator.LT -> context.getString(
            R.string.rear_store_requirement_config_number_lt_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )
    }
    return RearStoreRequirementCheckResult(false, reason)
}

private fun evaluateCollectionRequirement(
    context: Context,
    key: String,
    actual: Collection<*>,
    operator: RearStoreRequirementOperator,
    operand: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): RearStoreRequirementCheckResult {
    val normalizedValues = actual.mapNotNull { it?.toString().normalizedOrNull() }.toSet()
    val target = operand.normalizedOrNull() ?: return RearStoreRequirementCheckResult(
        satisfied = false,
        reason = buildConfigNeedEnabledReason(context, key, configNodeIndex),
    )
    val satisfied = when (operator) {
        RearStoreRequirementOperator.EQ -> target in normalizedValues
        RearStoreRequirementOperator.NE -> target !in normalizedValues
        else -> false
    }
    if (satisfied) return RearStoreRequirementCheckResult(true, "")

    val reason = when (operator) {
        RearStoreRequirementOperator.EQ -> context.getString(
            R.string.rear_store_requirement_config_contains_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            target,
        )

        RearStoreRequirementOperator.NE -> context.getString(
            R.string.rear_store_requirement_config_not_contains_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            target,
        )

        else -> context.getString(
            R.string.rear_store_requirement_config_contains_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            target,
        )
    }
    return RearStoreRequirementCheckResult(false, reason)
}

private fun evaluateStringRequirement(
    context: Context,
    key: String,
    actual: String,
    operator: RearStoreRequirementOperator,
    operand: String,
    configNodeIndex: RearStoreConfigNodeIndex,
): RearStoreRequirementCheckResult {
    val normalizedActual = actual.trim()
    val satisfied = when (operator) {
        RearStoreRequirementOperator.EQ -> normalizedActual == operand
        RearStoreRequirementOperator.NE -> normalizedActual != operand
        else -> false
    }
    if (satisfied) return RearStoreRequirementCheckResult(true, "")

    val reason = when (operator) {
        RearStoreRequirementOperator.EQ -> context.getString(
            R.string.rear_store_requirement_config_string_eq_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )

        RearStoreRequirementOperator.NE -> context.getString(
            R.string.rear_store_requirement_config_string_ne_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )

        else -> context.getString(
            R.string.rear_store_requirement_config_string_eq_item,
            configNodeIndex.titleResByKey[key]?.let(context::getString)?.let(::quotedConfigTitle)
                .orEmpty(),
            operand,
        )
    }
    return RearStoreRequirementCheckResult(false, reason)
}

private fun parseMetadataType(rawType: String?): RearStoreWidgetMetadataType {
    val raw = rawType?.trim().orEmpty()
    if (raw.contains("壁纸")) return RearStoreWidgetMetadataType.WALLPAPER
    if (raw.contains("通知")) return RearStoreWidgetMetadataType.NOTIFICATION
    if (raw.contains("增强")) return RearStoreWidgetMetadataType.ENHANCED
    if (raw.contains("卡片") || raw.contains("组件")) return RearStoreWidgetMetadataType.CARD

    val normalized = rawType
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace('-', '_')
        ?.replace(' ', '_')
        .orEmpty()
    if (normalized.isBlank()) return RearStoreWidgetMetadataType.UNKNOWN
    return when {
        normalized.contains("wallpaper") -> RearStoreWidgetMetadataType.WALLPAPER
        normalized.contains("notification") || normalized.contains("notify") -> {
            RearStoreWidgetMetadataType.NOTIFICATION
        }

        normalized.contains("enhanced") || normalized.contains("enhance") -> {
            RearStoreWidgetMetadataType.ENHANCED
        }

        normalized.contains("card") || normalized.contains("widget") -> {
            RearStoreWidgetMetadataType.CARD
        }

        else -> RearStoreWidgetMetadataType.UNKNOWN
    }
}

private fun RearStoreWidgetDetail.resolvedMetadataTypeForInstall(): RearStoreWidgetMetadataType {
    parseMetadataType(type).takeIf {
        it != RearStoreWidgetMetadataType.UNKNOWN
    }?.let { return it }

    parseMetadataType(metadata?.type).takeIf {
        it != RearStoreWidgetMetadataType.UNKNOWN
    }?.let { return it }

    metadata.resolvedType().takeIf {
        it != RearStoreWidgetMetadataType.UNKNOWN
    }?.let { return it }

    parseMetadataType(widgetInfo?.type).takeIf {
        it != RearStoreWidgetMetadataType.UNKNOWN
    }?.let { return it }

    return when (widgetInfo.resolvedType()) {
        RearStoreWidgetInfoType.WALLPAPER -> RearStoreWidgetMetadataType.WALLPAPER
        RearStoreWidgetInfoType.WIDGET -> RearStoreWidgetMetadataType.CARD
    }
}

private fun RearStoreWidgetDetail.notificationSceneSetupOrNull(): RearStoreSceneSetup? {
    if (resolvedMetadataTypeForInstall() != RearStoreWidgetMetadataType.NOTIFICATION) return null
    val sceneSetup = widgetInfo?.sceneSetup ?: return null
    val scene = sceneSetup.scene.normalizedOrNull() ?: return null
    val packageName = sceneSetup.packageName.normalizedOrNull() ?: return null
    return sceneSetup.copy(scene = scene, packageName = packageName)
}

@Keep
data class RearStoreAuthor(
    @SerializedName("login")
    val login: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("url")
    val url: String = "",
    @SerializedName("avatarUrl")
    val avatarUrl: String = "",
    @SerializedName("type")
    val type: String = "",
) {
    val displayName: String
        get() = name.normalizedOrNull() ?: login.normalizedOrNull().orEmpty()
}

data class RearStoreRepositoryLink(
    @SerializedName("fullName")
    val fullName: String = "",
    @SerializedName("url")
    val url: String = "",
)

@Keep
data class RearStoreRepositoryInfo(
    @SerializedName("widgetName")
    val widgetName: String = "",
    @SerializedName("owner")
    val owner: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("fullName")
    val fullName: String = "",
    @SerializedName("url")
    val url: String = "",
    @SerializedName("description")
    val description: String = "",
    @SerializedName("homepage")
    val homepage: String = "",
    @SerializedName("updatedAt")
    val updatedAt: String = "",
    @SerializedName("pushedAt")
    val pushedAt: String = "",
    @SerializedName("stargazersCount")
    val stargazersCount: Int = 0,
)

@Keep
data class RearStoreListItem(
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("description")
    val description: String = "",
    @SerializedName("author")
    val author: RearStoreAuthor = RearStoreAuthor(),
    @SerializedName("updatedAt")
    val updatedAt: String = "",
    @SerializedName("repository")
    val repository: RearStoreRepositoryLink? = null,
    val stargazersCount: Int = 0,
    val latestReleaseTag: String? = null,
    val latestReleasePublishedAt: String? = null,
    val metadata: RearStoreWidgetMetadata? = null,
) {
    val displayName: String
        get() = name.normalizedOrNull() ?: id.normalizedOrNull().orEmpty()
}

private data class RearStoreWidgetCatalogItemResponse(
    @SerializedName(value = "type", alternate = ["widgetType", "widget_type"])
    val type: String? = null,
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("authorName")
    val authorName: String = "",
    @SerializedName("authorId")
    val authorId: String = "",
    @SerializedName("description")
    val description: String = "",
    @SerializedName("latestReleaseTag")
    val latestReleaseTag: String = "",
    @SerializedName("latestReleasePublishedAt")
    val latestReleasePublishedAt: String = "",
    @SerializedName("stars")
    val stars: Int = 0,
    @SerializedName("metadata")
    val metadata: RearStoreWidgetMetadata? = null,
)

private data class RearStoreDescriptionResponse(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("repository")
    val repository: RearStoreRepositoryInfo? = null,
    @SerializedName("metadata")
    val metadata: RearStoreWidgetMetadata? = null,
)

private data class RearStoreAuthorResponse(
    @SerializedName("author")
    val author: RearStoreAuthor = RearStoreAuthor(),
)

private data class RearStoreWidgetInfoResponse(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("type")
    val type: String? = null,
    @SerializedName(value = "widgetInfo", alternate = ["widget_info"])
    val widgetInfo: RearStoreWidgetInfo? = null,
)

@Keep
data class RearStoreWidgetInfo(
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("name")
    val name: String = "",
    @SerializedName(value = "business_setup", alternate = ["businessSetup"])
    val businessSetup: RearStoreBusinessSetup? = null,
    @SerializedName(value = "card_setup", alternate = ["cardSetup"])
    val cardSetup: RearStoreCardSetup? = null,
    @SerializedName(value = "scene_setup", alternate = ["sceneSetup"])
    val sceneSetup: RearStoreSceneSetup? = null,
    @SerializedName(value = "minVersion", alternate = ["min_version"])
    val minVersion: Long = WIDGET_VERSION_CHECK_DISABLED,
    @SerializedName(value = "maxVersion", alternate = ["max_version"])
    val maxVersion: Long = WIDGET_VERSION_CHECK_DISABLED,
    @SerializedName("requirements")
    val requirements: RearStoreWidgetRequirements? = null,
    @SerializedName("postinstall")
    val postInstall: RearStorePostInstall? = null,
)

@Keep
data class RearStoreWidgetMetadata(
    @SerializedName("type")
    val type: String = "",
    @SerializedName(value = "install_as_app", alternate = ["installAsApp"])
    val installAsApp: Boolean = false,
)

@Keep
data class RearStoreBusinessSetup(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("renameable")
    val renameable: Boolean = true,
)

@Keep
data class RearStoreCardSetup(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("package")
    val packageName: String = "",
    @SerializedName("priority")
    val priority: Int = 500,
    @SerializedName("sticky")
    val sticky: Boolean = true,
    @SerializedName("renameable")
    val renameable: Boolean = true,
)

@Keep
data class RearStoreSceneSetup(
    @SerializedName("scene")
    val scene: String = "",
    @SerializedName(value = "pkg", alternate = ["package", "packageName"])
    val packageName: String = "",
)

private data class RearStoreReadmeResponse(
    @SerializedName("readme")
    val readme: RearStoreReadme? = null,
)

data class RearStoreReadme(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("htmlUrl")
    val htmlUrl: String = "",
    @SerializedName("content")
    val content: String = "",
)

private data class RearStoreReleasesResponse(
    @SerializedName("releases")
    val releases: List<RearStoreRelease> = emptyList(),
)

@Keep
data class RearStoreRelease(
    @SerializedName("tagName")
    val tagName: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("body")
    val body: String = "",
    @SerializedName("createdAt")
    val createdAt: String = "",
    @SerializedName("publishedAt")
    val publishedAt: String = "",
    @SerializedName("url")
    val url: String = "",
    @SerializedName(value = "widgetInfo", alternate = ["widget_info"])
    val widgetInfo: RearStoreWidgetInfo? = null,
    @SerializedName("assets")
    val assets: List<RearStoreReleaseAsset> = emptyList(),
)

@Keep
data class RearStoreReleaseAsset(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("contentType")
    val contentType: String = "",
    @SerializedName("size")
    val size: Long = 0L,
    @SerializedName("downloadCount")
    val downloadCount: Long = 0L,
    @SerializedName("downloadUrl")
    val downloadUrl: String = "",
)

@Keep
data class RearStoreWidgetDetail(
    @SerializedName("type")
    val type: String? = null,
    val widgetId: String,
    val name: String,
    val description: String,
    val author: RearStoreAuthor,
    val repository: RearStoreRepositoryInfo?,
    @SerializedName(value = "widgetInfo", alternate = ["widget_info"])
    val widgetInfo: RearStoreWidgetInfo?,
    val metadata: RearStoreWidgetMetadata?,
    val readme: RearStoreReadme?,
    val releases: List<RearStoreRelease>,
)

@Keep
data class RearStoreInstalledWidget(
    val widgetId: String,
    val widgetName: String,
    val businessId: String,
    val releaseTag: String?,
    val releasePublishedAt: String?,
    val installedAt: String?,
    val renameable: Boolean,
)

enum class RearStoreInstallConflictSource {
    SAME_WIDGET,
    MANUAL_BUSINESS,
    OTHER_STORE_WIDGET,
}

data class RearStoreInstallConflict(
    val businessId: String,
    val businessName: String,
    val source: RearStoreInstallConflictSource,
    val existingWidgetId: String? = null,
    val existingWidgetName: String? = null,
)

data class RearStoreQuickInstallResult(
    val widgetId: String,
    val widgetName: String,
    val releaseTag: String?,
    val cardInstalled: Boolean,
    val fallbackUsed: Boolean,
    val updatedExistingInstall: Boolean,
    val businessConfigId: String? = null,
    val cardId: String? = null,
)

data class RearStoreInstalledArchiveSource(
    val storeWidgetId: String?,
    val businessConfigId: String,
    val businessName: String,
    val cardId: String?,
    val filePath: String,
)

data class RearStorePreparedInstallAsset(
    val release: RearStoreRelease,
    val asset: RearStoreReleaseAsset,
    val assetBytes: ByteArray,
    val embeddedMetadataBytes: ByteArray? = null,
    val wallpaperMetadataEditable: Boolean = true,
    val embeddedWallpaperMetadataOptions: RearWallpaperMetadataOptions? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RearStorePreparedInstallAsset

        if (release != other.release) return false
        if (asset != other.asset) return false
        if (!assetBytes.contentEquals(other.assetBytes)) return false
        if (!embeddedMetadataBytes.contentEquals(other.embeddedMetadataBytes)) return false
        if (wallpaperMetadataEditable != other.wallpaperMetadataEditable) return false
        if (embeddedWallpaperMetadataOptions != other.embeddedWallpaperMetadataOptions) return false

        return true
    }

    override fun hashCode(): Int {
        var result = release.hashCode()
        result = 31 * result + asset.hashCode()
        result = 31 * result + assetBytes.contentHashCode()
        result = 31 * result + (embeddedMetadataBytes?.contentHashCode() ?: 0)
        result = 31 * result + wallpaperMetadataEditable.hashCode()
        result = 31 * result + (embeddedWallpaperMetadataOptions?.hashCode() ?: 0)
        return result
    }
}

data class RearStoreUninstallResult(
    val widgetId: String,
    val widgetName: String,
    val removedBusinessCount: Int,
    val removedCardCount: Int,
    val removedSceneRouteCount: Int,
    val removedWallpaperCount: Int,
)

enum class RearStoreInstallProgressStage {
    CONNECTING,
    DOWNLOADING,
    INSTALLING,
}

data class RearStoreInstallProgress(
    val stage: RearStoreInstallProgressStage,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
)

private data class RearStoreSelectedAsset(
    val release: RearStoreRelease,
    val asset: RearStoreReleaseAsset,
)

data class RearStoreInstalledWallpaper(
    val widgetId: String,
    val widgetName: String,
    val wallpaperId: Int,
    val releaseTag: String?,
    val releasePublishedAt: String?,
    val installedAt: String?,
)

object RearStoreRepository {
    private val wallpaperInstallMutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    }
                )
            }
        }
        .build()
    private val gson = Gson()
    private val widgetsCache =
        java.util.concurrent.ConcurrentHashMap<String, List<RearStoreListItem>>()
    private val descriptionCache =
        java.util.concurrent.ConcurrentHashMap<String, RearStoreDescriptionResponse>()
    private val authorCache = java.util.concurrent.ConcurrentHashMap<String, RearStoreAuthor>()
    private val widgetInfoCache =
        java.util.concurrent.ConcurrentHashMap<String, RearStoreWidgetInfo>()
    private val widgetTypeCache =
        java.util.concurrent.ConcurrentHashMap<String, String>()
    private val releasesCache =
        java.util.concurrent.ConcurrentHashMap<String, List<RearStoreRelease>>()
    private val detailCache =
        java.util.concurrent.ConcurrentHashMap<String, RearStoreWidgetDetail>()
    private val readmeCache = java.util.concurrent.ConcurrentHashMap<String, RearStoreReadme>()

    suspend fun loadWidgets(prefsManager: PrefsManager): List<RearStoreListItem> {
        return withContext(Dispatchers.IO) {
            val baseUrl = resolveRearStoreApiBaseUrl(prefsManager)
            widgetsCache[baseUrl]?.let { return@withContext it }
            val loaded = fetchJson<Array<RearStoreWidgetCatalogItemResponse>>(baseUrl, "/widgets")
                ?.map { item ->
                    val authorId = item.authorId.normalizedOrNull().orEmpty()
                    val authorName = item.authorName.normalizedOrNull().orEmpty()
                    RearStoreListItem(
                        type = item.type.normalizedOrNull(),
                        id = item.id,
                        name = item.name,
                        description = item.description,
                        author = RearStoreAuthor(
                            login = authorId,
                            name = authorName.ifBlank { authorId },
                        ),
                        updatedAt = item.latestReleasePublishedAt,
                        stargazersCount = item.stars,
                        latestReleaseTag = item.latestReleaseTag.normalizedOrNull(),
                        latestReleasePublishedAt = item.latestReleasePublishedAt.normalizedOrNull(),
                        metadata = item.metadata,
                    )
                }
            val enriched = loaded?.let { widgets ->
                coroutineScope {
                    widgets.map { item ->
                        async {
                            val normalizedType = item.type.normalizedOrNull()
                                ?: item.metadata?.type.normalizedOrNull()
                                ?: item.id.normalizedOrNull()?.let {
                                    loadWidgetTypeFromInfoCached(baseUrl, it)
                                }
                            if (normalizedType != null && normalizedType != item.type) {
                                item.copy(type = normalizedType)
                            } else {
                                item
                            }
                        }
                    }.awaitAll()
                }
            }
            if (enriched != null) widgetsCache[baseUrl] = enriched
            enriched ?: widgetsCache[baseUrl].orEmpty()
        }
    }

    suspend fun loadWidgetDetail(
        prefsManager: PrefsManager,
        widgetId: String,
        widgetInfoVersion: String? = null,
    ): RearStoreWidgetDetail? {
        val normalizedWidgetId = widgetId.normalizedOrNull() ?: return null
        val normalizedWidgetInfoVersion = widgetInfoVersion.normalizedOrNull()
        val baseUrl = resolveRearStoreApiBaseUrl(prefsManager)
        val detailCacheKey = cacheKey(baseUrl, normalizedWidgetId, normalizedWidgetInfoVersion)
        detailCache[detailCacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val descriptionDeferred =
                    async { loadDescriptionCached(baseUrl, normalizedWidgetId) }
                val authorDeferred = async { loadAuthorCached(baseUrl, normalizedWidgetId) }
                val releasesDeferred = async { loadReleasesCached(baseUrl, normalizedWidgetId) }

                val description = descriptionDeferred.await()
                    ?: detailCache[detailCacheKey]?.let { cached ->
                        return@coroutineScope cached.copy(
                            readme = readmeCache[detailCacheKey] ?: cached.readme
                        )
                    }
                    ?: return@coroutineScope null
                val repository = description.repository
                val releases = releasesDeferred.await().orEmpty()
                val widgetInfo = loadWidgetInfoForVersionCached(
                    baseUrl = baseUrl,
                    widgetId = normalizedWidgetId,
                    version = normalizedWidgetInfoVersion,
                    releases = releases,
                )
                val metadata = description.metadata
                val resolvedAuthor = resolveAuthor(authorDeferred.await(), repository)
                val detail = RearStoreWidgetDetail(
                    type = widgetInfo?.type.normalizedOrNull() ?: metadata?.type.normalizedOrNull(),
                    widgetId = normalizedWidgetId,
                    name = description.name.normalizedOrNull()
                        ?: widgetInfo?.name.normalizedOrNull()
                        ?: normalizedWidgetId,
                    description = repository?.description.normalizedOrNull().orEmpty(),
                    author = resolvedAuthor,
                    repository = repository,
                    widgetInfo = widgetInfo,
                    metadata = metadata,
                    readme = readmeCache[detailCacheKey],
                    releases = releases,
                )
                detailCache[detailCacheKey] = detail
                detail
            }
        }
    }

    suspend fun loadWidgetDetailForRelease(
        prefsManager: PrefsManager,
        detail: RearStoreWidgetDetail,
        releaseTag: String?,
    ): RearStoreWidgetDetail = withContext(Dispatchers.IO) {
        val normalizedWidgetId = detail.widgetId.normalizedOrNull() ?: return@withContext detail
        val baseUrl = resolveRearStoreApiBaseUrl(prefsManager)
        val widgetInfo = loadWidgetInfoForVersionCached(
            baseUrl = baseUrl,
            widgetId = normalizedWidgetId,
            version = releaseTag,
            releases = detail.releases,
        )
        detail.copy(widgetInfo = widgetInfo ?: detail.widgetInfo)
    }

    suspend fun loadWidgetReadme(prefsManager: PrefsManager, widgetId: String): RearStoreReadme? {
        val normalizedWidgetId = widgetId.normalizedOrNull() ?: return null
        val baseUrl = resolveRearStoreApiBaseUrl(prefsManager)
        val readmeCacheKey = cacheKey(baseUrl, normalizedWidgetId)
        readmeCache[readmeCacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            val readme = fetchJson<RearStoreReadmeResponse>(
                baseUrl,
                "/widget/${Uri.encode(normalizedWidgetId)}/readme"
            )?.readme ?: readmeCache[readmeCacheKey]
            if (readme != null) {
                readmeCache[readmeCacheKey] = readme
                detailCache[readmeCacheKey] = detailCache[readmeCacheKey]
                    ?.copy(readme = readme)
                    ?: loadWidgetDetail(prefsManager, normalizedWidgetId)?.copy(readme = readme)
                            ?: return@withContext readme
            }
            readme
        }
    }

    fun loadInstalledWidgetSummaries(prefsManager: PrefsManager): Map<String, RearStoreInstalledWidget> {
        return LinkedHashMap<String, RearStoreInstalledWidget>().apply {
            RearWidgetManagerRepository.loadBusinesses(prefsManager)
                .asSequence()
                .filter { it.downloadedFromStore }
                .forEach { business ->
                    val widgetId = business.storeWidgetId.normalizedOrNull() ?: return@forEach
                    put(
                        widgetId,
                        RearStoreInstalledWidget(
                            widgetId = widgetId,
                            widgetName = business.storeWidgetName.normalizedOrNull()
                                ?: business.business.normalizedOrNull()
                                ?: widgetId,
                            businessId = business.business,
                            releaseTag = business.storeReleaseTag.normalizedOrNull(),
                            releasePublishedAt = business.storeReleasePublishedAt.normalizedOrNull(),
                            installedAt = business.storeInstalledAt.normalizedOrNull()
                                ?: business.storeReleasePublishedAt.normalizedOrNull(),
                            renameable = business.renameable,
                        )
                    )
                }

            loadInstalledWallpaperRecords(prefsManager).forEach { wallpaper ->
                put(
                    wallpaper.widgetId,
                    RearStoreInstalledWidget(
                        widgetId = wallpaper.widgetId,
                        widgetName = wallpaper.widgetName,
                        businessId = wallpaper.wallpaperId.toString(),
                        releaseTag = wallpaper.releaseTag,
                        releasePublishedAt = wallpaper.releasePublishedAt,
                        installedAt = wallpaper.installedAt ?: wallpaper.releasePublishedAt,
                        renameable = false,
                    )
                )
            }
        }
    }

    fun resolveInstalledArchiveSourceByStoreWidgetId(
        prefsManager: PrefsManager,
        widgetId: String,
    ): RearStoreInstalledArchiveSource? {
        val normalizedWidgetId = widgetId.normalizedOrNull() ?: return null
        val businesses = RearWidgetManagerRepository.loadBusinesses(prefsManager)
        val cards = RearWidgetManagerRepository.loadCards(prefsManager)
        val business = businesses.firstOrNull {
            it.downloadedFromStore &&
                    it.storeWidgetId.normalizedOrNull() == normalizedWidgetId &&
                    it.filePath.normalizedOrNull() != null
        } ?: return null
        val cardId = cards.firstOrNull {
            it.downloadedFromStore && it.storeWidgetId.normalizedOrNull() == normalizedWidgetId
        }?.id
        return RearStoreInstalledArchiveSource(
            storeWidgetId = normalizedWidgetId,
            businessConfigId = business.id,
            businessName = business.business,
            cardId = cardId,
            filePath = business.filePath,
        )
    }

    fun resolveInstalledArchiveSourceByBusinessConfigId(
        prefsManager: PrefsManager,
        businessConfigId: String,
    ): RearStoreInstalledArchiveSource? {
        val normalizedBusinessConfigId = businessConfigId.normalizedOrNull() ?: return null
        val businesses = RearWidgetManagerRepository.loadBusinesses(prefsManager)
        val cards = RearWidgetManagerRepository.loadCards(prefsManager)
        val business = businesses.firstOrNull {
            it.id.normalizedOrNull() == normalizedBusinessConfigId && it.filePath.normalizedOrNull() != null
        } ?: return null
        val cardId = cards.firstOrNull {
            it.business == business.business &&
                    it.storeWidgetId.normalizedOrNull() == business.storeWidgetId.normalizedOrNull()
        }?.id
        return RearStoreInstalledArchiveSource(
            storeWidgetId = business.storeWidgetId.normalizedOrNull(),
            businessConfigId = business.id,
            businessName = business.business,
            cardId = cardId,
            filePath = business.filePath,
        )
    }

    fun readInstalledArchiveFileBase64(
        filePath: String,
        entryName: String? = null,
    ): String {
        val targetFile = File(filePath)
        require(targetFile.exists() && targetFile.isFile) { "Archive file not found" }
        val bytes = if (entryName.normalizedOrNull() == null) {
            targetFile.readBytes()
        } else {
            readZipEntryBytes(targetFile, entryName.normalizedOrNull()!!)
                ?: error("Entry '$entryName' not found in archive")
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun listInstalledArchiveEntries(filePath: String): List<String> {
        val targetFile = File(filePath)
        require(targetFile.exists() && targetFile.isFile) { "Archive file not found" }
        return runCatching {
            ZipFile(targetFile).use { zipFile ->
                buildList {
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        add(entry.name.replace('\\', '/').trimStart('/'))
                    }
                }.sorted()
            }
        }.getOrElse { emptyList() }
    }

    fun loadInstalledWallpaperSources(
        prefsManager: PrefsManager,
    ): Map<Int, RearStoreInstalledWallpaper> {
        return loadInstalledWallpaperRecords(prefsManager)
            .associateBy { it.wallpaperId }
    }

    fun removeInstalledWallpaperRecord(
        prefsManager: PrefsManager,
        wallpaperId: Int,
    ): Boolean {
        val records = loadInstalledWallpaperRecords(prefsManager)
        val nextRecords = records.filterNot { it.wallpaperId == wallpaperId }
        if (nextRecords.size == records.size) return false
        saveInstalledWallpaperRecords(prefsManager, nextRecords)
        return true
    }

    fun pruneInstalledWallpaperRecords(
        prefsManager: PrefsManager,
        installedWallpaperIds: Set<Int>,
    ): Boolean {
        val records = loadInstalledWallpaperRecords(prefsManager)
        val nextRecords = records.filter { it.wallpaperId in installedWallpaperIds }
        if (nextRecords.size == records.size) return false
        saveInstalledWallpaperRecords(prefsManager, nextRecords)
        return true
    }

    fun resolveInstallConflict(
        prefsManager: PrefsManager,
        detail: RearStoreWidgetDetail,
    ): RearStoreInstallConflict? {
        if (detail.widgetInfo.resolvedType() != RearStoreWidgetInfoType.WIDGET) return null

        val businessId = detail.widgetInfo?.businessSetup?.id.normalizedOrNull() ?: return null
        val existingBusiness = RearWidgetManagerRepository.loadBusinesses(prefsManager)
            .firstOrNull { it.business == businessId }
            ?: return null
        val existingWidgetId = existingBusiness.storeWidgetId.normalizedOrNull()
        if (existingWidgetId == detail.widgetId) return null
        val source = when {
            existingBusiness.downloadedFromStore && existingWidgetId != null -> {
                RearStoreInstallConflictSource.OTHER_STORE_WIDGET
            }

            else -> RearStoreInstallConflictSource.MANUAL_BUSINESS
        }
        return RearStoreInstallConflict(
            businessId = businessId,
            businessName = existingBusiness.business,
            source = source,
            existingWidgetId = existingWidgetId,
            existingWidgetName = existingBusiness.storeWidgetName.normalizedOrNull(),
        )
    }

    suspend fun uninstallWidget(
        context: Context,
        prefsManager: PrefsManager,
        widgetId: String,
    ): RearStoreUninstallResult = withContext(Dispatchers.IO) {
        val normalizedWidgetId = widgetId.normalizedOrNull() ?: error("Missing widget id")
        val businesses = RearWidgetManagerRepository.loadBusinesses(prefsManager)
        val cards = RearWidgetManagerRepository.loadCards(prefsManager)
        val sceneRoutes = RearWidgetManagerRepository.loadSceneRoutes(prefsManager)
        val wallpaperRecords = loadInstalledWallpaperRecords(prefsManager)

        val removedBusinesses =
            businesses.filter { it.storeWidgetId.normalizedOrNull() == normalizedWidgetId }
        val removedCards =
            cards.filter { it.storeWidgetId.normalizedOrNull() == normalizedWidgetId }
        val removedSceneRoutes = sceneRoutes.filter {
            it.storeWidgetId.normalizedOrNull() == normalizedWidgetId
        }
        val removedWallpaper = wallpaperRecords.firstOrNull { it.widgetId == normalizedWidgetId }

        if (removedBusinesses.isEmpty() &&
            removedCards.isEmpty() &&
            removedSceneRoutes.isEmpty() &&
            removedWallpaper == null
        ) {
            error("Widget is not installed")
        }

        removeInstalledAppCards(
            context = context,
            businessIds = removedBusinesses.map { it.business },
        )
        ScriptProjectStore(context).uninstallComponent(normalizedWidgetId)

        removedWallpaper?.let { wallpaper ->
            val result = RearWallpaperRepository.deleteWallpaper(context, wallpaper.wallpaperId)
            if (!result.success && !isMissingWallpaperError(result.error)) {
                error(result.error ?: "Failed to delete wallpaper")
            }
        }

        val nextCards =
            cards.filterNot { it.storeWidgetId.normalizedOrNull() == normalizedWidgetId }
        val nextSceneRoutes = sceneRoutes.filterNot {
            it.storeWidgetId.normalizedOrNull() == normalizedWidgetId
        }
        val nextBusinesses = businesses.filterNot {
            it.storeWidgetId.normalizedOrNull() == normalizedWidgetId
        }

        if (nextCards != cards) {
            RearWidgetManagerRepository.saveCards(
                context = context,
                prefsManager = prefsManager,
                cards = nextCards,
                allowLockedEdits = true,
            )
        }
        if (nextSceneRoutes != sceneRoutes) {
            RearWidgetManagerRepository.saveSceneRoutes(
                context = context,
                prefsManager = prefsManager,
                sceneRoutes = nextSceneRoutes,
            )
        }
        if (nextBusinesses != businesses) {
            RearWidgetManagerRepository.saveBusinesses(
                context = context,
                prefsManager = prefsManager,
                businesses = nextBusinesses,
                allowLockedEdits = true,
            )
        }
        if (removedWallpaper != null) {
            saveInstalledWallpaperRecords(
                prefsManager,
                wallpaperRecords.filterNot { it.widgetId == normalizedWidgetId },
            )
        }

        RearStoreUninstallResult(
            widgetId = normalizedWidgetId,
            widgetName = removedBusinesses.firstOrNull()?.storeWidgetName.normalizedOrNull()
                ?: removedWallpaper?.widgetName
                ?: removedCards.firstOrNull()?.storeWidgetName.normalizedOrNull()
                ?: normalizedWidgetId,
            removedBusinessCount = removedBusinesses.size,
            removedCardCount = removedCards.size,
            removedSceneRouteCount = removedSceneRoutes.size,
            removedWallpaperCount = if (removedWallpaper != null) 1 else 0,
        )
    }

    private fun removeInstalledAppCards(
        context: Context,
        businessIds: Collection<String>,
    ) {
        val normalizedBusinessIds = businessIds.mapNotNull(String::normalizedOrNull).toSet()
        if (normalizedBusinessIds.isEmpty()) return
        RearAppCardRepository.loadCatalog(context)
            .filter { card ->
                card.ownedByRearEye && card.componentBusiness in normalizedBusinessIds
            }
            .forEach { card ->
                val result = RearAppCardRepository.delete(context, card.appId)
                if (!result.success) {
                    error(result.error ?: "Failed to uninstall app card")
                }
            }
    }

    suspend fun prepareInstallAsset(
        prefsManager: PrefsManager,
        detail: RearStoreWidgetDetail,
        releaseTag: String? = null,
        assetName: String? = null,
        onProgress: (RearStoreInstallProgress) -> Unit = {},
    ): RearStorePreparedInstallAsset = withContext(Dispatchers.IO) {
        val baseUrl = resolveRearStoreApiBaseUrl(prefsManager)
        emitInstallProgress(
            onProgress,
            RearStoreInstallProgress(stage = RearStoreInstallProgressStage.CONNECTING),
        )
        val selectedAsset = selectAsset(
            releases = detail.releases,
            preferredReleaseTag = releaseTag,
            preferredAssetName = assetName,
        )
            ?: error("No downloadable release asset found")
        val installDetail = loadWidgetDetailForRelease(
            prefsManager = prefsManager,
            detail = detail,
            releaseTag = selectedAsset.release.tagName,
        )
        val assetBytes = downloadAssetBytes(
            baseUrl = baseUrl,
            widgetId = detail.widgetId,
            selectedAsset = selectedAsset,
            onProgress = onProgress,
        )
            ?: error("Failed to download widget asset")
        val isWallpaper =
            installDetail.widgetInfo.resolvedType() == RearStoreWidgetInfoType.WALLPAPER
        val embeddedMetadataBytes = if (isWallpaper) extractRootMetadataMrm(assetBytes) else null
        val defaultWallpaperOptions = if (isWallpaper) {
            installDetail.toWallpaperMetadataOptions(installDetail.defaultWallpaperName())
        } else {
            null
        }
        RearStorePreparedInstallAsset(
            release = selectedAsset.release,
            asset = selectedAsset.asset,
            assetBytes = assetBytes,
            embeddedMetadataBytes = embeddedMetadataBytes,
            wallpaperMetadataEditable = !isWallpaper || !containsNotEditableMarker(assetBytes),
            embeddedWallpaperMetadataOptions = defaultWallpaperOptions
                ?.let { fallback ->
                    val metadataBytes = embeddedMetadataBytes ?: return@let null
                    parseWallpaperMetadataOptions(
                        bytes = metadataBytes,
                        fallback = fallback,
                    )
                },
        )
    }

    suspend fun quickInstallWidget(
        context: Context,
        prefsManager: PrefsManager,
        detail: RearStoreWidgetDetail,
        releaseTag: String? = null,
        assetName: String? = null,
        forceOverwrite: Boolean = false,
        preparedAsset: RearStorePreparedInstallAsset? = null,
        wallpaperMetadataOptions: RearWallpaperMetadataOptions? = null,
        onProgress: (RearStoreInstallProgress) -> Unit = {},
    ): RearStoreQuickInstallResult = withContext(Dispatchers.IO) {
        val selectedReleaseTag = preparedAsset?.release?.tagName.normalizedOrNull()
            ?: releaseTag.normalizedOrNull()
            ?: selectAsset(
                releases = detail.releases,
                preferredReleaseTag = releaseTag,
                preferredAssetName = assetName,
            )?.release?.tagName.normalizedOrNull()
        val installDetail = loadWidgetDetailForRelease(
            prefsManager = prefsManager,
            detail = detail,
            releaseTag = selectedReleaseTag,
        )
        val widgetInfoType = installDetail.widgetInfo.resolvedType()
        if (!widgetInfoType.supportedInCurrentVersion) {
            error("Install mode '${widgetInfoType.rawValue}' is not supported by current version")
        }
        if (!installDetail.widgetInfo.supportsModuleVersion()) {
            error("Current module version does not support installing this component")
        }
        val requirementsResult =
            installDetail.widgetInfo.evaluateRequirements(context, prefsManager)
        if (!requirementsResult.satisfied) {
            when {
                !requirementsResult.appListPermissionGranted && requirementsResult.missingPackages.isNotEmpty() -> {
                    error("Installed apps permission is required to verify widget package requirements")
                }

                requirementsResult.missingPackages.isNotEmpty() -> {
                    error(
                        "Missing required packages: ${
                            requirementsResult.missingPackages.joinToString(
                                ", "
                            )
                        }"
                    )
                }

                requirementsResult.failedConfigKeys.isNotEmpty() -> {
                    error(
                        "Unsatisfied required configs: ${
                            requirementsResult.failedConfigKeys.joinToString(
                                ", "
                            )
                        }"
                    )
                }

                else -> error("Widget requirements are not satisfied")
            }
        }
        val conflict = resolveInstallConflict(prefsManager, installDetail)
        if (conflict != null && !forceOverwrite) {
            error(buildInstallConflictMessage(conflict))
        }
        val prepared = preparedAsset ?: prepareInstallAsset(
            prefsManager = prefsManager,
            detail = installDetail,
            releaseTag = releaseTag,
            assetName = assetName,
            onProgress = onProgress,
        )
        emitInstallProgress(
            onProgress,
            RearStoreInstallProgress(stage = RearStoreInstallProgressStage.INSTALLING),
        )

        when (widgetInfoType) {
            RearStoreWidgetInfoType.WIDGET -> installWidgetAsset(
                context = context,
                prefsManager = prefsManager,
                detail = installDetail,
                selectedAsset = RearStoreSelectedAsset(prepared.release, prepared.asset),
                assetBytes = prepared.assetBytes,
                conflict = conflict,
            )

            RearStoreWidgetInfoType.WALLPAPER -> installWallpaperAsset(
                context = context,
                prefsManager = prefsManager,
                detail = installDetail,
                selectedAsset = RearStoreSelectedAsset(prepared.release, prepared.asset),
                assetBytes = prepared.assetBytes,
                metadataBytes = prepared.embeddedMetadataBytes,
                wallpaperMetadataOptions = wallpaperMetadataOptions,
            )
        }
    }

    private fun buildInstallConflictMessage(conflict: RearStoreInstallConflict): String {
        return when (conflict.source) {
            RearStoreInstallConflictSource.SAME_WIDGET -> {
                "Component '${conflict.businessId}' is already installed"
            }

            RearStoreInstallConflictSource.MANUAL_BUSINESS -> {
                "Component '${conflict.businessId}' already exists"
            }

            RearStoreInstallConflictSource.OTHER_STORE_WIDGET -> {
                val widgetName =
                    conflict.existingWidgetName ?: conflict.existingWidgetId ?: "unknown"
                "Component '${conflict.businessId}' is already registered by '$widgetName'"
            }
        }
    }

    private fun isMissingWallpaperError(error: String?): Boolean {
        val normalized = error?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized.contains("not in runtime list") ||
                normalized.contains("not in current list") ||
                normalized.contains("not found") ||
                normalized.contains("找不到")
    }

    private fun installWidgetAsset(
        context: Context,
        prefsManager: PrefsManager,
        detail: RearStoreWidgetDetail,
        selectedAsset: RearStoreSelectedAsset,
        assetBytes: ByteArray,
        conflict: RearStoreInstallConflict?,
    ): RearStoreQuickInstallResult {
        val businessSetup = detail.widgetInfo?.businessSetup
        val businessId = businessSetup?.id.normalizedOrNull()
            ?: error("Missing required widget_info.business_setup.id for type='widget'")
        val businessName = detail.widgetInfo?.name.normalizedOrNull() ?: detail.name
        val targetPath = RearWidgetManagerRepository.saveTemplateBytesToManagedPath(
            context = context,
            bytes = assetBytes,
            businessNameHint = businessId,
            fileNameHint = selectedAsset.asset.name,
        ) ?: error("Failed to save widget asset")
        val installedAt = currentUtcTimestamp()
        val storeReleaseTag = selectedAsset.release.tagName.normalizedOrNull()
        val storeReleaseAssetName = selectedAsset.asset.name.normalizedOrNull()
        val storeReleasePublishedAt = selectedAsset.release.publishedAt.normalizedOrNull()
            ?: selectedAsset.release.createdAt.normalizedOrNull()
        val conflictingStoreWidgetId = conflict
            ?.takeIf { it.source == RearStoreInstallConflictSource.OTHER_STORE_WIDGET }
            ?.existingWidgetId

        val businesses = RearWidgetManagerRepository.loadBusinesses(prefsManager)
        val previousBusiness = businesses.firstOrNull {
            it.matchesStoreBusiness(detail.widgetId, businessId) ||
                    (conflictingStoreWidgetId != null &&
                            it.storeWidgetId.normalizedOrNull() == conflictingStoreWidgetId)
        }
        val installedBusiness = RearBusinessConfig(
            id = previousBusiness?.id
                ?: RearWidgetConfigCodec.newBusinessId(
                    DEFAULT_COMPONENT_ROUTE_PACKAGE,
                    businessId,
                ),
            packageName = DEFAULT_COMPONENT_ROUTE_PACKAGE,
            business = businessId,
            filePath = targetPath,
            defaultIndex = previousBusiness?.defaultIndex ?: 0,
            defaultPriority = previousBusiness?.defaultPriority ?: 500,
            renameable = businessSetup?.renameable ?: true,
            downloadedFromStore = true,
            storeWidgetId = detail.widgetId,
            storeWidgetName = businessName,
            storeReleaseTag = storeReleaseTag,
            storeReleaseAssetName = storeReleaseAssetName,
            storeReleasePublishedAt = storeReleasePublishedAt,
            storeInstalledAt = installedAt,
        )
        val nextBusinesses = businesses
            .filterNot {
                it.matchesStoreBusiness(detail.widgetId, businessId) ||
                        (conflictingStoreWidgetId != null &&
                                it.storeWidgetId.normalizedOrNull() == conflictingStoreWidgetId)
            }
            .plus(installedBusiness)
        RearWidgetManagerRepository.saveBusinesses(
            context = context,
            prefsManager = prefsManager,
            businesses = nextBusinesses,
            allowLockedEdits = true,
        )

        val sceneSetup = detail.notificationSceneSetupOrNull()
        val sceneRoutes = RearWidgetManagerRepository.loadSceneRoutes(prefsManager)
        val previousSceneRoute = sceneSetup?.let { setup ->
            sceneRoutes.firstOrNull {
                it.matchesStoreSceneRoute(
                    widgetId = detail.widgetId,
                    packageName = setup.packageName,
                    scene = setup.scene,
                    businessId = businessId,
                ) || (conflictingStoreWidgetId != null &&
                        it.storeWidgetId.normalizedOrNull() == conflictingStoreWidgetId)
            }
        }
        val nextSceneRoutes = sceneRoutes
            .filterNot {
                it.matchesStoreSceneRoute(
                    widgetId = detail.widgetId,
                    packageName = sceneSetup?.packageName,
                    scene = sceneSetup?.scene,
                    businessId = businessId,
                ) || (conflictingStoreWidgetId != null &&
                        it.storeWidgetId.normalizedOrNull() == conflictingStoreWidgetId)
            }
            .let { existingRoutes ->
                sceneSetup?.let { setup ->
                    existingRoutes + RearWidgetSceneRouteConfig(
                        id = previousSceneRoute?.id
                            ?: RearWidgetConfigCodec.newSceneRouteId(
                                setup.packageName,
                                setup.scene,
                            ),
                        packageName = setup.packageName,
                        scene = setup.scene,
                        business = businessId,
                        downloadedFromStore = true,
                        storeWidgetId = detail.widgetId,
                        storeWidgetName = businessName,
                        storeReleaseTag = storeReleaseTag,
                        storeReleaseAssetName = storeReleaseAssetName,
                        storeReleasePublishedAt = storeReleasePublishedAt,
                    )
                } ?: existingRoutes
            }
        if (nextSceneRoutes != sceneRoutes) {
            RearWidgetManagerRepository.saveSceneRoutes(
                context = context,
                prefsManager = prefsManager,
                sceneRoutes = nextSceneRoutes,
            )
        }

        val cards = RearWidgetManagerRepository.loadCards(prefsManager)
        val cardPackage = detail.widgetInfo?.cardSetup?.packageName.normalizedOrNull()
        val installAsApp = detail.metadata.shouldInstallAsApp()
        val previousCard = cardPackage?.let { targetPackage ->
            cards.firstOrNull {
                it.matchesStoreCard(detail.widgetId, businessId, targetPackage) ||
                        (conflictingStoreWidgetId != null &&
                                it.storeWidgetId.normalizedOrNull() == conflictingStoreWidgetId)
            }
        }
        var cardInstalled = false
        var installedCardId: String? = null
        val nextCards = cards
            .filterNot {
                (installAsApp && it.storeWidgetId.normalizedOrNull() == detail.widgetId) ||
                        (cardPackage != null && it.matchesStoreCard(
                            detail.widgetId,
                            businessId,
                            cardPackage
                        )) ||
                        (conflictingStoreWidgetId != null &&
                                it.storeWidgetId.normalizedOrNull() == conflictingStoreWidgetId)
            }
            .let { existingCards ->
                if (installAsApp) {
                    installedCardId = installAppCard(
                        context = context,
                        prefsManager = prefsManager,
                        businessId = businessId,
                        title = detail.widgetInfo?.cardSetup?.name.normalizedOrNull()
                            ?: businessName,
                    )
                    cardInstalled = true
                    return@let existingCards
                }
                val cardSetup = detail.widgetInfo?.cardSetup ?: return@let existingCards
                val normalizedCardPackage = cardPackage ?: return@let existingCards
                cardInstalled = true
                val installedCard = RearCardConfig(
                    id = previousCard?.id ?: RearWidgetConfigCodec.newCardId(),
                    title = cardSetup.name.normalizedOrNull() ?: businessName,
                    packageName = normalizedCardPackage,
                    business = businessId,
                    oneConfigJson = previousCard
                        ?.takeIf { it.storeWidgetId.normalizedOrNull() == detail.widgetId }
                        ?.oneConfigJson,
                    enabled = previousCard
                        ?.takeIf { it.storeWidgetId.normalizedOrNull() == detail.widgetId }
                        ?.enabled
                        ?: true,
                    sticky = cardSetup.sticky,
                    priority = previousCard?.priority ?: cardSetup.priority,
                    renameable = cardSetup.renameable,
                    downloadedFromStore = true,
                    storeWidgetId = detail.widgetId,
                    storeWidgetName = businessName,
                    storeReleaseTag = storeReleaseTag,
                    storeReleaseAssetName = storeReleaseAssetName,
                    storeReleasePublishedAt = storeReleasePublishedAt,
                )
                installedCardId = installedCard.id
                existingCards + installedCard
            }
        if (nextCards != cards) {
            RearWidgetManagerRepository.saveCards(
                context = context,
                prefsManager = prefsManager,
                cards = nextCards,
                allowLockedEdits = true,
            )
        }

        // Component scripts are copied into REAREye-owned storage. Reinstalling a
        // component replaces the complete script tree.
        installComponentScripts(context, detail.widgetId, assetBytes)

        return RearStoreQuickInstallResult(
            widgetId = detail.widgetId,
            widgetName = businessName,
            releaseTag = storeReleaseTag,
            cardInstalled = cardInstalled,
            fallbackUsed = false,
            updatedExistingInstall = previousBusiness != null || conflict != null,
            businessConfigId = installedBusiness.id,
            cardId = installedCardId,
        )
    }

    private fun installAppCard(
        context: Context,
        prefsManager: PrefsManager,
        businessId: String,
        title: String,
    ): String {
        val existing = RearAppCardRepository.loadCatalog(context).firstOrNull {
            it.ownedByRearEye && it.componentBusiness == businessId
        }
        val result = if (existing != null) {
            RearAppCardRepository.rename(
                context = context,
                appId = existing.appId,
                title = title,
            )
        } else {
            RearAppCardRepository.register(
                context = context,
                prefsManager = prefsManager,
                title = title,
                componentBusiness = businessId,
            )
        }
        if (!result.success) {
            error(result.error ?: "Failed to install app card")
        }
        return result.appId ?: existing?.appId
        ?: error("App card installation returned no app id")
    }

    private fun installComponentScripts(
        context: Context,
        componentId: String,
        assetBytes: ByteArray
    ) {
        val safeId = componentId.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { return }
        val store = ScriptProjectStore(context)
        // Reinstall semantics intentionally clear the old component script tree even
        // when the new component has no scripts directory.
        store.uninstallComponent(safeId)
        val staging = File(context.cacheDir, "script-install-$safeId-${System.nanoTime()}")
        try {
            var found = false
            ZipInputStream(assetBytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalized = entry.name.replace('\\', '/')
                    val scriptsIndex = normalized.indexOf("scripts/")
                    if (scriptsIndex < 0 || entry.isDirectory) continue
                    val relative = normalized.substring(scriptsIndex + "scripts/".length)
                    if (relative.isBlank() || relative.contains("../")) continue
                    val target = staging.resolve(relative).canonicalFile
                    if (!target.path.startsWith(staging.canonicalPath + File.separator)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                    found = true
                }
            }
            if (found) store.installComponentScripts(safeId, staging)
        } catch (_: Throwable) {
            // A component without a scripts/ directory is valid; script installation
            // must not make the widget installation fail.
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun installWallpaperAsset(
        context: Context,
        prefsManager: PrefsManager,
        detail: RearStoreWidgetDetail,
        selectedAsset: RearStoreSelectedAsset,
        assetBytes: ByteArray,
        metadataBytes: ByteArray?,
        wallpaperMetadataOptions: RearWallpaperMetadataOptions?,
    ): RearStoreQuickInstallResult = wallpaperInstallMutex.withLock {
        val widgetName = detail.defaultWallpaperName()
        val oldRecords = loadInstalledWallpaperRecords(prefsManager)
        val previousRecords = oldRecords.filter { it.widgetId == detail.widgetId }
        val retainedRecords = oldRecords.filterNot { it.widgetId == detail.widgetId }

        val effectiveMetadataBytes = metadataBytes.takeIf { wallpaperMetadataOptions == null }
        val options = when {
            wallpaperMetadataOptions != null -> wallpaperMetadataOptions
            effectiveMetadataBytes != null && effectiveMetadataBytes.isNotEmpty() -> null
            previousRecords.isNotEmpty() -> null
            else -> detail.toWallpaperMetadataOptions(widgetName)
        }
        var result: RearWallpaperOperationResult? = null
        previousRecords.distinctBy { it.wallpaperId }.forEach { previousRecord ->
            if (result != null) return@forEach
            val updateResult = RearWallpaperRepository.updateWallpaperBytes(
                context = context,
                wallpaperId = previousRecord.wallpaperId,
                bytes = assetBytes,
                displayNameHint = selectedAsset.asset.name,
                metadataBytes = effectiveMetadataBytes,
                options = options,
            )
            if (updateResult.success || !isMissingWallpaperError(updateResult.error)) {
                result = updateResult
            }
        }
        val installResult = result ?: RearWallpaperRepository.importWallpaperBytes(
            context = context,
            bytes = assetBytes,
            displayNameHint = selectedAsset.asset.name,
            metadataBytes = effectiveMetadataBytes,
            options = options,
        )
        if (!installResult.success) {
            error(installResult.error ?: "Failed to install wallpaper")
        }
        val wallpaperId =
            installResult.wallpaperId ?: error("Wallpaper install finished without wallpaper id")
        val installedAt = currentUtcTimestamp()
        val record = RearStoreInstalledWallpaper(
            widgetId = detail.widgetId,
            widgetName = widgetName,
            wallpaperId = wallpaperId,
            releaseTag = selectedAsset.release.tagName.normalizedOrNull(),
            releasePublishedAt = selectedAsset.release.publishedAt.normalizedOrNull()
                ?: selectedAsset.release.createdAt.normalizedOrNull(),
            installedAt = installedAt,
        )
        saveInstalledWallpaperRecords(
            prefsManager,
            retainedRecords + record,
        )
        return RearStoreQuickInstallResult(
            widgetId = detail.widgetId,
            widgetName = widgetName,
            releaseTag = record.releaseTag,
            cardInstalled = false,
            fallbackUsed = false,
            updatedExistingInstall = previousRecords.isNotEmpty(),
            businessConfigId = null,
            cardId = null,
        )
    }

    private fun RearStoreWidgetDetail.defaultWallpaperName(): String {
        return widgetInfo?.name.normalizedOrNull()
            ?: name.normalizedOrNull()
            ?: widgetId
    }

    private fun RearStoreWidgetDetail.toWallpaperMetadataOptions(widgetName: String): RearWallpaperMetadataOptions {
        val descriptionText = description.normalizedOrNull().orEmpty()
        val authorName = author.displayName.normalizedOrNull().orEmpty()
        return RearWallpaperMetadataOptions(
            titleFallback = widgetName,
            titleZhCn = widgetName,
            descriptionFallback = descriptionText,
            descriptionZhCn = descriptionText,
            author = authorName,
            designer = authorName,
            category = "REAREye",
            resSubType = buildWallpaperResSubType(widgetId),
            editable = false,
            thirdParties = true,
            supportAon = false,
        )
    }

    private fun buildWallpaperResSubType(widgetId: String): String {
        return "rearstore_" + widgetId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
    }

    private fun loadInstalledWallpaperRecords(
        prefsManager: PrefsManager,
    ): List<RearStoreInstalledWallpaper> {
        val raw = prefsManager.getString(
            ConfigKeys.REAR_STORE_WALLPAPER_INSTALL_DATA,
            RearWidgetConfigCodec.EMPTY_ARRAY,
        )
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val widgetId = item.optString("widgetId").trim()
                if (widgetId.isBlank()) continue
                if (!item.has("wallpaperId")) continue
                val wallpaperId = item.optInt("wallpaperId")
                add(
                    RearStoreInstalledWallpaper(
                        widgetId = widgetId,
                        widgetName = item.optString("widgetName").trim().ifBlank { widgetId },
                        wallpaperId = wallpaperId,
                        releaseTag = item.optString("releaseTag").trim().ifBlank { null },
                        releasePublishedAt = item.optString("releasePublishedAt").trim()
                            .ifBlank { null },
                        installedAt = item.optString("installedAt").trim().ifBlank { null },
                    )
                )
            }
        }
    }

    private fun saveInstalledWallpaperRecords(
        prefsManager: PrefsManager,
        records: List<RearStoreInstalledWallpaper>,
    ) {
        val encoded = JSONArray().apply {
            records.forEach { item ->
                put(
                    JSONObject()
                        .put("widgetId", item.widgetId)
                        .put("widgetName", item.widgetName)
                        .put("wallpaperId", item.wallpaperId)
                        .put("releaseTag", item.releaseTag)
                        .put("releasePublishedAt", item.releasePublishedAt)
                        .put("installedAt", item.installedAt)
                )
            }
        }.toString()
        prefsManager.putString(ConfigKeys.REAR_STORE_WALLPAPER_INSTALL_DATA, encoded)
    }

    private fun extractRootMetadataMrm(bytes: ByteArray): ByteArray? {
        return runCatching {
            ZipInputStream(bytes.inputStream()).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    if (entry.isDirectory) continue
                    if (normalizedName == "metadata.mrm") {
                        return@runCatching zipInput.readBytes()
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun containsNotEditableMarker(bytes: ByteArray): Boolean {
        return runCatching {
            ZipInputStream(bytes.inputStream()).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    if (entry.isDirectory) continue
                    if (normalizedName == "not_editable" || normalizedName.endsWith("/not_editable")) {
                        return@runCatching true
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun parseWallpaperMetadataOptions(
        bytes: ByteArray,
        fallback: RearWallpaperMetadataOptions,
    ): RearWallpaperMetadataOptions? {
        return runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val titles = json.optLocaleObject("titles")
            val descriptions = json.optLocaleObject("descriptions")
            val authors = json.optLocaleObject("authors")
            val designers = json.optLocaleObject("designers")
            val titleFallback = titles["fallback"] ?: titles["zh_CN"] ?: fallback.titleFallback
            val descriptionFallback = descriptions["fallback"]
                ?: descriptions["zh_CN"]
                ?: fallback.descriptionFallback

            RearWallpaperMetadataOptions(
                titleFallback = titleFallback,
                titleZhCn = titles["zh_CN"] ?: titleFallback,
                descriptionFallback = descriptionFallback,
                descriptionZhCn = descriptions["zh_CN"] ?: descriptionFallback,
                author = authors["fallback"] ?: authors["zh_CN"] ?: fallback.author,
                designer = designers["fallback"] ?: designers["zh_CN"] ?: fallback.designer,
                category = json.optNonBlankString("subResourceType")
                    ?: json.optNonBlankString("widgetCategory")
                    ?: fallback.category,
                resSubType = json.optNonBlankString("resSubType") ?: fallback.resSubType,
                editable = json.optBoolean("isRearScreenEditable", fallback.editable),
                thirdParties = json.optBoolean("isThirdParties", fallback.thirdParties),
                supportAon = json.optBoolean("supportAon", fallback.supportAon),
            )
        }.getOrNull()
    }

    private fun JSONObject.optLocaleObject(key: String): Map<String, String> {
        val json = when (val value = opt(key)) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return emptyMap()

        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val locale = keys.next()
                val text = json.optString(locale).trim()
                if (locale.isNotBlank() && text.isNotBlank()) put(locale, text)
            }
        }
    }

    private fun JSONObject.optNonBlankString(key: String): String? {
        return optString(key).trim().takeIf { it.isNotBlank() }
    }

    private fun readZipEntryBytes(file: File, entryName: String): ByteArray? {
        val normalizedEntryName = entryName.replace('\\', '/').trimStart('/')
        return runCatching {
            ZipFile(file).use { zipFile ->
                val entry = zipFile.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.replace('\\', '/')
                        .trimStart('/') == normalizedEntryName
                } ?: return@runCatching null
                zipFile.getInputStream(entry).use { input -> input.readBytes() }
            }
        }.getOrNull()
    }

    private suspend fun fetchBytes(
        url: String,
        onProgress: (RearStoreInstallProgress) -> Unit,
    ): ByteArray? {
        return try {
            val request = Request.Builder()
                .url(url)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: -1L
                emitInstallProgress(
                    onProgress,
                    RearStoreInstallProgress(
                        stage = RearStoreInstallProgressStage.DOWNLOADING,
                        downloadedBytes = 0L,
                        totalBytes = totalBytes,
                    ),
                )

                body.byteStream().use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        var lastReportedBytes = 0L
                        var lastReportedAtNanos = System.nanoTime()

                        while (true) {
                            val readCount = input.read(buffer)
                            if (readCount < 0) break

                            output.write(buffer, 0, readCount)
                            downloadedBytes += readCount

                            val now = System.nanoTime()
                            val shouldReport =
                                downloadedBytes == totalBytes ||
                                        downloadedBytes - lastReportedBytes >= 64 * 1024 ||
                                        now - lastReportedAtNanos >= 100_000_000L
                            if (shouldReport) {
                                emitInstallProgress(
                                    onProgress,
                                    RearStoreInstallProgress(
                                        stage = RearStoreInstallProgressStage.DOWNLOADING,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                    ),
                                )
                                lastReportedBytes = downloadedBytes
                                lastReportedAtNanos = now
                            }
                        }

                        if (downloadedBytes != lastReportedBytes) {
                            emitInstallProgress(
                                onProgress,
                                RearStoreInstallProgress(
                                    stage = RearStoreInstallProgressStage.DOWNLOADING,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                        }

                        output.toByteArray()
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadDescriptionCached(
        baseUrl: String,
        widgetId: String
    ): RearStoreDescriptionResponse? {
        val cacheKey = cacheKey(baseUrl, widgetId)
        descriptionCache[cacheKey]?.let { return it }
        val loaded = fetchJson<RearStoreDescriptionResponse>(
            baseUrl,
            "/widget/${Uri.encode(widgetId)}/description"
        )
        if (loaded != null) descriptionCache[cacheKey] = loaded
        return loaded ?: descriptionCache[cacheKey]
    }

    private fun loadAuthorCached(baseUrl: String, widgetId: String): RearStoreAuthor? {
        val cacheKey = cacheKey(baseUrl, widgetId)
        authorCache[cacheKey]?.let { return it }
        val loaded = fetchJson<RearStoreAuthorResponse>(
            baseUrl,
            "/widget/${Uri.encode(widgetId)}/author"
        )?.author
        val normalizedLoaded = loaded?.takeIf { it.displayName.isNotBlank() }
        if (normalizedLoaded != null) authorCache[cacheKey] = normalizedLoaded
        return normalizedLoaded ?: authorCache[cacheKey]
    }

    private fun resolveAuthor(
        author: RearStoreAuthor?,
        repository: RearStoreRepositoryInfo?,
    ): RearStoreAuthor {
        author?.takeIf { it.displayName.isNotBlank() }?.let { return it }
        val repositoryOwner = repository?.owner.normalizedOrNull() ?: return RearStoreAuthor()
        return RearStoreAuthor(
            login = repositoryOwner,
            name = repositoryOwner,
            url = repository?.url.normalizedOrNull().orEmpty(),
        )
    }

    private fun loadWidgetInfoCached(baseUrl: String, widgetId: String): RearStoreWidgetInfo? {
        val cacheKey = cacheKey(baseUrl, widgetId)
        widgetInfoCache[cacheKey]?.let { return it }
        val loadedResponse = fetchJson<RearStoreWidgetInfoResponse>(
            baseUrl,
            "/widget/${Uri.encode(widgetId)}/widget-info"
        )
        loadedResponse?.type.normalizedOrNull()?.let { widgetTypeCache[cacheKey] = it }
        val loadedWidgetInfo = loadedResponse?.widgetInfo
        if (loadedWidgetInfo != null) widgetInfoCache[cacheKey] = loadedWidgetInfo
        return loadedWidgetInfo ?: widgetInfoCache[cacheKey]
    }

    private fun loadWidgetInfoForVersionCached(
        baseUrl: String,
        widgetId: String,
        version: String?,
        releases: List<RearStoreRelease> = emptyList(),
    ): RearStoreWidgetInfo? {
        val normalizedVersion = version.normalizedOrNull()
            ?: return loadWidgetInfoCached(baseUrl, widgetId)
        val cacheKey = cacheKey(baseUrl, widgetId, normalizedVersion)
        widgetInfoCache[cacheKey]?.let { return it }

        val releaseWidgetInfo = releases.firstOrNull { release ->
            release.tagName.normalizedOrNull() == normalizedVersion ||
                    release.name.normalizedOrNull() == normalizedVersion
        }?.widgetInfo
        if (releaseWidgetInfo != null) {
            widgetInfoCache[cacheKey] = releaseWidgetInfo
            return releaseWidgetInfo
        }

        val loadedWidgetInfo = fetchJson<RearStoreWidgetInfoResponse>(
            baseUrl,
            "/widget/${Uri.encode(widgetId)}/releases/${Uri.encode(normalizedVersion)}/widget-info"
        )?.widgetInfo
        if (loadedWidgetInfo != null) {
            widgetInfoCache[cacheKey] = loadedWidgetInfo
            return loadedWidgetInfo
        }

        return loadWidgetInfoCached(baseUrl, widgetId)
    }

    private fun loadWidgetTypeFromInfoCached(baseUrl: String, widgetId: String): String? {
        val cacheKey = cacheKey(baseUrl, widgetId)
        widgetTypeCache[cacheKey]?.let { return it }
        loadWidgetInfoCached(baseUrl, widgetId)
        return widgetTypeCache[cacheKey]
    }

    private fun loadReleasesCached(baseUrl: String, widgetId: String): List<RearStoreRelease>? {
        val cacheKey = cacheKey(baseUrl, widgetId)
        releasesCache[cacheKey]?.let { return it }
        val loaded = fetchJson<RearStoreReleasesResponse>(
            baseUrl,
            "/widget/${Uri.encode(widgetId)}/releases"
        )?.releases
        if (loaded != null) releasesCache[cacheKey] = loaded
        return loaded ?: releasesCache[cacheKey]
    }

    private inline fun <reified T> fetchJson(baseUrl: String, path: String): T? {
        return runCatching {
            val request = Request.Builder()
                .url(baseUrl + path)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                gson.fromJson(response.body.string(), T::class.java)
            }
        }.getOrNull()
    }

    private suspend fun downloadAssetBytes(
        baseUrl: String,
        widgetId: String,
        selectedAsset: RearStoreSelectedAsset,
        onProgress: (RearStoreInstallProgress) -> Unit,
    ): ByteArray? {
        val tagName = selectedAsset.release.tagName.normalizedOrNull() ?: return null
        val assetName = selectedAsset.asset.name.normalizedOrNull() ?: return null
        val apiBytes = fetchBytes(
            baseUrl + "/widget/${Uri.encode(widgetId)}/releases/${Uri.encode(tagName)}/${
                Uri.encode(
                    assetName
                )
            }",
            onProgress = onProgress,
        )
        return apiBytes ?: fetchBytes(
            url = selectedAsset.asset.downloadUrl,
            onProgress = onProgress,
        )
    }

    private suspend fun emitInstallProgress(
        onProgress: (RearStoreInstallProgress) -> Unit,
        progress: RearStoreInstallProgress,
    ) {
        withContext(Dispatchers.Main) {
            onProgress(progress)
        }
    }

    private fun cacheKey(baseUrl: String, widgetId: String): String {
        return "$baseUrl\u0000$widgetId"
    }

    private fun cacheKey(baseUrl: String, widgetId: String, version: String?): String {
        val normalizedVersion = version.normalizedOrNull() ?: return cacheKey(baseUrl, widgetId)
        return "$baseUrl\u0000$widgetId\u0000$normalizedVersion"
    }

    private fun selectAsset(
        releases: List<RearStoreRelease>,
        preferredReleaseTag: String? = null,
        preferredAssetName: String? = null,
    ): RearStoreSelectedAsset? {
        val normalizedReleaseTag = preferredReleaseTag.normalizedOrNull()
        val normalizedAssetName = preferredAssetName.normalizedOrNull()
        val preferredRelease = releases.firstOrNull {
            it.tagName.normalizedOrNull() == normalizedReleaseTag && it.assets.isNotEmpty()
        }
        val release =
            preferredRelease ?: releases.firstOrNull { it.assets.isNotEmpty() } ?: return null
        val preferredAsset = release.assets.firstOrNull {
            it.name.normalizedOrNull() == normalizedAssetName
        }
        val asset =
            preferredAsset ?: release.assets.minByOrNull { it.installPriority() } ?: return null
        return RearStoreSelectedAsset(release = release, asset = asset)
    }

    private fun RearStoreReleaseAsset.installPriority(): Int {
        val fileName = name.lowercase()
        return when {
            fileName.endsWith(".mrc") -> 0
            fileName.endsWith(".zip") -> 1
            else -> 2
        }
    }

    private fun currentUtcTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun RearBusinessConfig.matchesStoreBusiness(
        widgetId: String,
        businessId: String
    ): Boolean {
        return (downloadedFromStore && storeWidgetId.normalizedOrNull() == widgetId) ||
                (packageName == DEFAULT_COMPONENT_ROUTE_PACKAGE && business == businessId)
    }

    private fun RearCardConfig.matchesStoreCard(
        widgetId: String,
        businessId: String,
        packageName: String,
    ): Boolean {
        return (downloadedFromStore && storeWidgetId.normalizedOrNull() == widgetId) ||
                (this.packageName == packageName && business == businessId)
    }

    private fun RearWidgetSceneRouteConfig.matchesStoreSceneRoute(
        widgetId: String,
        packageName: String?,
        scene: String?,
        businessId: String,
    ): Boolean {
        if (downloadedFromStore && storeWidgetId.normalizedOrNull() == widgetId) {
            return true
        }
        val normalizedPackageName = packageName.normalizedOrNull() ?: return false
        val normalizedScene = scene.normalizedOrNull() ?: return false
        return this.packageName == normalizedPackageName &&
                RearWidgetSceneRouteSpec.normalizeScenePattern(this.scene) ==
                RearWidgetSceneRouteSpec.normalizeScenePattern(normalizedScene) &&
                business == businessId
    }

}
