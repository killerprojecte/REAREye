package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.os.Bundle
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.RearWidgetRuntimeStore.cardNoticeCompositeIndex
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.RearWidgetRuntimeStore.disableBusinessDisplay
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.widgetapi.RearWidgetActiveNotice
import hk.uwu.reareye.widgetapi.RearWidgetBusinessSpec
import hk.uwu.reareye.widgetapi.RearWidgetNoticeOptions
import hk.uwu.reareye.widgetapi.RearWidgetNoticeTicket
import hk.uwu.reareye.widgetapi.RearWidgetSceneRouteSpec
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object RearWidgetRuntimeStore {

    @Volatile
    var defaultPackageName: String = "com.xiaomi.subscreencenter"

    val mapsDirty = AtomicBoolean(false)

    private val businessFiles = ConcurrentHashMap<String, String>()
    private val routes = ConcurrentHashMap<String, MutableMap<String, RearWidgetBusinessSpec>>()

    @Volatile
    private var sceneRoutes: List<RearWidgetSceneRouteSpec> = emptyList()

    @Volatile
    private var exactSceneRoutesByPackage: Map<String, Map<String, RearWidgetSceneRouteSpec>> =
        emptyMap()

    private val sceneRouteLock = Any()
    private val routedNoticeByIdentity = ConcurrentHashMap<String, String>()
    private val notices = ConcurrentHashMap<String, RearWidgetActiveNotice>()
    private val cardNoticeIdIndex = ConcurrentHashMap<String, Int>()
    private val cardNoticeCompositeIndex = ConcurrentHashMap<String, String>()
    private val idSeed = AtomicInteger(310000)

    fun install(defaultPkg: String) {
        defaultPackageName = defaultPkg
        routes.computeIfAbsent(defaultPkg) { linkedMapOf() }
        mapsDirty.set(true)
    }

    fun registerBusinessFile(business: String, filePath: String) {
        businessFiles[business] = filePath
        routes.forEach { (_, bizMap) ->
            val old = bizMap[business]
            if (old != null) bizMap[business] = old.copy(filePath = filePath)
        }
        mapsDirty.set(true)
    }

    fun getBusinessFile(business: String): String? {
        return businessFiles[business]
            ?: routes.values.firstNotNullOfOrNull { it[business]?.filePath }
    }

    fun unregisterBusinessFile(business: String) {
        businessFiles.remove(business)
        routes.forEach { (_, bizMap) -> bizMap.remove(business) }
        mapsDirty.set(true)
    }

    fun registerBusiness(
        packageName: String = defaultPackageName,
        business: String,
        filePath: String,
        defaultIndex: Int = 0,
        defaultPriority: Int = 500,
    ) {
        businessFiles[business] = filePath
        val spec =
            RearWidgetBusinessSpec(packageName, business, filePath, defaultIndex, defaultPriority)
        routes.computeIfAbsent(packageName) { linkedMapOf() }[business] = spec
        mapsDirty.set(true)
    }

    fun registerBusinessWithoutFile(
        packageName: String = defaultPackageName,
        business: String,
        defaultIndex: Int = 0,
        defaultPriority: Int = 500,
    ): Boolean {
        val filePath = getBusinessFile(business) ?: return false
        registerBusiness(packageName, business, filePath, defaultIndex, defaultPriority)
        return true
    }

    fun ensureBusinessRegistered(
        packageName: String = defaultPackageName,
        business: String,
        defaultIndex: Int = 0,
        defaultPriority: Int = 500,
    ): Boolean {
        if (routes[packageName]?.containsKey(business) == true) return true
        return registerBusinessWithoutFile(packageName, business, defaultIndex, defaultPriority)
    }

    fun unregisterBusiness(packageName: String = defaultPackageName, business: String) {
        routes[packageName]?.remove(business)
        mapsDirty.set(true)
    }

    fun replaceSceneRoutes(specs: List<RearWidgetSceneRouteSpec>) {
        synchronized(sceneRouteLock) {
            rebuildSceneRoutes(
                specs.mapNotNull { spec ->
                    normalizeSceneRouteSpec(spec.packageName, spec.scene, spec.business)
                }
            )
        }
        mapsDirty.set(true)
    }

    fun registerSceneRoute(
        packageName: String = defaultPackageName,
        scene: String,
        business: String,
    ) {
        val spec = normalizeSceneRouteSpec(packageName, scene, business) ?: return
        synchronized(sceneRouteLock) {
            val nextRoutes = sceneRoutes.toMutableList()
            val replaceIndex = nextRoutes.indexOfFirst {
                it.packageName == spec.packageName && it.scene == spec.scene
            }
            if (replaceIndex >= 0) {
                nextRoutes[replaceIndex] = spec
            } else {
                nextRoutes.add(spec)
            }
            rebuildSceneRoutes(nextRoutes)
        }
        mapsDirty.set(true)
    }

    fun unregisterSceneRoute(
        packageName: String = defaultPackageName,
        scene: String,
    ) {
        val normalizedPackageName = packageName.trim()
        val normalizedScene = RearWidgetSceneRouteSpec.normalizeScenePattern(scene)
        if (normalizedPackageName.isBlank() || normalizedScene.isBlank()) return
        synchronized(sceneRouteLock) {
            rebuildSceneRoutes(
                sceneRoutes.filterNot {
                    it.packageName == normalizedPackageName && it.scene == normalizedScene
                }
            )
        }
        mapsDirty.set(true)
    }

    fun resolveBusinessForScene(packageName: String, scene: String): String? {
        val normalizedPackageName = packageName.trim()
        val normalizedScene = RearWidgetSceneRouteSpec.normalizeScene(scene)
        if (normalizedPackageName.isBlank() || normalizedScene.isBlank()) return null
        exactSceneRoutesByPackage[normalizedPackageName]?.get(normalizedScene)?.business?.let {
            return it
        }
        return sceneRoutes.firstOrNull {
            it.matchesPackage(normalizedPackageName) && it.matchesScene(scene)
        }?.business
    }

    fun hasSceneRoutePrefix(packageName: String, prefix: String): Boolean {
        val normalizedPackageName = packageName.trim()
        if (normalizedPackageName.isBlank() || prefix.isBlank()) return false
        return sceneRoutes.any {
            it.matchesPackage(normalizedPackageName) && it.hasScenePrefix(
                prefix
            )
        }
    }

    fun hasAnySceneRoutePrefix(prefix: String): Boolean {
        if (prefix.isBlank()) return false
        return sceneRoutes.any { it.hasScenePrefix(prefix) }
    }

    fun hasAnyBusinessForPackage(packageName: String): Boolean {
        return collectMatchedBusinesses(packageName).isNotEmpty()
    }

    fun rememberRoutedNotification(
        packageName: String,
        notificationId: Int,
        notificationKey: String?,
        business: String,
    ): Set<String> {
        val compositeKey = "$packageName:$business:$notificationId"
        val stale = LinkedHashSet<String>()

        val byIdKey = routedNoticeIdentity(packageName, notificationId, null)
        routedNoticeByIdentity.put(byIdKey, compositeKey)
            ?.takeIf { it != compositeKey }
            ?.let(stale::add)

        val byNotificationKey = notificationKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { routedNoticeIdentity(packageName, notificationId, it) }
        if (byNotificationKey != null) {
            routedNoticeByIdentity.put(byNotificationKey, compositeKey)
                ?.takeIf { it != compositeKey }
                ?.let(stale::add)
        }

        return stale
    }

    fun removeRoutedNotification(
        packageName: String,
        notificationId: Int,
        notificationKey: String?,
    ): Set<String> {
        val removed = LinkedHashSet<String>()
        routedNoticeByIdentity.remove(routedNoticeIdentity(packageName, notificationId, null))
            ?.let(removed::add)

        notificationKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { routedNoticeIdentity(packageName, notificationId, it) }
            ?.let { routedNoticeByIdentity.remove(it) }
            ?.let(removed::add)

        return removed
    }

    fun postNotice(
        business: String,
        payload: Bundle = Bundle(),
        options: RearWidgetNoticeOptions = RearWidgetNoticeOptions(),
        packageName: String = defaultPackageName,
    ): RearWidgetNoticeTicket {
        val spec = routes[packageName]?.get(business)
            ?: error("Business not registered: $packageName/$business")
        val merged = options.copy(
            index = options.index ?: spec.defaultIndex,
            priority = options.priority ?: spec.defaultPriority,
        )

        val cardId = payload.getString("__rear_card_id__")?.trim().orEmpty()
        if (cardId.isNotBlank()) {
            val cardKey = cardNoticeKey(packageName, business, cardId)
            val existingComposite = cardNoticeCompositeIndex[cardKey]
            if (!existingComposite.isNullOrBlank()) {
                val existing = notices[existingComposite]
                if (existing != null) {
                    notices[existingComposite] = existing.copy(
                        payload = Bundle(payload),
                        options = merged,
                    )
                    return existing.ticket
                }
                cardNoticeCompositeIndex.remove(cardKey)
            }

            val id = cardNoticeIdIndex.getOrPut(cardKey) { idSeed.incrementAndGet() }
            val key = "$packageName:$business:$id"
            val ticket = RearWidgetNoticeTicket(packageName, business, id, key)
            notices[key] = RearWidgetActiveNotice(ticket, Bundle(payload), merged)
            cardNoticeCompositeIndex[cardKey] = key
            return ticket
        }

        val id = idSeed.incrementAndGet()
        val key = "$packageName:$business:$id"
        val ticket = RearWidgetNoticeTicket(packageName, business, id, key)
        notices[key] = RearWidgetActiveNotice(ticket, Bundle(payload), merged)
        return ticket
    }

    fun updateNotice(
        ticket: RearWidgetNoticeTicket,
        payload: Bundle? = null,
        options: RearWidgetNoticeOptions? = null,
    ) {
        val old = notices[ticket.compositeKey] ?: return
        notices[ticket.compositeKey] = old.copy(
            payload = payload ?: old.payload,
            options = options ?: old.options,
        )
    }

    fun removeNotice(ticket: RearWidgetNoticeTicket) {
        val removed = notices.remove(ticket.compositeKey) ?: return
        val cardId = removed.payload.getString("__rear_card_id__")?.trim().orEmpty()
        if (cardId.isNotBlank()) {
            cardNoticeCompositeIndex.remove(
                cardNoticeKey(
                    ticket.packageName,
                    ticket.business,
                    cardId
                )
            )
        }
    }

    fun disableBusinessDisplay(packageName: String = defaultPackageName, business: String): Int {
        val targets = notices.values
            .filter { it.ticket.packageName == packageName && it.ticket.business == business }
            .map { it.ticket }
        targets.forEach(::removeNotice)
        return targets.size
    }

    /**
     * 按 cardId 精确删除单卡对应的 notice，用于同一业务下存在多张卡、仅关闭其中一张的场景。
     *
     * 业务级 [disableBusinessDisplay] 只能整组清理，无法命中"同业务仍启用其他卡"的单卡关闭。
     * 这里通过 [cardNoticeCompositeIndex] 反查 cardId 对应的 compositeKey 并只删除这一条。
     * cardNoticeIdIndex 刻意保留：若同一 cardId 后续重新启用，可以复用相同的 notificationId，
     * 使 compositeKey 保持稳定，避免 SubScreenCenter 持久化恢复与新注入之间的重复。
     *
     * @return 被删除 notice 的 ticket；cardId 未注册或已不存在时返回 null。
     */
    fun disableCardDisplay(
        packageName: String = defaultPackageName,
        business: String,
        cardId: String,
    ): RearWidgetNoticeTicket? {
        val normalizedCardId = cardId.trim()
        if (normalizedCardId.isBlank()) return null
        val cardKey = cardNoticeKey(packageName, business, normalizedCardId)
        val compositeKey = cardNoticeCompositeIndex.remove(cardKey) ?: return null
        val removed = notices.remove(compositeKey) ?: run {
            cardNoticeCompositeIndex[cardKey] = compositeKey
            return null
        }
        YLog.debug("card display disabled pkg=$packageName biz=$business cardId=$normalizedCardId composite=$compositeKey")
        return removed.ticket
    }

    fun listNotices(): List<RearWidgetActiveNotice> {
        return notices.values.sortedByDescending { it.createdAt }
    }

    fun getNotice(compositeKey: String): RearWidgetActiveNotice? = notices[compositeKey]

    fun buildDecoratedExtras(ticket: RearWidgetNoticeTicket): Bundle {
        val notice = notices[ticket.compositeKey]
            ?: error("Notice not found: ${ticket.compositeKey}")
        val options = notice.options
        val timestamp = notice.createdAt
        return Bundle(notice.payload).apply {
            putString("package_name", ticket.packageName)
            putString("creator_package", ticket.packageName)
            putString("business", ticket.business)
            putInt("index", options.index ?: 0)
            putInt("priority", options.priority ?: 500)
            putInt("notification_id", ticket.notificationId)
            putInt("widget_id", ticket.notificationId)
            putString("composite_key", ticket.compositeKey)
            putLong("timestamp", timestamp)

            putBoolean("disable_popup", options.disablePopup)
            putBoolean("force_popup", options.forcePopup)
            putBoolean("enableFloat", options.enableFloat)
            putBoolean("show_time_tip", options.showTimeTip)
            putBoolean("__x_sticky__", options.sticky)

            if (getString("miui.rear.param").isNullOrBlank()) {
                putString("miui.rear.param", buildRearParamJson(ticket.business, options))
            }
            if (getString("miui.focus.param").isNullOrBlank()) {
                putString("miui.focus.param", buildFocusParamJson(ticket.business, options))
            }
            putString("__xposed_origin__", ticket.packageName)
        }
    }

    fun allPkgBusinesses(): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        val packages = LinkedHashSet<String>().apply {
            addAll(routes.keys)
            sceneRoutes.mapNotNullTo(this) { it.exactPackageNameOrNull() }
        }
        packages.forEach { pkg ->
            val businesses = collectConfiguredBusinesses(pkg)
            if (businesses.isNotEmpty()) out[pkg] = businesses
        }
        return out
    }

    fun primaryBusinessByPkg(): Map<String, String> =
        allPkgBusinesses().mapValues { (_, businesses) -> businesses.firstOrNull().orEmpty() }

    fun allBusinessPath(): Map<String, String> {
        val out = HashMap<String, String>()
        out.putAll(businessFiles)
        routes.values.flatMap { it.values }.forEach { spec -> out[spec.business] = spec.filePath }
        return out
    }

    private fun collectConfiguredBusinesses(packageName: String): LinkedHashSet<String> {
        return LinkedHashSet<String>().apply {
            routes[packageName]?.keys?.forEach(::add)
            sceneRoutes.forEach { spec ->
                if (spec.exactPackageNameOrNull() == packageName) {
                    add(spec.business)
                }
            }
        }
    }

    private fun collectMatchedBusinesses(packageName: String): LinkedHashSet<String> {
        val normalizedPackageName = packageName.trim()
        if (normalizedPackageName.isBlank()) return linkedSetOf()
        return LinkedHashSet<String>().apply {
            routes[normalizedPackageName]?.keys?.forEach(::add)
            sceneRoutes.forEach { spec ->
                if (spec.matchesPackage(normalizedPackageName)) {
                    add(spec.business)
                }
            }
        }
    }

    private fun normalizeSceneRouteSpec(
        packageName: String,
        scene: String,
        business: String,
    ): RearWidgetSceneRouteSpec? {
        val normalizedPackageName = packageName.trim()
        val normalizedScene = RearWidgetSceneRouteSpec.normalizeScenePattern(scene)
        val normalizedBusiness = business.trim()
        if (normalizedPackageName.isBlank() || normalizedScene.isBlank() || normalizedBusiness.isBlank()) {
            return null
        }
        return RearWidgetSceneRouteSpec(normalizedPackageName, normalizedScene, normalizedBusiness)
    }

    private fun rebuildSceneRoutes(specs: List<RearWidgetSceneRouteSpec>) {
        sceneRoutes = specs.toList()
        val indexed = LinkedHashMap<String, LinkedHashMap<String, RearWidgetSceneRouteSpec>>()
        specs.forEach { spec ->
            val exactPackageName = spec.exactPackageNameOrNull() ?: return@forEach
            val exactScene = spec.exactSceneOrNull() ?: return@forEach
            indexed.getOrPut(exactPackageName) { linkedMapOf() }[exactScene] = spec
        }
        exactSceneRoutesByPackage = indexed.mapValues { (_, routes) -> routes.toMap() }
    }

    private fun routedNoticeIdentity(
        packageName: String,
        notificationId: Int,
        notificationKey: String?,
    ): String {
        val key = notificationKey?.trim().orEmpty()
        return if (key.isBlank()) {
            "$packageName:$notificationId"
        } else {
            "$packageName:$notificationId:$key"
        }
    }

    private fun buildRearParamJson(business: String, options: RearWidgetNoticeOptions): String {
        val v1 = JSONObject()
            .put("business", business)
            .put("index", options.index ?: 0)
            .put("priority", options.priority ?: 500)
            .put("disable_popup", options.disablePopup)
            .put("show_time_tip", options.showTimeTip)
            .put("swipe_out_screen_listener", false)
            .put("enableFloat", options.enableFloat)
        return JSONObject().put("rear_param_v1", v1).toString()
    }

    private fun buildFocusParamJson(business: String, options: RearWidgetNoticeOptions): String {
        return JSONObject()
            .put("business", business)
            .put("index", options.index ?: 0)
            .put("priority", options.priority ?: 500)
            .put("disable_popup", options.disablePopup)
            .put("show_time_tip", options.showTimeTip)
            .put("swipe_out_screen_listener", false)
            .put("enableFloat", options.enableFloat)
            .toString()
    }

    private fun cardNoticeKey(packageName: String, business: String, cardId: String): String {
        return "$packageName:$business:$cardId"
    }

    /** 仅供单元测试复位进程内状态，避免不同测试用例互相污染。 */
    internal fun resetForTest() {
        businessFiles.clear()
        routes.clear()
        sceneRoutes = emptyList()
        exactSceneRoutesByPackage = emptyMap()
        routedNoticeByIdentity.clear()
        notices.clear()
        cardNoticeIdIndex.clear()
        cardNoticeCompositeIndex.clear()
        mapsDirty.set(false)
        idSeed.set(310000)
    }
}
