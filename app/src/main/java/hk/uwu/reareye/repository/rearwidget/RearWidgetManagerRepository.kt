package hk.uwu.reareye.repository.rearwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository.getShowTimeTipForBusiness
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.widgetapi.RearWidgetApiClient
import hk.uwu.reareye.widgetapi.RearWidgetApiContract
import hk.uwu.reareye.widgetapi.RearWidgetNoticeOptions
import hk.uwu.reareye.widgetapi.RearWidgetSceneRouteSpec
import hk.uwu.reareye.widgetapi.RearWidgetTemplateConfigState
import hk.uwu.reareye.widgetapi.RearWidgetTemplateImagePreview
import java.io.File
import java.security.MessageDigest

object RearWidgetManagerRepository {

    private const val BUSINESS_TEMPLATE_DIR = "rear_widget_business"
    private const val TAG = "RearWidgetDebug"

    @Volatile
    private var remoteClient: RearWidgetApiClient? = null

    fun loadBusinesses(prefsManager: PrefsManager): List<RearBusinessConfig> {
        val raw = prefsManager.getString(
            ConfigKeys.REAR_WIDGET_BUSINESS_DATA,
            RearWidgetConfigCodec.EMPTY_ARRAY,
        )
        return RearWidgetConfigCodec.parseBusinesses(raw)
    }

    fun saveBusinesses(
        context: Context,
        prefsManager: PrefsManager,
        businesses: List<RearBusinessConfig>,
        allowLockedEdits: Boolean = false,
    ) {
        val oldBusinesses = loadBusinesses(prefsManager)
        val mergedBusinesses = mergeLockedBusinesses(oldBusinesses, businesses, allowLockedEdits)
        val preparedBusinesses = prepareBusinessesInManagedDir(context, mergedBusinesses)
        cacheBusinessTemplatesInPrefs(prefsManager, oldBusinesses, preparedBusinesses)
        prefsManager.putString(
            ConfigKeys.REAR_WIDGET_BUSINESS_DATA,
            RearWidgetConfigCodec.encodeBusinesses(preparedBusinesses),
        )
        cleanupStaleManagedFiles(context, oldBusinesses, preparedBusinesses)
        applyBusinessFilesViaApi(context, oldBusinesses, preparedBusinesses)

        // business 文件变化后，重放当前卡片，确保显示状态与模板一致。
        val cards = loadCards(prefsManager)
        applyCardsViaApi(context, cards, cards, preparedBusinesses)
    }

    fun loadCards(prefsManager: PrefsManager): List<RearCardConfig> {
        val raw = prefsManager.getString(
            ConfigKeys.REAR_WIDGET_CARD_DATA,
            RearWidgetConfigCodec.EMPTY_ARRAY,
        )
        return RearCardPriorityManager.sorted(
            RearWidgetConfigCodec.parseCards(raw), loadCardOrderSettings(prefsManager),
        )
    }

    fun loadCardOrderSettings(prefsManager: PrefsManager): Map<String, RearCardOrderSetting> =
        RearCardPriorityManager.parse(
            prefsManager.getString(
                ConfigKeys.REAR_WIDGET_CARD_ORDER_DATA,
                "{}"
            )
        )

    fun saveCardOrderSettings(
        prefsManager: PrefsManager,
        settings: Map<String, RearCardOrderSetting>
    ) {
        prefsManager.putString(
            ConfigKeys.REAR_WIDGET_CARD_ORDER_DATA,
            RearCardPriorityManager.encode(settings)
        )
    }

    fun saveCards(
        context: Context,
        prefsManager: PrefsManager,
        cards: List<RearCardConfig>,
        allowLockedEdits: Boolean = false,
    ) {
        val oldCards = loadCards(prefsManager)
        val mergedCards = RearCardPriorityManager.sorted(
            mergeLockedCards(oldCards, cards, allowLockedEdits),
            loadCardOrderSettings(prefsManager),
        )
        prefsManager.putString(
            ConfigKeys.REAR_WIDGET_CARD_DATA,
            RearWidgetConfigCodec.encodeCards(mergedCards),
        )
        val businesses = loadBusinesses(prefsManager)
        applyCardsViaApi(context, oldCards, mergedCards, businesses)
    }

    fun loadSceneRoutes(prefsManager: PrefsManager): List<RearWidgetSceneRouteConfig> {
        val raw = prefsManager.getString(
            ConfigKeys.REAR_WIDGET_SCENE_ROUTE_DATA,
            RearWidgetConfigCodec.EMPTY_ARRAY,
        )
        return normalizeSceneRoutes(RearWidgetConfigCodec.parseSceneRoutes(raw))
    }

    fun saveSceneRoutes(
        context: Context,
        prefsManager: PrefsManager,
        sceneRoutes: List<RearWidgetSceneRouteConfig>,
    ) {
        val oldSceneRoutes = loadSceneRoutes(prefsManager)
        val normalizedSceneRoutes = normalizeSceneRoutes(sceneRoutes)
        prefsManager.putString(
            ConfigKeys.REAR_WIDGET_SCENE_ROUTE_DATA,
            RearWidgetConfigCodec.encodeSceneRoutes(normalizedSceneRoutes),
        )
        applySceneRoutesViaApi(context, oldSceneRoutes, normalizedSceneRoutes)
    }

    fun setCardEnabled(
        context: Context,
        prefsManager: PrefsManager,
        cardId: String,
        enabled: Boolean,
    ) {
        val oldCards = loadCards(prefsManager)
        val targetIndex = oldCards.indexOfFirst { it.id == cardId }
        if (targetIndex < 0) return

        val oldCard = oldCards[targetIndex]
        if (!oldCard.renameable) return
        if (oldCard.enabled == enabled) return

        val newCards = oldCards.toMutableList().apply {
            this[targetIndex] = oldCard.copy(enabled = enabled)
        }

        prefsManager.putString(
            ConfigKeys.REAR_WIDGET_CARD_DATA,
            RearWidgetConfigCodec.encodeCards(newCards),
        )

        applyCardsViaApi(context, oldCards, newCards, loadBusinesses(prefsManager))
    }

    fun refreshRuntimeFromPrefs(context: Context, prefsManager: PrefsManager) {
        val businesses = loadBusinesses(prefsManager)
        val preparedBusinesses = prepareBusinessesInManagedDir(context, businesses)
        cacheBusinessTemplatesInPrefs(prefsManager, businesses, preparedBusinesses)
        if (preparedBusinesses != businesses) {
            prefsManager.putString(
                ConfigKeys.REAR_WIDGET_BUSINESS_DATA,
                RearWidgetConfigCodec.encodeBusinesses(preparedBusinesses),
            )
        }
        val cards = loadCards(prefsManager)
        val sceneRoutes = loadSceneRoutes(prefsManager)
        debugLog(
            context,
            "refreshRuntimeFromPrefs businesses=${preparedBusinesses.size} cards=${cards.size} sceneRoutes=${sceneRoutes.size}",
        )
        applyBusinessFilesViaApi(context, businesses, preparedBusinesses)
        applySceneRoutesViaApi(
            context = context,
            oldSceneRoutes = sceneRoutes,
            newSceneRoutes = sceneRoutes,
            reapplyAll = true,
        )
        applyCardsViaApi(
            context = context,
            oldCards = cards,
            newCards = cards,
            businesses = preparedBusinesses,
            preserveExistingDisplay = true,
            repostStickyCards = false,
        )
    }

    fun resolveTemplateImagePreview(
        context: Context,
        business: String,
        sourceFilePath: String,
        imageValue: String,
    ): RearWidgetTemplateImagePreview? {
        val normalizedBusiness = business.trim()
        val normalizedSource = sourceFilePath.trim()
        val normalizedValue = imageValue.trim()
        if (normalizedValue.isBlank() || (normalizedBusiness.isBlank() && normalizedSource.isBlank())) {
            return null
        }
        debugLog(
            context,
            "resolveTemplateImagePreview business=$normalizedBusiness source=${normalizedSource.ifBlank { "<builtin>" }} value=$normalizedValue",
        )
        return runCatching {
            withApiClient(context) { client ->
                client.resolveTemplateImagePreview(
                    business = normalizedBusiness,
                    sourceFilePath = normalizedSource,
                    imageValue = normalizedValue,
                )
            }
        }.getOrNull()
    }

    fun resolveTemplateConfigState(
        context: Context,
        business: String,
        sourceFilePath: String,
        currentOneConfigJson: String?,
    ): RearWidgetTemplateConfigState? {
        val normalizedBusiness = business.trim()
        val normalizedSource = sourceFilePath.trim()
        if (normalizedBusiness.isBlank()) return null
        debugLog(
            context,
            "resolveTemplateConfigState business=$normalizedBusiness source=${normalizedSource.ifBlank { "<builtin>" }} hasConfig=${
                currentOneConfigJson.isNullOrBlank().not()
            }",
        )
        return runCatching {
            withApiClient(context) { client ->
                client.resolveTemplateConfigState(
                    business = normalizedBusiness,
                    sourceFilePath = normalizedSource,
                    currentOneConfigJson = currentOneConfigJson,
                )
            }
        }.getOrNull()
    }

    fun importCardCustomImage(
        context: Context,
        cardKey: String,
        fieldName: String,
        uri: Uri,
    ): String? {
        val normalizedCardKey = cardKey.trim()
        val normalizedFieldName = fieldName.trim()
        if (normalizedCardKey.isBlank() || normalizedFieldName.isBlank()) return null

        return runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.grantUriPermission(
                RearWidgetApiContract.HOOK_HOST_PACKAGE,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )

            val displayNameHint = queryDisplayName(context, uri)
                ?.trim()
                ?.ifBlank { null }
                ?: "$normalizedFieldName.png"

            withApiClient(context) { client ->
                client.importCardCustomImage(
                    cardKey = normalizedCardKey,
                    fieldName = normalizedFieldName,
                    sourceUri = uri.toString(),
                    displayNameHint = displayNameHint,
                )
            }
        }.getOrNull()
    }

    fun forceSyncRuntime(context: Context) {
        debugLog(context, "forceSyncRuntime")
        runCatching {
            withApiClient(context) { client ->
                client.syncState()
            }
        }
    }

    fun copyTemplateToManagedPath(
        context: Context,
        uri: Uri,
        businessNameHint: String,
    ): String? {
        return runCatching {
            val root = resolveManagedTemplateRoot(context)
            if (!root.exists()) root.mkdirs()

            val sourceName = queryDisplayName(context, uri)
            val extension = sourceName
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() }
                ?: "json"
            val safeBusiness = sanitizeFileName(businessNameHint.ifBlank { "business" })
            val target = File(root, "${safeBusiness}_${System.currentTimeMillis()}.$extension")

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            target.absolutePath
        }.getOrNull()
    }

    fun saveTemplateBytesToManagedPath(
        context: Context,
        bytes: ByteArray,
        businessNameHint: String,
        fileNameHint: String,
    ): String? {
        return runCatching {
            val root = resolveManagedTemplateRoot(context)
            if (!root.exists()) root.mkdirs()

            val extension = fileNameHint
                .substringAfterLast('.', "")
                .takeIf { it.isNotBlank() }
                ?: "bin"
            val safeBusiness = sanitizeFileName(businessNameHint.ifBlank { "business" })
            val target = File(root, "${safeBusiness}_${System.currentTimeMillis()}.$extension")

            writeBytesAtomically(target, bytes)
            target.absolutePath
        }.getOrNull()
    }

    private fun applyBusinessFilesViaApi(
        context: Context,
        oldBusinesses: List<RearBusinessConfig>,
        newBusinesses: List<RearBusinessConfig>,
    ) {
        val oldByBusiness = oldBusinesses.associateBy { it.business }
        val newByBusiness = newBusinesses.associateBy { it.business }

        val staleBusinesses = oldByBusiness.keys - newByBusiness.keys
        staleBusinesses.forEach { business ->
            unregisterBusinessFile(context, business)
        }

        newBusinesses.forEach { item ->
            registerBusinessFile(context, item.business, item.filePath)
        }
    }

    private fun applySceneRoutesViaApi(
        context: Context,
        oldSceneRoutes: List<RearWidgetSceneRouteConfig>,
        newSceneRoutes: List<RearWidgetSceneRouteConfig>,
        reapplyAll: Boolean = false,
    ) {
        val oldById = oldSceneRoutes.associateBy { it.id }
        val newById = newSceneRoutes.associateBy { it.id }

        if (reapplyAll) {
            newSceneRoutes.forEach { item ->
                registerSceneRoute(context, item.packageName, item.scene, item.business)
            }
            return
        }

        (oldById.keys - newById.keys).forEach { id ->
            oldById[id]?.let { item ->
                unregisterSceneRoute(context, item.packageName, item.scene)
            }
        }

        newSceneRoutes.forEach { item ->
            val old = oldById[item.id]
            if (old == null || old.business != item.business) {
                if (old != null) {
                    unregisterSceneRoute(context, old.packageName, old.scene)
                }
                registerSceneRoute(context, item.packageName, item.scene, item.business)
            }
        }
    }

    private fun applyCardsViaApi(
        context: Context,
        oldCards: List<RearCardConfig>,
        newCards: List<RearCardConfig>,
        businesses: List<RearBusinessConfig>,
        preserveExistingDisplay: Boolean = false,
        repostStickyCards: Boolean = true,
    ) {
        val prefsManager = context.getPrefsManager()
        val businessPathByName = businesses.associate { it.business to it.filePath }

        val oldPairs = oldCards.mapTo(LinkedHashSet()) { it.packageName to it.business }
        val newPairs = newCards.mapTo(LinkedHashSet()) { it.packageName to it.business }
        val allPairs = LinkedHashSet<Pair<String, String>>().apply {
            addAll(oldPairs)
            addAll(newPairs)
        }

        val enabledCards = newCards.filter { it.enabled }
        val enabledPairs = enabledCards.mapTo(LinkedHashSet()) { it.packageName to it.business }
        // API 这里只能按 (package,business) 业务级清理；cardId 对应的 ticket 仅保存在 Hook 进程，
        // 现有跨进程 API 没有查询/删除单卡能力。因此这里只修复跨 pair 整组误删：
        // 新配置仍启用同一 pair 时不清理；同一 pair 内删除单张卡无法在此精确处理。
        val pairsToDisable = if (preserveExistingDisplay) {
            oldPairs - enabledPairs
        } else {
            allPairs - enabledPairs
        }
        debugLog(
            context,
            "applyCardsViaApi old=${oldCards.size} new=${newCards.size} enabled=${enabledCards.size} disable=${pairsToDisable.size} preserve=$preserveExistingDisplay",
        )

        pairsToDisable.forEach { (pkg, biz) ->
            disableBusinessDisplay(context, pkg, biz)
        }

        // 精确关闭被禁用的单卡：当同一业务下仍启用其他卡时，业务级 pairsToDisable 不会命中，
        // 旧 cardId 对应的 ticket 仍残留在 SubScreenCenter 持久化中，导致该卡无法关闭。
        // 这里对"旧配置启用、新配置禁用"的每张卡按 cardId 精确下发删除。
        val disabledCards = computeDisabledCards(oldCards, newCards)
        disabledCards.forEach { card ->
            disableCardDisplay(context, card.packageName, card.business, card.id)
        }
        if (disabledCards.isNotEmpty()) {
            debugLog(
                context,
                "applyCardsViaApi disabledCards=${disabledCards.map { it.id }}",
            )
        }

        enabledPairs.forEach { (pkg, biz) ->
            val filePath = businessPathByName[biz]
            if (!filePath.isNullOrBlank()) {
                registerBusiness(
                    context = context,
                    packageName = pkg,
                    business = biz,
                    filePath = filePath,
                )
            } else {
                registerBusiness(
                    context = context,
                    packageName = pkg,
                    business = biz,
                    filePath = null,
                )
            }
        }

        (allPairs - enabledPairs).forEach { (pkg, biz) ->
            unregisterBusiness(context, pkg, biz)
        }

        if (repostStickyCards) {
            enabledCards
                .filter { it.sticky }
                .forEachIndexed { index, card ->
                    postCard(
                        context = context,
                        prefsManager = prefsManager,
                        card = card,
                        index = index,
                    )
                }
        }
    }

    /**
     * 计算旧配置中启用、但新配置中已禁用的卡片集合。
     *
     * 用于在同业务仍启用其他卡时按 cardId 精确下发删除：业务级清理只能按
     * (packageName, business) 整组处理，无法命中单卡关闭。
     */
    internal fun computeDisabledCards(
        oldCards: List<RearCardConfig>,
        newCards: List<RearCardConfig>,
    ): List<RearCardConfig> {
        return oldCards.filter { old ->
            old.enabled && newCards.none { it.id == old.id && it.enabled }
        }
    }

    private fun registerBusiness(
        context: Context,
        packageName: String,
        business: String,
        filePath: String?,
    ) {
        runCatching {
            withApiClient(context) { client ->
                if (!filePath.isNullOrBlank()) {
                    client.registerBusiness(
                        packageName,
                        business,
                        filePath,
                        0,
                        500,
                    )
                } else {
                    client.registerBusinessWithoutFile(
                        packageName,
                        business,
                        0,
                        500,
                    )
                }
            }
        }
    }

    private fun disableBusinessDisplay(
        context: Context,
        packageName: String,
        business: String,
    ) {
        runCatching {
            withApiClient(context) { client ->
                client.disableBusinessDisplay(packageName, business)
            }
        }
    }

    private fun disableCardDisplay(
        context: Context,
        packageName: String,
        business: String,
        cardId: String,
    ) {
        runCatching {
            withApiClient(context) { client ->
                client.disableCardDisplay(packageName, business, cardId)
            }
        }
    }

    private fun registerSceneRoute(
        context: Context,
        packageName: String,
        scene: String,
        business: String,
    ) {
        runCatching {
            withApiClient(context) { client ->
                client.registerSceneRoute(packageName, scene, business)
            }
        }
    }

    private fun unregisterSceneRoute(
        context: Context,
        packageName: String,
        scene: String,
    ) {
        runCatching {
            withApiClient(context) { client ->
                client.unregisterSceneRoute(packageName, scene)
            }
        }
    }

    private fun unregisterBusiness(
        context: Context,
        packageName: String,
        business: String,
    ) {
        runCatching {
            withApiClient(context) { client ->
                client.unregisterBusiness(packageName, business)
            }
        }
    }

    private fun postCard(
        context: Context,
        prefsManager: PrefsManager,
        card: RearCardConfig,
        index: Int,
    ) {
        if (!card.sticky) return

        val payload = Bundle().apply {
            putString("title", card.title.ifBlank { card.business })
            putString("business", card.business)
            putString("__rear_card_id__", card.id)
            card.oneConfigJson?.takeIf { it.isNotBlank() }?.let {
                putString(REAR_WIDGET_CARD_ONE_CONFIG_JSON_KEY, it)
            }
        }
        val options = RearWidgetNoticeOptions(
            sticky = card.sticky,
            disablePopup = true,
            showTimeTip = prefsManager.getShowTimeTipForBusiness(card.business),
            index = index,
            priority = card.priority,
        )
        postNotice(
            context = context,
            packageName = card.packageName,
            business = card.business,
            payload = payload,
            options = options,
        )
    }

    private fun registerBusinessFile(context: Context, business: String, filePath: String) {
        runCatching {
            withApiClient(context) { client ->
                client.registerBusinessFile(business, filePath)
            }
        }
    }

    private fun unregisterBusinessFile(context: Context, business: String) {
        runCatching {
            withApiClient(context) { client ->
                client.unregisterBusinessFile(business)
            }
        }
    }

    private fun postNotice(
        context: Context,
        packageName: String,
        business: String,
        payload: Bundle,
        options: RearWidgetNoticeOptions,
    ) {
        runCatching {
            withApiClient(context) { client ->
                client.postNotice(packageName, business, payload, options)
            }
        }
    }

    private fun debugLog(context: Context, message: String) {
        val prefs = context.getPrefsManager()
        if (prefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            Log.d(TAG, message)
        }
    }

    private fun <T> withApiClient(
        context: Context,
        block: (RearWidgetApiClient) -> T,
    ): T {
        val appContext = context.applicationContext
        val client = remoteClient ?: RearWidgetApiClient().also { remoteClient = it }
        runCatching {
            if (client.isConnected() || client.bind(appContext)) return block(client)
            throw IllegalStateException("RearWidget API service is not connected")
        }.onFailure {
            client.unbind()
            if (remoteClient === client) remoteClient = null
        }.getOrThrow()
    }

    private fun cleanupStaleManagedFiles(
        context: Context,
        oldBusinesses: List<RearBusinessConfig>,
        newBusinesses: List<RearBusinessConfig>,
    ) {
        val root = resolveManagedTemplateRoot(context)
        val rootNorm = normalizePath(root.absolutePath) ?: return
        val activePaths = newBusinesses.mapNotNull { normalizePath(it.filePath) }.toSet()
        val stalePaths = oldBusinesses.mapNotNull { normalizePath(it.filePath) }
            .filter { it !in activePaths }

        stalePaths.forEach { stalePath ->
            deleteIfManagedPath(stalePath, rootNorm)
        }

        if (root.exists() && root.isDirectory) {
            root.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val filePath = normalizePath(file.absolutePath) ?: return@forEach
                if (filePath !in activePaths) runCatching { file.delete() }
            }
        }
    }

    private fun resolveManagedTemplateRoot(context: Context): File {
        return File(context.filesDir, BUSINESS_TEMPLATE_DIR)
    }

    private fun prepareBusinessesInManagedDir(
        context: Context,
        businesses: List<RearBusinessConfig>,
    ): List<RearBusinessConfig> {
        val root = resolveManagedTemplateRoot(context)
        if (!root.exists()) root.mkdirs()
        val rootNorm = normalizePath(root.absolutePath)

        return businesses.map { item ->
            val currentNorm = normalizePath(item.filePath)
            val alreadyManaged = !rootNorm.isNullOrBlank() && !currentNorm.isNullOrBlank() &&
                    (currentNorm == rootNorm || currentNorm.startsWith("$rootNorm/")) && File(item.filePath).exists()
            if (alreadyManaged) return@map item

            val source = File(item.filePath)
            if (!source.exists() || !source.isFile) return@map item

            val extension =
                source.name.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "json"
            val target = File(
                root,
                "${sanitizeFileName(item.business)}_${System.currentTimeMillis()}.$extension"
            )
            runCatching {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure {
                return@map item
            }
            item.copy(filePath = target.absolutePath)
        }
    }

    private fun cacheBusinessTemplatesInPrefs(
        prefsManager: PrefsManager,
        oldBusinesses: List<RearBusinessConfig>,
        newBusinesses: List<RearBusinessConfig>,
    ) {
        val oldSet = oldBusinesses.mapTo(HashSet()) { it.business }
        val newSet = newBusinesses.mapTo(HashSet()) { it.business }
        (oldSet - newSet).forEach { business ->
            clearBusinessTemplateBlob(prefsManager, business)
        }

        newBusinesses.forEach { item ->
            upsertBusinessTemplateBlob(
                prefsManager = prefsManager,
                business = item.business,
                sourcePath = item.filePath,
            )
        }
    }

    private fun upsertBusinessTemplateBlob(
        prefsManager: PrefsManager,
        business: String,
        sourcePath: String,
    ) {
        val source = File(sourcePath)
        if (!source.exists() || !source.isFile) return

        val sourceSig = buildSourceSignature(source)
        val sourceKey = RearWidgetConfigCodec.businessBlobSourceKey(business)
        val blobKey = RearWidgetConfigCodec.businessBlobKey(business)
        val metaKey = RearWidgetConfigCodec.businessBlobMetaKey(business)
        val remoteFileName = RearWidgetConfigCodec.businessBlobRemoteFileName(business)
        val marker = RearWidgetConfigCodec.remoteBlobMarker(remoteFileName)

        val oldSourceSig = prefsManager.getString(sourceKey, "")
        val oldBlob = prefsManager.getString(blobKey, "")
        val oldMeta = prefsManager.getString(metaKey, "")
        if (oldSourceSig == sourceSig && oldBlob == marker && oldMeta.isNotBlank()) return

        val bytes = runCatching { source.readBytes() }
            .onFailure {
                Log.e(
                    TAG,
                    "Unable to read rear widget business template: business=$business path=$sourcePath",
                    it
                )
            }
            .getOrElse { throw it }
        val newMeta = buildBlobMeta(bytes)
        check(prefsManager.writeRemoteFile(remoteFileName, bytes)) {
            "Unable to write rear widget RemoteFile: business=$business size=${bytes.size}"
        }

        val committed = prefsManager.prefs.edit()
            .putString(blobKey, marker)
            .putString(metaKey, newMeta)
            .putString(sourceKey, sourceSig)
            .commit()
        check(committed) {
            "Unable to commit rear widget RemoteFile marker: business=$business size=${bytes.size}"
        }
    }

    private fun clearBusinessTemplateBlob(prefsManager: PrefsManager, business: String) {
        val blobKey = RearWidgetConfigCodec.businessBlobKey(business)
        val oldMarker = RearWidgetConfigCodec.remoteBlobFileNameFromMarker(
            prefsManager.getString(blobKey, ""),
        )
        val namesToDelete = linkedSetOf(
            RearWidgetConfigCodec.businessBlobRemoteFileName(business),
            oldMarker,
        ).filterNotNull()
        namesToDelete.forEach { name ->
            check(prefsManager.deleteRemoteFile(name)) {
                "Unable to delete rear widget RemoteFile: business=$business"
            }
        }
        val committed = prefsManager.prefs.edit()
            .remove(blobKey)
            .remove(RearWidgetConfigCodec.businessBlobMetaKey(business))
            .remove(RearWidgetConfigCodec.businessBlobSourceKey(business))
            .commit()
        check(committed) {
            "Unable to clear rear widget RemoteFile metadata: business=$business"
        }
    }

    private fun deleteIfManagedPath(path: String, rootNorm: String) {
        val target = File(path)
        val targetNorm = normalizePath(target.absolutePath) ?: return
        val managed = targetNorm == rootNorm || targetNorm.startsWith("$rootNorm/")
        if (!managed) return
        runCatching {
            if (target.exists() && target.isFile) target.delete()
        }
    }

    private fun normalizePath(path: String): String? {
        return runCatching { File(path).absolutePath.replace('\\', '/') }.getOrNull()
    }

    private fun mergeLockedBusinesses(
        oldBusinesses: List<RearBusinessConfig>,
        newBusinesses: List<RearBusinessConfig>,
        allowLockedEdits: Boolean,
    ): List<RearBusinessConfig> {
        if (allowLockedEdits) return newBusinesses

        val oldLockedBusinesses = oldBusinesses.filter { !it.renameable }
        if (oldLockedBusinesses.isEmpty()) return newBusinesses

        val merged = newBusinesses.map { candidate ->
            val locked = oldLockedBusinesses.firstOrNull { existing ->
                existing.id == candidate.id ||
                        (existing.downloadedFromStore && candidate.storeWidgetId != null && existing.storeWidgetId == candidate.storeWidgetId) ||
                        existing.business == candidate.business
            }
            locked ?: candidate
        }.toMutableList()

        return merged
    }

    private fun mergeLockedCards(
        oldCards: List<RearCardConfig>,
        newCards: List<RearCardConfig>,
        allowLockedEdits: Boolean,
    ): List<RearCardConfig> {
        if (allowLockedEdits) return newCards

        val oldLockedCards = oldCards.filter { !it.renameable }
        if (oldLockedCards.isEmpty()) return newCards

        val merged = newCards.map { candidate ->
            val locked = oldLockedCards.firstOrNull { existing ->
                existing.id == candidate.id ||
                        (existing.downloadedFromStore && candidate.storeWidgetId != null && existing.storeWidgetId == candidate.storeWidgetId) ||
                        (existing.packageName == candidate.packageName && existing.business == candidate.business)
            }
            locked?.copy(priority = candidate.priority) ?: candidate
        }.toMutableList()

        return merged
    }

    private fun normalizeSceneRoutes(
        sceneRoutes: List<RearWidgetSceneRouteConfig>,
    ): List<RearWidgetSceneRouteConfig> {
        val normalizedById = LinkedHashMap<String, RearWidgetSceneRouteConfig>()
        sceneRoutes.forEach { item ->
            val packageName = item.packageName.trim()
            val scene = RearWidgetSceneRouteSpec.normalizeScenePattern(item.scene)
            val business = item.business.trim()
            if (packageName.isBlank() || scene.isBlank() || business.isBlank()) return@forEach
            val id = RearWidgetConfigCodec.newSceneRouteId(packageName, scene)
            normalizedById[id] = RearWidgetSceneRouteConfig(
                id = id,
                packageName = packageName,
                scene = scene,
                business = business,
                downloadedFromStore = item.downloadedFromStore,
                storeWidgetId = item.storeWidgetId,
                storeWidgetName = item.storeWidgetName,
                storeReleaseTag = item.storeReleaseTag,
                storeReleaseAssetName = item.storeReleaseAssetName,
                storeReleasePublishedAt = item.storeReleasePublishedAt,
            )
        }
        return normalizedById.values.sortedWith(
            compareBy(
                { it.packageName.lowercase() },
                { RearWidgetSceneRouteSpec.normalizeScenePattern(it.scene).lowercase() },
                { it.business.lowercase() },
            )
        )
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        tempFile.outputStream().use { it.write(bytes) }
        if (target.exists()) target.delete()
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx < 0 || !cursor.moveToFirst()) return@use null
                    cursor.getString(idx)
                }
        }.getOrNull()
    }

    private fun sanitizeFileName(source: String): String {
        return source.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun buildSourceSignature(source: File): String {
        return "${normalizePath(source.absolutePath)}|${source.length()}|${source.lastModified()}"
    }

    private fun buildBlobMeta(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString(separator = "") { "%02x".format(it) }
        return "$hex:${bytes.size}"
    }
}
