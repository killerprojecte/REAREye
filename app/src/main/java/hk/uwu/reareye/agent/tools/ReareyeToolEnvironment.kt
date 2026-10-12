package hk.uwu.reareye.agent.tools

import android.content.Context
import hk.uwu.reareye.agent.htmlwidget.HtmlWidgetPackage
import hk.uwu.reareye.agent.htmlwidget.WidgetHtmlValidator
import hk.uwu.reareye.agent.htmlwidget.WidgetSpec
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.hook.support.ModuleActivationState
import hk.uwu.reareye.hook.support.XposedModuleStatus
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperCatalog
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperMetadataOptions
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperRepository
import hk.uwu.reareye.repository.rearwidget.RearAppCardRepository
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleCodec
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * The tools Mary may call.
 *
 * Everything runs in the module's own app process with no root, so the blast radius of any single
 * tool is bounded by what REAREye can do as an ordinary application. Nothing here switches
 * displays, kills processes or shells out.
 *
 * ## Write safety
 *
 * Card, component and route storage are **whole-list replacements** over libxposed
 * RemotePreferences, and reads against an unbound service **silently return empty defaults**. The
 * combination is genuinely destructive: read (empty) → append → write back would erase everything
 * the user had configured, with no error anywhere.
 *
 * Every mutation therefore goes through [mutateWithVerification], which refuses to write unless the
 * service is bound, re-reads the authoritative list rather than trusting anything cached, and
 * verifies the result by reading it back.
 */
class ReareyeToolEnvironment(private val context: Context) : ToolEnvironment {

    private val appContext = context.applicationContext
    private val prefs: PrefsManager get() = appContext.getPrefsManager()

    override fun availableTools(): List<ToolSpec> = ALL

    override suspend fun execute(toolName: String, args: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            when (toolName) {
                // --- diagnostics
                GET_MODULE_STATUS -> getModuleStatus()
                LIST_COMPONENTS -> listComponents()
                LIST_CARDS -> listCards()
                LIST_APP_CARDS -> listAppCards(args)
                LIST_SCENE_ROUTES -> listSceneRoutes()
                LIST_WALLPAPERS -> listWallpapers()

                // --- cards
                CREATE_CARD -> createCard(args)
                UPDATE_CARD -> updateCard(args)
                DELETE_CARD -> deleteCard(args)
                SET_CARD_ENABLED -> setCardEnabled(args)

                // --- app cards (应用卡 / 应用) — a different entity from cards
                REGISTER_APP_CARD -> registerAppCard(args)
                RENAME_APP_CARD -> renameAppCard(args)
                DELETE_APP_CARD -> deleteAppCard(args)
                REORDER_APP_CARDS -> reorderAppCards(args)

                // --- generated HTML widgets (create a brand-new component)
                GET_WIDGET_SPEC -> getWidgetSpec(args)
                CREATE_HTML_WIDGET -> createHtmlWidget(args)

                // --- components
                RENAME_COMPONENT -> renameComponent(args)
                DELETE_COMPONENT -> deleteComponent(args)

                // --- scene routes
                REGISTER_SCENE_ROUTE -> registerSceneRoute(args)
                DELETE_SCENE_ROUTE -> deleteSceneRoute(args)

                // --- wallpaper
                SWITCH_WALLPAPER -> switchWallpaper(args)
                SET_WALLPAPER_ROTATION -> setWallpaperRotation(args)
                SET_ROTATION_ENABLED -> setRotationEnabled(args)
                DELETE_WALLPAPER -> deleteWallpaper(args)
                UPDATE_WALLPAPER_METADATA -> updateWallpaperMetadata(args)

                else -> ToolResult.error("未知工具：$toolName")
            }
        }

    // ================================================================ diagnostics

    private fun getModuleStatus(): ToolResult {
        val state = XposedModuleStatus.current()
        val readable = isRemoteReady()

        val explanation = when (state) {
            ModuleActivationState.ACTIVE ->
                "模块正在运行，至少有一个作用域目标进程处于活动状态。"

            ModuleActivationState.NO_RUNNING_TARGET ->
                "模块已启用且作用域已授权，但当前没有目标进程在运行（背屏中心、主题管理或系统界面可能未启动）。"

            ModuleActivationState.SCOPE_NOT_AUTHORIZED ->
                "模块已在框架中启用，但还没有授权任何作用域。需要到 LSPosed 里勾选作用域。"

            ModuleActivationState.SERVICE_UNAVAILABLE ->
                "无法连接到 libxposed 服务：模块可能没有在 LSPosed 中启用，或当前框架不支持。"
        }

        return ToolResult.ok(
            json(
                "activation_state" to state.name,
                "module_active" to (state == ModuleActivationState.ACTIVE),
                "config_readable" to readable,
                "explanation" to explanation,
            ),
        )
    }

    private fun listComponents(): ToolResult {
        requireReadable()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        val cards = RearWidgetManagerRepository.loadCards(prefs)

        return ToolResult.ok(
            buildJsonObject {
                put("count", components.size)
                put("components", buildJsonArray {
                    components.forEach { component ->
                        add(buildJsonObject {
                            put("business", component.business)
                            put("package_name", component.packageName)
                            put("template_path", component.filePath)
                            put("default_priority", component.defaultPriority)
                            put("renameable", component.renameable)
                            put("from_store", component.downloadedFromStore)
                            component.storeWidgetName?.let { put("store_widget", it) }
                            put("used_by_cards", buildJsonArray {
                                cards.filter { it.business == component.business }
                                    .forEach { add(JsonPrimitive(it.title)) }
                            })
                        })
                    }
                })
            }.toString(),
        )
    }

    private fun listCards(): ToolResult {
        requireReadable()?.let { return it }
        val cards = RearWidgetManagerRepository.loadCards(prefs)

        return ToolResult.ok(
            buildJsonObject {
                put("count", cards.size)
                put("cards", buildJsonArray {
                    cards.forEach { card ->
                        add(buildJsonObject {
                            put("id", card.id)
                            put("title", card.title)
                            put("package_name", card.packageName)
                            put("business", card.business)
                            put("enabled", card.enabled)
                            put("sticky", card.sticky)
                            put("priority", card.priority)
                            put("renameable", card.renameable)
                            put("from_store", card.downloadedFromStore)
                            put("has_template_values", !card.oneConfigJson.isNullOrBlank())
                        })
                    }
                })
            }.toString(),
        )
    }

    /**
     * Application cards ("应用卡") — the entries a user calls "背屏应用".
     *
     * These are **not** cards. Both are derived from a component, but an app card is a launcher
     * entry addressed by `appId`, whereas a card is display content addressed by its own id. They
     * live behind different APIs (`RearAppApiClient` vs the remote-preferences widget store), which
     * is why [requireReadable] is checked here anyway: the app-card service is reached through the
     * same host process, so an unbound host means the catalog is unavailable too.
     */
    private fun listAppCards(args: JsonObject): ToolResult {
        requireReadable()?.let { return it }
        val all = RearAppCardRepository.loadCatalog(appContext)

        // Paged and pruned deliberately. A full catalog of a few dozen entries is several thousand
        // characters of JSON, which lands right on the maxToolResultChars ceiling; going over makes
        // the engine head/tail-truncate it and the model then receives broken JSON, which reads as
        // "the interface returned truncated data". Keeping default pages small avoids that, and the
        // field pruning roughly halves the size again.
        val limit = (args.int("limit") ?: DEFAULT_APP_CARD_PAGE_SIZE).coerceIn(1, 100)
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val page = all.drop(offset).take(limit)

        return ToolResult.ok(
            buildJsonObject {
                put("total", all.size)
                put("offset", offset)
                put("count", page.size)
                val next = offset + page.size
                put("has_more", next < all.size)
                if (next < all.size) put("next_offset", next)
                put(
                    "note",
                    "这些是应用卡（应用），不是卡片，也不是组件。" +
                        if (next < all.size) "还有更多，用 offset=$next 继续读取。" else "",
                )
                put("app_cards", buildJsonArray {
                    page.forEach { card ->
                        add(buildJsonObject {
                            put("app_id", card.appId)
                            put("title", card.title)
                            card.componentBusiness?.let { put("component_business", it) }
                            card.bindPackage?.let { put("bind_package", it) }
                            // Flags are emitted only when they are actionable, so a healthy card
                            // stays short instead of carrying four `false`s.
                            if (card.canRename) put("can_rename", true)
                            if (card.canDelete) put("can_delete", true)
                            if (!card.ownedByRearEye) put("owned_by_reareye", false)
                        })
                    }
                })
            }.toString(),
        )
    }

    // ================================================================ app cards
    //
    // App cards run over a different channel from cards (RearAppApiClient, not the remote-preference
    // widget store), and their operations report success explicitly instead of silently. They still
    // go through [mutateWithVerification] so the same rule holds everywhere: the read-back decides,
    // never the return value of the write.

    private fun registerAppCard(args: JsonObject): ToolResult {
        val title = args.string("title")?.trim().orEmpty()
        val business = args.string("component_business")?.trim().orEmpty()
        if (title.isEmpty()) return invalidArgs("title 是必填的。")
        if (business.isEmpty()) {
            return invalidArgs("component_business 是必填的，先用 list_components 获取组件标识。")
        }

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        if (components.none { it.business == business }) {
            return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "组件 '$business' 不存在。应用卡必须由已注册的组件派生。",
                    "recovery" to "先调用 list_components 获取可用的 business 值，不要凭空编造。",
                ),
            )
        }

        var appId: String? = null
        return mutateWithVerification(
            action = "register_app_card",
            mutate = {
                val result = RearAppCardRepository.register(appContext, prefs, title, business)
                if (!result.success) {
                    error(result.error ?: "注册应用卡失败，模块没有说明原因。")
                }
                appId = result.appId
            },
            readBack = { RearAppCardRepository.loadCatalog(appContext) },
            // Identified by title+component rather than appId: the id is only known after the call,
            // and matching the pair is what proves the card the user asked for actually exists.
            verify = { saved ->
                val id = appId
                if (id != null) {
                    saved.any { it.appId == id }
                } else {
                    saved.any { it.title == title && it.componentBusiness == business }
                }
            },
            detail = buildMap {
                put("title", title)
                put("component_business", business)
                appId?.let { put("app_id", it) }
            },
        )
    }

    private fun renameAppCard(args: JsonObject): ToolResult {
        val appId = args.string("app_id")?.trim().orEmpty()
        val title = args.string("title")?.trim().orEmpty()
        if (appId.isEmpty()) return invalidArgs("app_id 是必填的，先用 list_app_cards 获取。")
        if (title.isEmpty()) return invalidArgs("title 是必填的。")

        writeGuard()?.let { return it }
        val existing = RearAppCardRepository.loadCatalog(appContext).firstOrNull { it.appId == appId }
            ?: return ToolResult.error(
                json(
                    "error" to "app_card_not_found",
                    "detail" to "找不到 app_id 为 '$appId' 的应用卡。",
                    "recovery" to "先调用 list_app_cards 获取正确的 app_id。",
                ),
            )

        if (!existing.canRename) {
            return ToolResult.error(
                json(
                    "error" to "not_renameable",
                    "detail" to "应用卡 '${existing.title}' 不允许改名（can_rename=false）。",
                    "recovery" to "直接告知用户这一张改不了，不要重试。",
                ),
            )
        }

        return mutateWithVerification(
            action = "rename_app_card",
            mutate = { _ ->
                val result = RearAppCardRepository.rename(appContext, appId, title)
                if (!result.success) error(result.error ?: "改名失败，模块没有说明原因。")
            },
            readBack = { RearAppCardRepository.loadCatalog(appContext) },
            verify = { saved -> saved.any { it.appId == appId && it.title == title } },
            detail = mapOf("app_id" to appId, "title" to title),
        )
    }

    private fun deleteAppCard(args: JsonObject): ToolResult {
        val appId = args.string("app_id")?.trim().orEmpty()
        if (appId.isEmpty()) return invalidArgs("app_id 是必填的，先用 list_app_cards 获取。")

        writeGuard()?.let { return it }
        val existing = RearAppCardRepository.loadCatalog(appContext).firstOrNull { it.appId == appId }
            ?: return ToolResult.error(
                json(
                    "error" to "app_card_not_found",
                    "detail" to "找不到 app_id 为 '$appId' 的应用卡。",
                    "recovery" to "先调用 list_app_cards 获取正确的 app_id。",
                ),
            )

        if (!existing.canDelete) {
            return ToolResult.error(
                json(
                    "error" to "not_deletable",
                    "detail" to "应用卡 '${existing.title}' 不允许删除（can_delete=false）。",
                    "recovery" to "直接告知用户这一张删不了，不要重试。",
                ),
            )
        }

        return mutateWithVerification(
            action = "delete_app_card",
            mutate = { _ ->
                val result = RearAppCardRepository.delete(appContext, appId)
                if (!result.success) error(result.error ?: "删除失败，模块没有说明原因。")
            },
            readBack = { RearAppCardRepository.loadCatalog(appContext) },
            verify = { saved -> saved.none { it.appId == appId } },
            detail = mapOf("app_id" to appId, "title" to existing.title),
        )
    }

    private fun reorderAppCards(args: JsonObject): ToolResult {
        val ids = args.stringArray("app_ids").map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            return invalidArgs("app_ids 是必填的，先用 list_app_cards 获取全部 app_id。")
        }
        if (ids.size != ids.toSet().size) {
            return invalidArgs("app_ids 里有重复项，每个应用卡只能出现一次。")
        }

        writeGuard()?.let { return it }
        val catalog = RearAppCardRepository.loadCatalog(appContext)
        // The module reorders by the full list, so a partial list would silently drop the rest.
        val missing = ids.filter { id -> catalog.none { it.appId == id } }
        if (missing.isNotEmpty()) {
            return ToolResult.error(
                json(
                    "error" to "app_card_not_found",
                    "detail" to "这些 app_id 不存在：${missing.joinToString()}",
                    "recovery" to "先调用 list_app_cards 获取正确的 app_id。",
                ),
            )
        }
        if (ids.size != catalog.size) {
            return ToolResult.error(
                json(
                    "error" to "incomplete_order",
                    "detail" to "只提供了 ${ids.size} 个 app_id，但当前共有 ${catalog.size} 张应用卡；" +
                        "顺序必须包含全部应用卡，否则其余会被丢掉。",
                    "recovery" to "先用 list_app_cards 取出全部 app_id，按期望顺序完整传入。",
                ),
            )
        }

        return mutateWithVerification(
            action = "reorder_app_cards",
            mutate = { _ ->
                val result = RearAppCardRepository.reorder(appContext, ids)
                if (!result.success) error(result.error ?: "调整顺序失败，模块没有说明原因。")
            },
            readBack = { RearAppCardRepository.loadCatalog(appContext) },
            verify = { saved -> saved.map { it.appId } == ids },
            detail = mapOf("order" to ids.joinToString()),
        )
    }

    // ================================================================ html widget generation

    /**
     * Registers a generated HTML widget as a component, optionally as an app card too.
     *
     * The pipeline is: validate the model's HTML against the specification, write the zip (fixed
     * manifest + `web/index.html`), add it to the component list, then optionally register an app
     * card that reuses that component's template.
     *
     * Validation runs *before* anything is written. A widget that violates the safe zone or the
     * touch-target minimums would install as a blank or unusable panel, and the failure would only
     * surface once it was already on the rear screen — so the report is returned to the model to
     * revise instead.
     */
    /**
     * Returns the generation guideline with this request's parameters substituted.
     *
     * The guideline text itself is never rewritten here — [WidgetSpec] holds it verbatim and only the
     * parameter values are replaced.
     */
    private fun getWidgetSpec(args: JsonObject): ToolResult {
        val appName = args.string("app_name")?.trim().orEmpty()
        val content = args.string("content")?.trim().orEmpty()
        if (content.isEmpty()) {
            return invalidArgs(
                "content 是必填的：请把用户想要的功能、状态机、视觉规格和文案写清楚，" +
                    "它会填进规范里的 CONTENT 参数区。",
            )
        }

        val baseWidth = args.int("base_width") ?: DEFAULT_BASE_WIDTH
        val baseHeight = args.int("base_height") ?: DEFAULT_BASE_HEIGHT
        val prompt = WidgetSpec.buildPrompt(
            appName = appName.ifEmpty { "未命名" },
            baseWidth = baseWidth,
            baseHeight = baseHeight,
            content = content,
        )

        return ToolResult.ok(
            buildJsonObject {
                put("base", "${baseWidth}x$baseHeight")
                put("frame_rate", WidgetSpec.FRAME_RATE.toString())
                put(
                    "usage",
                    "这就是本次生成要遵循的完整规范（已填好参数）。请严格按它写出单文件 HTML，" +
                        "然后调用 create_html_widget 提交。规范中标注违反=废稿的条目会被程序校验，" +
                        "校验不通过不会创建任何东西。",
                )
                put("spec", prompt)
            }.toString(),
        )
    }

    private fun createHtmlWidget(args: JsonObject): ToolResult {        val html = args.string("html").orEmpty()
        if (html.isBlank()) {
            return invalidArgs("html 是必填的：请把完整单文件 HTML 放在这个参数里。")
        }

        val baseWidth = args.int("base_width") ?: DEFAULT_BASE_WIDTH
        val baseHeight = args.int("base_height") ?: DEFAULT_BASE_HEIGHT
        if (baseWidth <= baseHeight) {
            return invalidArgs(
                "base_width/base_height 写反了：背屏是横屏，必须 BASE_W > BASE_H（收到 " +
                    "$baseWidth×$baseHeight）。",
            )
        }

        val validation = WidgetHtmlValidator.validate(html, baseWidth, baseHeight)
        if (!validation.passed) {
            return ToolResult.error(
                json(
                    "error" to "widget_validation_failed",
                    "detail" to validation.report(),
                    "recovery" to
                        "按上面的问题逐条修改 HTML 后重新调用本工具。不要忽略任何一条 ERROR；" +
                        "尤其注意头注释里的数字必须与 LAYOUT 实算一致。",
                    "error_count" to validation.errors.size.toString(),
                ),
            )
        }

        writeGuard()?.let { return it }

        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "生成的组件"
        val requestedBusiness = args.string("business")?.trim()?.takeIf { it.isNotEmpty() }
        val existing = RearWidgetManagerRepository.loadBusinesses(prefs)
        val taken = existing.mapTo(mutableSetOf()) { it.business }

        val business = HtmlWidgetPackage.uniqueBusiness(
            desired = requestedBusiness
                ?: HtmlWidgetPackage.deriveBusiness(title, fallback = "html_widget"),
            taken = taken,
        )
        val renamed = requestedBusiness != null && business != requestedBusiness

        // Staged outside the repository's managed directory so its copy-and-rename step runs and the
        // file lands under the expected `{business}_{timestamp}.zip` name.
        val stagingDir = File(appContext.filesDir, "agent_widget_staging").apply { mkdirs() }
        val zipFile = File(stagingDir, "${business}_${System.currentTimeMillis()}.zip")

        val component = hk.uwu.reareye.repository.rearwidget.RearBusinessConfig(
            id = UUID.randomUUID().toString(),
            packageName = appContext.packageName,
            business = business,
            filePath = zipFile.absolutePath,
            renameable = true,
        )

        val registerResult = mutateWithVerification(
            action = "create_html_widget",
            mutate = {
                HtmlWidgetPackage.writeZip(zipFile, html)
                RearWidgetManagerRepository.saveBusinesses(
                    appContext,
                    prefs,
                    existing + component,
                )
            },
            readBack = { RearWidgetManagerRepository.loadBusinesses(prefs) },
            // Confirmed by business name: `saveBusinesses` swallows API failures internally, so the
            // only trustworthy signal is the component being present on read-back.
            verify = { saved -> saved.any { it.business == business } },
            detail = buildMap {
                put("business", business)
                put("title", title)
                put("base", "${baseWidth}x$baseHeight")
                put("warnings", validation.warnings.size.toString())
            },
        )
        if (registerResult.isError) return registerResult

        if (!renamed) return registerResult

        // Tell the model the id it wanted was taken, so it can report the final name accurately.
        return ToolResult.ok(
            json(
                "ok" to "true",
                "action" to "create_html_widget",
                "business" to business,
                "title" to title,
                "note" to "组件名为 $business（你指定的 $requestedBusiness 已存在，自动加了后缀）。",
            ),
        )
    }

    private fun listSceneRoutes(): ToolResult {
        requireReadable()?.let { return it }
        val routes = RearWidgetManagerRepository.loadSceneRoutes(prefs)
        return ToolResult.ok(
            buildJsonObject {
                put("count", routes.size)
                put("routes", buildJsonArray {
                    routes.forEach { route ->
                        add(buildJsonObject {
                            put("id", route.id)
                            put("package_name", route.packageName)
                            put("scene", route.scene)
                            put("business", route.business)
                            put("from_store", route.downloadedFromStore)
                        })
                    }
                })
            }.toString(),
        )
    }

    private suspend fun listWallpapers(): ToolResult {
        requireReadable()?.let { return it }

        val catalog: RearWallpaperCatalog = RearWallpaperRepository.loadCatalog(appContext)
        val schedule = RearWallpaperRepository.loadSchedule(prefs)
        val rotationEnabled = RearWallpaperRepository.isScheduleEnabled(prefs)
        val inRotation = schedule.map { it.wallpaperId }.toSet()

        return ToolResult.ok(
            buildJsonObject {
                put("count", catalog.wallpapers.size)
                put("current_wallpaper_id", catalog.currentWallpaperId ?: -1)
                put("rotation_enabled", rotationEnabled)
                put("rotation_count", schedule.size)
                put("wallpapers", buildJsonArray {
                    catalog.wallpapers.forEach { wallpaper ->
                        add(buildJsonObject {
                            put("wallpaper_id", wallpaper.wallpaperId)
                            put("title", wallpaper.title)
                            put("name", wallpaper.name)
                            put("is_current", wallpaper.wallpaperId == catalog.currentWallpaperId)
                            put("in_rotation", wallpaper.wallpaperId in inRotation)
                            put("imported", wallpaper.imported)
                            put("can_edit_metadata", wallpaper.canEditMetadata)
                            put("can_delete", wallpaper.canDelete)
                            put("has_template_config", wallpaper.templateConfigAvailable)
                            if (wallpaper.author.isNotBlank()) put("author", wallpaper.author)
                        })
                    }
                })
                put("rotation", buildJsonArray {
                    schedule.forEach { entry ->
                        add(buildJsonObject {
                            put("wallpaper_id", entry.wallpaperId)
                            put("interval_ms", entry.delayMs)
                        })
                    }
                })
            }.toString(),
        )
    }

    // ===================================================================== cards

    private fun createCard(args: JsonObject): ToolResult {
        val title = args.string("title")?.trim().orEmpty()
        val business = args.string("business")?.trim().orEmpty()
        if (title.isEmpty() || business.isEmpty()) {
            return invalidArgs("title 和 business 都是必填的。")
        }

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        val component = components.firstOrNull { it.business == business }
            ?: return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "组件 '$business' 没有注册。",
                    "recovery" to "先调用 list_components 查看可用组件，再用其中的 business 值重试。",
                ),
            )

        val cards = RearWidgetManagerRepository.loadCards(prefs)
        if (cards.any { it.business == business && it.title == title }) {
            return ToolResult.error(
                json(
                    "error" to "duplicate_card",
                    "detail" to "已经存在一张标题为 '$title' 且使用组件 '$business' 的卡片。",
                    "recovery" to "换一个标题，或用 update_card 修改现有卡片。",
                ),
            )
        }

        val card = hk.uwu.reareye.repository.rearwidget.RearCardConfig(
            id = UUID.randomUUID().toString(),
            title = title,
            packageName = args.string("package_name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: component.packageName,
            business = business,
            enabled = args.boolean("enabled") ?: true,
            sticky = args.boolean("sticky") ?: true,
            priority = args.int("priority") ?: component.defaultPriority,
        )

        return mutateWithVerification(
            action = "create_card",
            mutate = { current ->
                RearWidgetManagerRepository.saveCards(appContext, prefs, current + card)
            },
            readBack = { RearWidgetManagerRepository.loadCards(prefs) },
            // Confirm by id: `saveCards` swallows every API failure internally, so "no exception"
            // must never be treated as success.
            verify = { saved -> saved.any { it.id == card.id } },
            detail = mapOf("card_id" to card.id, "title" to title, "business" to business),
        )
    }

    private fun updateCard(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return invalidArgs("id 是必填的，先用 list_cards 获取。")

        writeGuard()?.let { return it }
        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val existing = cards.firstOrNull { it.id == id }
            ?: return ToolResult.error(
                json(
                    "error" to "card_not_found",
                    "detail" to "找不到 id 为 '$id' 的卡片。",
                    "recovery" to "先调用 list_cards 获取正确的 id。",
                ),
            )

        val changes = buildList {
            args.string("title")?.trim()?.takeIf { it.isNotEmpty() }?.let { add("标题改为 $it") }
            args.boolean("enabled")?.let { add(if (it) "启用" else "停用") }
            args.boolean("sticky")?.let { add(if (it) "常驻显示" else "取消常驻") }
            args.int("priority")?.let { add("优先级改为 $it") }
        }
        if (changes.isEmpty()) {
            return invalidArgs("没有提供任何要修改的字段（title / enabled / sticky / priority）。")
        }

        // Changing a store-installed card requires bypassing the locked merge, which is the same
        // escape hatch the store installer uses. Never do that silently.
        val touchesLocked = !existing.renameable
        val confirmed = args.boolean("confirm_locked_edit") ?: false
        if (touchesLocked && !confirmed) {
            return ToolResult.error(
                json(
                    "error" to "locked_card",
                    "detail" to "卡片 '${existing.title}' 由组件仓库安装，默认受保护、不允许直接修改。",
                    "recovery" to "先向用户说明这会修改仓库安装的卡片；用户同意后，带 confirm_locked_edit=true 重试。",
                ),
            )
        }

        val updated = existing.copy(
            title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: existing.title,
            enabled = args.boolean("enabled") ?: existing.enabled,
            sticky = args.boolean("sticky") ?: existing.sticky,
            priority = args.int("priority") ?: existing.priority,
        )

        return mutateWithVerification(
            action = "update_card",
            mutate = { current ->
                val next = current.map { if (it.id == id) updated else it }
                RearWidgetManagerRepository.saveCards(
                    appContext,
                    prefs,
                    next,
                    allowLockedEdits = touchesLocked,
                )
            },
            readBack = { RearWidgetManagerRepository.loadCards(prefs) },
            verify = { saved ->
                val after = saved.firstOrNull { it.id == id }
                after != null &&
                    after.title == updated.title &&
                    after.enabled == updated.enabled &&
                    after.priority == updated.priority
            },
            detail = mapOf("card_id" to id, "changes" to changes.joinToString("、")),
        )
    }

    private fun deleteCard(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return invalidArgs("id 是必填的，先用 list_cards 获取。")

        writeGuard()?.let { return it }
        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val existing = cards.firstOrNull { it.id == id }
            ?: return ToolResult.error(
                json("error" to "card_not_found", "detail" to "找不到 id 为 '$id' 的卡片。"),
            )

        return mutateWithVerification(
            action = "delete_card",
            mutate = { current ->
                RearWidgetManagerRepository.saveCards(
                    appContext,
                    prefs,
                    current.filterNot { it.id == id },
                )
            },
            readBack = { RearWidgetManagerRepository.loadCards(prefs) },
            verify = { saved -> saved.none { it.id == id } },
            detail = mapOf("card_id" to id, "title" to existing.title),
        )
    }

    private fun setCardEnabled(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        val enabled = args.boolean("enabled")
        if (id.isEmpty() || enabled == null) return invalidArgs("id 和 enabled 都是必填的。")

        writeGuard()?.let { return it }
        val before = RearWidgetManagerRepository.loadCards(prefs).firstOrNull { it.id == id }
            ?: return ToolResult.error(
                json("error" to "card_not_found", "detail" to "找不到 id 为 '$id' 的卡片。"),
            )
        if (before.enabled == enabled) {
            return ToolResult.ok(
                json("no_change" to true, "detail" to "卡片 '${before.title}' 已经是该状态。"),
            )
        }

        RearWidgetManagerRepository.setCardEnabled(appContext, prefs, id, enabled)
        val after = RearWidgetManagerRepository.loadCards(prefs).firstOrNull { it.id == id }
        return if (after?.enabled == enabled) {
            ToolResult.ok(
                json("card_id" to id, "title" to after.title, "enabled" to enabled),
            )
        } else {
            ToolResult.error(
                json(
                    "error" to "verify_failed",
                    "detail" to "已请求${if (enabled) "启用" else "停用"}卡片，但回读状态没有变化。",
                    "recovery" to "可能是该卡片被锁定（renameable=false）。用 list_cards 确认后告知用户。",
                ),
            )
        }
    }

    // ================================================================ components

    private fun renameComponent(args: JsonObject): ToolResult {
        val from = args.string("business")?.trim().orEmpty()
        val to = args.string("new_business")?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) return invalidArgs("business 和 new_business 都是必填的。")

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        val existing = components.firstOrNull { it.business == from }
            ?: return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "组件 '$from' 不存在。",
                    "recovery" to "先调用 list_components。",
                ),
            )
        if (components.any { it.business == to }) {
            return ToolResult.error(
                json("error" to "duplicate_component", "detail" to "组件 '$to' 已经存在。"),
            )
        }
        if (!existing.renameable && !(args.boolean("confirm_locked_edit") ?: false)) {
            return ToolResult.error(
                json(
                    "error" to "locked_component",
                    "detail" to "组件 '$from' 由仓库安装，默认受保护。",
                    "recovery" to "征得用户同意后，带 confirm_locked_edit=true 重试。",
                ),
            )
        }

        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val affected = cards.filter { it.business == from }

        return mutateWithVerification(
            action = "rename_component",
            mutate = { current ->
                RearWidgetManagerRepository.saveBusinesses(
                    appContext,
                    prefs,
                    current.map { if (it.business == from) it.copy(business = to) else it },
                    allowLockedEdits = !existing.renameable,
                )
                // A card points at its component by name, so renaming the component orphans every
                // card that referenced it unless they are updated in the same operation.
                if (affected.isNotEmpty()) {
                    val cardsNow = RearWidgetManagerRepository.loadCards(prefs)
                    RearWidgetManagerRepository.saveCards(
                        appContext,
                        prefs,
                        cardsNow.map { if (it.business == from) it.copy(business = to) else it },
                    )
                }
            },
            readBack = { RearWidgetManagerRepository.loadBusinesses(prefs) },
            verify = { saved ->
                saved.any { it.business == to } && saved.none { it.business == from }
            },
            detail = mapOf(
                "from" to from,
                "to" to to,
                "cards_updated" to affected.size.toString(),
            ),
        )
    }

    private fun deleteComponent(args: JsonObject): ToolResult {
        val business = args.string("business")?.trim().orEmpty()
        if (business.isEmpty()) return invalidArgs("business 是必填的。")

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        if (components.none { it.business == business }) {
            return ToolResult.error(
                json("error" to "component_not_found", "detail" to "组件 '$business' 不存在。"),
            )
        }

        val dependent = RearWidgetManagerRepository.loadCards(prefs).filter { it.business == business }
        if (dependent.isNotEmpty() && !(args.boolean("confirm_orphan_cards") ?: false)) {
            return ToolResult.error(
                json(
                    "error" to "component_in_use",
                    "detail" to "还有 ${dependent.size} 张卡片在用这个组件：${
                        dependent.joinToString("、") { it.title }
                    }。",
                    "recovery" to "先告知用户这些卡片会失效；用户同意后带 confirm_orphan_cards=true 重试，或先删除相关卡片。",
                ),
            )
        }

        return mutateWithVerification(
            action = "delete_component",
            mutate = { current ->
                RearWidgetManagerRepository.saveBusinesses(
                    appContext,
                    prefs,
                    current.filterNot { it.business == business },
                )
            },
            readBack = { RearWidgetManagerRepository.loadBusinesses(prefs) },
            verify = { saved -> saved.none { it.business == business } },
            detail = mapOf("business" to business, "orphaned_cards" to dependent.size.toString()),
        )
    }

    // ============================================================= scene routes

    private fun registerSceneRoute(args: JsonObject): ToolResult {
        val packageName = args.string("package_name")?.trim().orEmpty()
        val scene = args.string("scene")?.trim().orEmpty()
        val business = args.string("business")?.trim().orEmpty()
        if (packageName.isEmpty() || scene.isEmpty() || business.isEmpty()) {
            return invalidArgs("package_name、scene、business 都是必填的。")
        }

        writeGuard()?.let { return it }
        if (RearWidgetManagerRepository.loadBusinesses(prefs).none { it.business == business }) {
            return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "目标组件 '$business' 没有注册。",
                    "recovery" to "先调用 list_components。",
                ),
            )
        }

        // A malformed pattern is accepted and persisted by the module, then silently never matches.
        val regexError = validateScenePattern(scene)
        if (regexError != null) return invalidArgs(regexError)

        val routes = RearWidgetManagerRepository.loadSceneRoutes(prefs)
        val route = hk.uwu.reareye.repository.rearwidget.RearWidgetSceneRouteConfig(
            id = "$packageName::$scene::$business",
            packageName = packageName,
            scene = scene,
            business = business,
        )

        return mutateWithVerification(
            action = "register_scene_route",
            mutate = { current ->
                RearWidgetManagerRepository.saveSceneRoutes(
                    appContext,
                    prefs,
                    current.filterNot { it.id == route.id } + route,
                )
            },
            readBack = { RearWidgetManagerRepository.loadSceneRoutes(prefs) },
            verify = { saved -> saved.any { it.id == route.id } },
            detail = mapOf("package_name" to packageName, "scene" to scene, "business" to business),
        )
    }

    private fun deleteSceneRoute(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return invalidArgs("id 是必填的，先用 list_scene_routes 获取。")

        writeGuard()?.let { return it }
        val routes = RearWidgetManagerRepository.loadSceneRoutes(prefs)
        if (routes.none { it.id == id }) {
            return ToolResult.error(
                json(
                    "error" to "route_not_found",
                    "detail" to "找不到 id 为 '$id' 的场景路由。",
                    "recovery" to "先调用 list_scene_routes。",
                ),
            )
        }

        return mutateWithVerification(
            action = "delete_scene_route",
            mutate = { current ->
                RearWidgetManagerRepository.saveSceneRoutes(
                    appContext,
                    prefs,
                    current.filterNot { it.id == id },
                )
            },
            readBack = { RearWidgetManagerRepository.loadSceneRoutes(prefs) },
            verify = { saved -> saved.none { it.id == id } },
            detail = mapOf("id" to id),
        )
    }

    // ================================================================ wallpaper

    private suspend fun switchWallpaper(args: JsonObject): ToolResult {
        val id = args.int("wallpaper_id")
            ?: return invalidArgs("wallpaper_id 是必填的，先用 list_wallpapers 获取。")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        val target = catalog.wallpapers.firstOrNull { it.wallpaperId == id }
            ?: return ToolResult.error(
                json(
                    "error" to "wallpaper_not_found",
                    "detail" to "找不到 wallpaper_id=$id 的壁纸。",
                    "recovery" to "先调用 list_wallpapers 获取有效的 id。",
                ),
            )

        val requested = RearWallpaperRepository.switchWallpaper(appContext, id)
        return if (requested) {
            ToolResult.ok(
                json(
                    "wallpaper_id" to id,
                    "title" to target.name,
                    "note" to "已发送切换请求。注意：主屏处于编辑模式时切换会被系统忽略，而接口仍报告成功，因此请以用户实际观察到的结果为准。",
                ),
            )
        } else {
            ToolResult.error(
                json(
                    "error" to "switch_failed",
                    "detail" to "切换壁纸的请求未被接受。",
                    "recovery" to "可能是背屏中心（com.xiaomi.subscreencenter）没有运行，可先调用 get_module_status。",
                ),
            )
        }
    }

    private suspend fun setWallpaperRotation(args: JsonObject): ToolResult {
        val id = args.int("wallpaper_id")
            ?: return invalidArgs("wallpaper_id 是必填的。")
        val inRotation = args.boolean("in_rotation")
            ?: return invalidArgs("in_rotation 是必填的（true 加入轮播 / false 移出）。")
        val intervalMs = args.int("interval_ms")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        if (catalog.wallpapers.none { it.wallpaperId == id }) {
            return ToolResult.error(
                json("error" to "wallpaper_not_found", "detail" to "找不到 wallpaper_id=$id 的壁纸。"),
            )
        }

        val current = RearWallpaperRepository.loadSchedule(prefs)
        val next = if (inRotation) {
            val base = current.filterNot { it.wallpaperId == id }
            // Keep the array order: it *is* the rotation order.
            base + RearWallpaperScheduleEntry(
                wallpaperId = id,
                // The codec clamps to MIN_DELAY_MS; mirror its default so a caller may omit it.
                delayMs = intervalMs?.toLong() ?: RearWallpaperScheduleCodec.DEFAULT_DELAY_MS,
            )
        } else {
            current.filterNot { it.wallpaperId == id }
        }

        RearWallpaperRepository.saveSchedule(prefs, next)
        val enabled = RearWallpaperRepository.isScheduleEnabled(prefs) && next.isNotEmpty()
        RearWallpaperRepository.setScheduleEnabled(prefs, enabled)
        RearWallpaperRepository.syncSchedule(appContext, enabled, next)

        val saved = RearWallpaperRepository.loadSchedule(prefs)
        val ok = saved.any { it.wallpaperId == id } == inRotation
        return if (ok) {
            ToolResult.ok(
                json(
                    "wallpaper_id" to id,
                    "in_rotation" to inRotation,
                    "rotation_count" to saved.size.toString(),
                    "rotation_enabled" to enabled,
                ),
            )
        } else {
            ToolResult.error(
                json(
                    "error" to "verify_failed",
                    "detail" to "轮播列表修改后回读结果不一致。",
                ),
            )
        }
    }

    private suspend fun setRotationEnabled(args: JsonObject): ToolResult {
        val enabled = args.boolean("enabled") ?: return invalidArgs("enabled 是必填的。")
        writeGuard()?.let { return it }

        val schedule = RearWallpaperRepository.loadSchedule(prefs)
        if (enabled && schedule.isEmpty()) {
            return ToolResult.error(
                json(
                    "error" to "empty_rotation",
                    "detail" to "轮播列表是空的，不能开启轮播。",
                    "recovery" to "先用 set_wallpaper_rotation 把至少一张壁纸加入轮播。",
                ),
            )
        }

        RearWallpaperRepository.setScheduleEnabled(prefs, enabled)
        RearWallpaperRepository.syncSchedule(appContext, enabled, schedule)

        val actual = RearWallpaperRepository.isScheduleEnabled(prefs)
        return if (actual == enabled) {
            ToolResult.ok(json("rotation_enabled" to actual, "rotation_count" to schedule.size.toString()))
        } else {
            ToolResult.error(json("error" to "verify_failed", "detail" to "轮播开关回读不一致。"))
        }
    }

    private suspend fun deleteWallpaper(args: JsonObject): ToolResult {
        val id = args.int("wallpaper_id") ?: return invalidArgs("wallpaper_id 是必填的。")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        val target = catalog.wallpapers.firstOrNull { it.wallpaperId == id }
            ?: return ToolResult.error(
                json("error" to "wallpaper_not_found", "detail" to "找不到 wallpaper_id=$id 的壁纸。"),
            )

        // `imported` is the only gate the module enforces for delete; checking it here turns an
        // opaque host-side refusal into an explanation the model can act on.
        if (!target.imported) {
            return ToolResult.error(
                json(
                    "error" to "not_imported",
                    "detail" to "壁纸 '${target.name}' 不是 REAREye 导入或仓库安装的，模块不允许删除系统自带壁纸。",
                    "recovery" to "告知用户这张壁纸不能删除，不要重试。",
                ),
            )
        }

        val result = RearWallpaperRepository.deleteWallpaper(appContext, id)
        if (!result.success) {
            return ToolResult.error(
                json(
                    "error" to "delete_failed",
                    "detail" to (result.error ?: "模块拒绝了删除请求。"),
                ),
            )
        }

        // Keep the rotation list consistent with the catalog.
        val schedule = RearWallpaperRepository.loadSchedule(prefs)
        if (schedule.any { it.wallpaperId == id }) {
            val next = schedule.filterNot { it.wallpaperId == id }
            RearWallpaperRepository.saveSchedule(prefs, next)
            val enabled = RearWallpaperRepository.isScheduleEnabled(prefs) && next.isNotEmpty()
            RearWallpaperRepository.setScheduleEnabled(prefs, enabled)
            RearWallpaperRepository.syncSchedule(appContext, enabled, next)
        }

        return ToolResult.ok(
            json("deleted" to true, "wallpaper_id" to id, "title" to target.name),
        )
    }

    private suspend fun updateWallpaperMetadata(args: JsonObject): ToolResult {
        val id = args.int("wallpaper_id") ?: return invalidArgs("wallpaper_id 是必填的。")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        val target = catalog.wallpapers.firstOrNull { it.wallpaperId == id }
            ?: return ToolResult.error(
                json("error" to "wallpaper_not_found", "detail" to "找不到 wallpaper_id=$id 的壁纸。"),
            )
        if (!target.canEditMetadata) {
            return ToolResult.error(
                json(
                    "error" to "not_editable",
                    "detail" to "壁纸 '${target.name}' 不是 REAREye 导入的，不允许修改信息。",
                    "recovery" to "告知用户这张壁纸改不了，不要重试。",
                ),
            )
        }

        // Blank fields fall back to the existing values: the module treats an empty string as
        // "leave unchanged", so passing blanks for the fields the user did not mention is correct.
        val options = RearWallpaperMetadataOptions(
            titleFallback = args.string("title") ?: "",
            titleZhCn = args.string("title_zh") ?: "",
            descriptionFallback = args.string("description") ?: "",
            descriptionZhCn = args.string("description_zh") ?: "",
            author = args.string("author") ?: "",
            designer = args.string("designer") ?: "",
            category = args.string("category") ?: "",
            resSubType = args.string("res_sub_type") ?: "",
            editable = args.boolean("editable") ?: target.editable,
            thirdParties = args.boolean("third_parties") ?: target.thirdParties,
            supportAon = args.boolean("support_aon") ?: target.supportAon,
        )

        val result = RearWallpaperRepository.updateWallpaperMetadata(appContext, id, null, options)
        return if (result.success) {
            ToolResult.ok(json("updated" to true, "wallpaper_id" to id))
        } else {
            ToolResult.error(
                json(
                    "error" to "update_failed",
                    "detail" to (result.error ?: "模块拒绝了修改请求。"),
                ),
            )
        }
    }

    // ============================================================ write plumbing

    /**
     * Shared write path for whole-list replacements.
     *
     * Refuses to write unless the configuration service is bound, because reads against an unbound
     * service return empty defaults and a write built from those defaults would erase the user's
     * configuration. [mutate] receives the freshly read authoritative list and persists the new
     * one; [readBack] returns the list to verify against afterwards.
     *
     * The module swallows every host-side API failure, so "no exception" is not success — the
     * read-back is what decides.
     */
    private fun <T> mutateWithVerification(
        action: String,
        mutate: (List<T>) -> Unit,
        readBack: () -> List<T>,
        verify: (List<T>) -> Boolean,
        detail: Map<String, String>,
    ): ToolResult {
        requireReadable()?.let { return it }

        return try {
            val current = readBack()
            mutate(current)
            val saved = readBack()
            if (verify(saved)) {
                ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("action", action)
                        detail.forEach { (key, value) -> put(key, value) }
                    }.toString(),
                )
            } else {
                ToolResult.error(
                    json(
                        "error" to "verify_failed",
                        "action" to action,
                        "detail" to "改动已提交，但回读没有看到预期结果。模块的接口失败是静默的，所以不能当作成功。",
                        "recovery" to "重新读取一次当前状态并向用户如实说明，不要声称已成功。",
                    ),
                )
            }
        } catch (t: Throwable) {
            ToolResult.error(
                json(
                    "error" to "write_failed",
                    "action" to action,
                    "detail" to (t.message ?: t.javaClass.simpleName).take(300),
                    "recovery" to "写入失败，配置未被改动。请如实告知用户。",
                ),
            )
        }
    }

    private fun isRemoteReady(): Boolean =
        runCatching { prefs.isRemoteReady() }.getOrDefault(false)

    private fun requireReadable(): ToolResult? =
        if (isRemoteReady()) {
            null
        } else {
            ToolResult.error(
                json(
                    "error" to "config_unavailable",
                    "detail" to "模块配置服务未连接，无法读取或修改卡片、组件或壁纸。",
                    "recovery" to "先调用 get_module_status 查看状态；不要告诉用户「没有配置」，因为实际是读不到。",
                ),
            )
        }

    /** Write operations need the same readiness check, but failing to write is more serious. */
    private fun writeGuard(): ToolResult? = requireReadable()

    private fun invalidArgs(detail: String): ToolResult = ToolResult.error(
        json(
            "error" to "invalid_args",
            "detail" to detail,
            "recovery" to "请补齐参数后重试。",
        ),
    )

    /**
     * The module compiles route patterns leniently and swallows failures, so a malformed regex is
     * persisted and then never matches — a dead route with no error anywhere. Validate up front.
     */
    private fun validateScenePattern(scene: String): String? {
        val looksLikeRegex = scene.startsWith("regex:") ||
            scene.startsWith("re:") ||
            (scene.startsWith("/") && scene.endsWith("/") && scene.length > 2)
        val pattern = when {
            scene.startsWith("regex:") -> scene.removePrefix("regex:")
            scene.startsWith("re:") -> scene.removePrefix("re:")
            looksLikeRegex -> scene.substring(1, scene.length - 1)
            else -> return null
        }
        if (pattern.isEmpty()) return "场景规则是空的正则表达式。"
        return runCatching { Regex(pattern) }
            .exceptionOrNull()
            ?.let { "场景规则的正则表达式不合法（${it.message}）。模块会接受它但永远不会匹配，所以请修正后重试。" }
    }

    private companion object {
        const val GET_MODULE_STATUS = "get_module_status"
        const val LIST_COMPONENTS = "list_components"
        const val LIST_CARDS = "list_cards"
        const val LIST_APP_CARDS = "list_app_cards"

        /**
         * App-card pages stay small on purpose: the whole catalog is long enough to run into
         * [hk.uwu.reareye.agent.AgentConfig.maxToolResultChars], and an over-long result is
         * head/tail-truncated into invalid JSON rather than rejected.
         */
        private const val DEFAULT_APP_CARD_PAGE_SIZE = 12

        /**
         * The rear screen this targets: a 904×572 landscape panel.
         *
         * Supplied as a default so a generation request does not have to restate it, but kept as a
         * parameter because the specification requires the design base to match the real device.
         */
        private const val DEFAULT_BASE_WIDTH = 904
        private const val DEFAULT_BASE_HEIGHT = 572
        const val REGISTER_APP_CARD = "register_app_card"
        const val RENAME_APP_CARD = "rename_app_card"
        const val DELETE_APP_CARD = "delete_app_card"
        const val REORDER_APP_CARDS = "reorder_app_cards"

        /**
         * HTML widget generation. "HTML 组件" is a component built from a fixed manifest plus a
         * generated `web/index.html`; a future "MAML 组件" would be a different kind entirely, which
         * is why the tool names say `html`.
         */
        const val CREATE_HTML_WIDGET = "create_html_widget"

        /**
         * Returns the HTML widget guideline with this request's parameters filled in.
         *
         * The guideline is long, so it is fetched on demand rather than carried in the system prompt
         * of every turn. That matches how the specification is meant to be used — it is the *system
         * prompt of the generation call*, not of the conversation.
         */
        const val GET_WIDGET_SPEC = "get_widget_spec"
        const val LIST_SCENE_ROUTES = "list_scene_routes"
        const val LIST_WALLPAPERS = "list_wallpapers"

        const val CREATE_CARD = "create_card"
        const val UPDATE_CARD = "update_card"
        const val DELETE_CARD = "delete_card"
        const val SET_CARD_ENABLED = "set_card_enabled"

        const val RENAME_COMPONENT = "rename_component"
        const val DELETE_COMPONENT = "delete_component"

        const val REGISTER_SCENE_ROUTE = "register_scene_route"
        const val DELETE_SCENE_ROUTE = "delete_scene_route"

        const val SWITCH_WALLPAPER = "switch_wallpaper"
        const val SET_WALLPAPER_ROTATION = "set_wallpaper_rotation"
        const val SET_ROTATION_ENABLED = "set_rotation_enabled"
        const val DELETE_WALLPAPER = "delete_wallpaper"
        const val UPDATE_WALLPAPER_METADATA = "update_wallpaper_metadata"

        fun json(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> put(key, kotlinx.serialization.json.JsonNull)
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()

        val ALL: List<ToolSpec> = readTools() + writeTools()

        private fun readTools(): List<ToolSpec> = listOf(
            ToolSpec(
                name = GET_MODULE_STATUS,
                description = """
                    查询 REAREye 模块自身的运行状态：是否已激活、作用域是否授权、模块配置当前是否可读。

                    什么时候用：
                    - 用户问"背屏功能怎么没生效""模块装好了吗""为什么改了没反应"时，先调用它。
                    - 在任何其他查询或修改之前调用它，可以提前发现配置服务没连接的情况。

                    返回：activation_state（SERVICE_UNAVAILABLE / SCOPE_NOT_AUTHORIZED /
                    NO_RUNNING_TARGET / ACTIVE）、module_active、config_readable，以及中文说明。
                    本工具只读。
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_COMPONENTS,
                description = """
                    列出背屏上已经装好的组件（"组件"），以及每个组件被哪些卡片使用。

                    什么时候用：
                    - 用户问"背屏装了什么""列一下组件""有哪些组件"时，这是正确的工具。
                    - 在新增卡片之前，用它确认目标组件确实存在，并拿到 business 值。

                    返回：count 和 components 数组。每项含 business（组件标识，建卡片要用）、
                    package_name、template_path、default_priority、renameable、
                    from_store、store_widget、used_by_cards。

                    本工具只读。返回 config_unavailable 时不要告诉用户"没有组件"，那是读不到。
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_CARDS,
                description = """
                    列出背屏上配置好的卡片（"卡片"）。

                    什么时候用：
                    - 用户问"背屏有哪些卡片"时。
                    - 在修改、开关或删除卡片之前，先用它拿到卡片的 id。

                    返回：count 和 cards 数组。每项含 id（改删要用）、title、package_name、
                    business、enabled、sticky、priority、renameable（false 表示仓库安装、受保护）、
                    from_store、has_template_values。本工具只读。
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_APP_CARDS,
                description = """
                    列出背屏上的应用卡（"应用"）。

                    什么时候用：
                    - 用户问"背屏有哪些应用""装了什么应用"，或提到"应用卡"时，用这个工具。
                    - **绝对不要**用 list_components 或 list_cards 回答关于"应用"的问题。
                      它们读的是组件和卡片，和应用卡是完全不同的东西。

                    概念区分（重要）：
                    - 组件（component）：可复用模板，用 business 标识，本身不显示在背屏上。
                    - 卡片（card）：由组件派生的显示内容，用卡片 id 标识。
                    - 应用卡（app card）：也叫"应用"，由组件派生，但是能跳转到某个 App 的
                      入口，用 app_id 标识。用户说"背屏应用"指的就是它。

                    返回：total（总数）、offset、count（本次返回数）、has_more、
                    next_offset（还有更多时给出）、app_cards 数组。
                    每项含 app_id（后续改名/删除要用）、title、component_business（派生自哪个组件）、
                    bind_package，以及仅在可用时才出现的 can_rename / can_delete。

                    分页说明：结果默认每页 $DEFAULT_APP_CARD_PAGE_SIZE 条，避免一次性返回过长内容。
                    如果 has_more 为 true，用 next_offset 再调一次即可继续。
                    用户要"列出全部应用"时，要一直翻到 has_more 为 false，不要只报第一页。

                    如果 total 为 0，就是真的没有应用卡，照实说即可。本工具只读。
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
                params = listOf(
                    ToolParam(
                        "offset",
                        "从第几条开始，默认 0",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                    ),
                    ToolParam(
                        "limit",
                        "本次返回条数，默认 $DEFAULT_APP_CARD_PAGE_SIZE",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                    ),
                ),
            ),
            ToolSpec(
                name = REGISTER_APP_CARD,
                description = """
                    新建一张应用卡（"应用"）。

                    概念：应用卡由组件派生，但是能跳转到某个 App 的入口，和卡片是两种东西。
                    这个工具**不会**创建卡片；要建卡片请用 create_card。

                    前置条件：component_business 必须是已注册的组件。先调用 list_components 获取。

                    参数：
                    - title（必填）：应用卡显示名。
                    - component_business（必填）：来源组件标识，来自 list_components。

                    本操作会修改配置，需要用户确认。返回 ok 与 app_id。
                    如果返回 component_not_found，先查组件再重试，不要凭空编造标识。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("title", "应用卡显示名", ToolParam.ParamType.STRING),
                    ToolParam(
                        "component_business",
                        "来源组件标识，来自 list_components",
                        ToolParam.ParamType.STRING,
                    ),
                ),
            ),
            ToolSpec(
                name = RENAME_APP_CARD,
                description = """
                    重命名一张应用卡。

                    参数：
                    - app_id（必填）：先用 list_app_cards 获取，注意不是卡片的 id。
                    - title（必填）：新名称。

                    限制：只有 can_rename=true 的应用卡能改名，否则返回 not_renameable。
                    遇到时直接告诉用户这一张改不了，不要重试。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("app_id", "应用卡 id，来自 list_app_cards", ToolParam.ParamType.STRING),
                    ToolParam("title", "新名称", ToolParam.ParamType.STRING),
                ),
            ),
            ToolSpec(
                name = DELETE_APP_CARD,
                description = """
                    删除一张应用卡。

                    参数：app_id（必填），先用 list_app_cards 获取。

                    这是不可撤销的操作。删除前先用标题向用户复述一遍是哪一张，不要凭猜测删除。
                    限制：只有 can_delete=true 的应用卡能删，否则返回 not_deletable。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("app_id", "应用卡 id，来自 list_app_cards", ToolParam.ParamType.STRING),
                ),
            ),
            ToolSpec(
                name = REORDER_APP_CARDS,
                description = """
                    调整应用卡的显示顺序。

                    参数：app_ids（必填）—— 按期望顺序排列的**全部** app_id。

                    重要：必须传入当前所有应用卡的 app_id，不能只传一部分。模块是按完整列表
                    重排的，少传的会被丢掉。工具会先核对数量，不匹配时返回 incomplete_order
                    并告诉你当前共有多少张，此时应先调用 list_app_cards 取全再重试。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "app_ids",
                        "按期望顺序排列的全部 app_id（字符串数组）",
                        ToolParam.ParamType.STRING_ARRAY,
                    ),
                ),
            ),
            ToolSpec(
                name = GET_WIDGET_SPEC,
                description = """
                    取回"HTML 组件"的生成规范（已按本次参数填好）。

                    什么时候用：
                    - 用户要求生成一个背屏上原本没有的卡片/应用/组件时，**第一步先调用本工具**。
                    - 规范规定了安全区、可用区比例、触控尺寸、分辨率适配、生命周期接口等硬性约束，
                      违反其中任何一条都会被程序校验拒绝。

                    参数：
                    - content（必填）：把用户要的东西写清楚——功能、状态机、视觉规格、交互规则、文案。
                      规范要求界面文案语言与 content 的书写语言一致，所以用中文写就会生成中文界面。
                    - app_name（可选）：应用名。
                    - base_width / base_height（可选）：设计基准分辨率，默认 $DEFAULT_BASE_WIDTH×$DEFAULT_BASE_HEIGHT。

                    返回：spec 字段是完整规范原文。拿到后按它写 HTML，再调用 create_html_widget 提交。
                    本工具只读，不修改任何配置。
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
                params = listOf(
                    ToolParam("content", "功能、状态机、视觉规格、交互规则、文案", ToolParam.ParamType.STRING),
                    ToolParam("app_name", "应用名", ToolParam.ParamType.STRING, required = false),
                    ToolParam("base_width", "设计基准宽，默认 $DEFAULT_BASE_WIDTH", ToolParam.ParamType.INTEGER, required = false),
                    ToolParam("base_height", "设计基准高，默认 $DEFAULT_BASE_HEIGHT", ToolParam.ParamType.INTEGER, required = false),
                ),
            ),
            ToolSpec(
                name = CREATE_HTML_WIDGET,
                description = """
                    把一个生成好的单文件 HTML 注册为一个全新的背屏组件（"HTML 组件"）。

                    什么时候用：
                    - 用户要求"生成一个 XX 卡片/应用/组件"，而且现有组件满足不了时。
                    - 你已经按系统提示词里的 HTML 组件规范写好完整 HTML 之后。

                    概念（重要）：
                    - 组件是根本，卡片和应用都必须由组件派生。本工具创建的是**组件**。
                    - 组件建好后，再用 register_app_card 把它注册成应用卡（"应用"），
                      或用 create_card 建成卡片。
                    - 现有的 create_card / register_app_card 只能使用**已存在**的组件；
                      要造背屏上原本没有的东西，必须先调用本工具。

                    参数：
                    - html（必填）：完整的单文件 HTML，遵循系统提示词里的规范。
                    - title（必填）：显示名，例如"真心话大冒险"。
                    - business（可选）：组件标识。留空则自动取名；若与已有组件重名会自动加后缀。
                    - base_width / base_height（可选）：设计基准分辨率，默认 $DEFAULT_BASE_WIDTH×$DEFAULT_BASE_HEIGHT。
                      背屏是横屏，必须 base_width > base_height。

                    校验：HTML 会先按规范自动校验（安全区、触控尺寸、头注释数值是否与 LAYOUT 实算一致等）。
                    不通过时返回 widget_validation_failed 和逐条问题清单，**此时不会创建任何东西**；
                    请按清单修改 HTML 后重新调用，不要跳过任何一条。

                    本操作会创建组件、修改配置，需要用户确认。成功返回 business，后续注册应用卡要用它。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                // The result echoes back the submitted HTML, which is large by nature. The global
                // cap would cut it in half and hand the model broken JSON to reason about.
                maxResultChars = 200_000,
                params = listOf(
                    ToolParam("html", "完整单文件 HTML", ToolParam.ParamType.STRING),
                    ToolParam("title", "显示名，例如 真心话大冒险", ToolParam.ParamType.STRING),
                    ToolParam("business", "组件标识，留空自动取名", ToolParam.ParamType.STRING, required = false),
                    ToolParam("base_width", "设计基准宽，默认 $DEFAULT_BASE_WIDTH", ToolParam.ParamType.INTEGER, required = false),
                    ToolParam("base_height", "设计基准高，默认 $DEFAULT_BASE_HEIGHT", ToolParam.ParamType.INTEGER, required = false),
                ),
            ),
            ToolSpec(
                name = LIST_SCENE_ROUTES,
                description = """
                    列出背屏的场景路由规则：把某个应用的通知场景映射到指定组件。

                    什么时候用：用户问"通知怎么显示的""场景路由配了哪些"时。

                    返回：count 和 routes 数组，每项含 id（删除要用）、package_name、scene、
                    business、from_store。

                    注意：只管场景路由，系统级通知路由不在本模块可读范围内。本工具只读。
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_WALLPAPERS,
                description = """
                    列出背屏壁纸：全部可用壁纸、当前正在使用的那张，以及轮播列表。

                    什么时候用：
                    - 用户问"有哪些壁纸""现在用哪张""轮播怎么设的"时。
                    - 切换壁纸、改信息、删除、调轮播之前，先拿 wallpaper_id 并确认 imported。

                    返回：count、current_wallpaper_id、rotation_enabled、rotation_count；
                    wallpapers 数组（wallpaper_id、title、name、is_current、in_rotation、imported、
                    can_edit_metadata、can_delete、has_template_config、author）；
                    rotation 数组（wallpaper_id、interval_ms）。

                    重要：只有 imported=true 的壁纸能改或删，系统壁纸会被模块拒绝。
                    本工具只读。
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
        )

        private fun writeTools(): List<ToolSpec> = listOf(
            ToolSpec(
                name = CREATE_CARD,
                description = """
                    在背屏上新建一张卡片。

                    前置条件：目标组件必须已经注册。先调用 list_components 拿到 business 值。

                    参数：
                    - title（必填）：卡片显示名。
                    - business（必填）：要使用的组件标识，来自 list_components。
                    - package_name（可选）：通知投递的目标包名；留空则沿用组件自身的包名。
                    - enabled（可选，默认 true）：是否启用。
                    - sticky（可选，默认 true）：是否常驻显示。
                    - priority（可选）：优先级，数字越大越靠前；留空则用组件默认值。

                    注意：如果标题和组件都相同的卡片已存在，会被拒绝，避免重复。

                    本操作会修改配置，需要用户确认。返回 ok 与 card_id。
                    如果返回 component_not_found，先查组件再重试，不要凭空编造 business。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("title", "卡片显示名", ToolParam.ParamType.STRING),
                    ToolParam("business", "组件标识，来自 list_components", ToolParam.ParamType.STRING),
                    ToolParam("package_name", "通知投递目标包名，可省略", ToolParam.ParamType.STRING, required = false),
                    ToolParam("enabled", "是否启用，默认 true", ToolParam.ParamType.BOOLEAN, required = false),
                    ToolParam("sticky", "是否常驻显示，默认 true", ToolParam.ParamType.BOOLEAN, required = false),
                    ToolParam("priority", "优先级，越大越靠前", ToolParam.ParamType.INTEGER, required = false),
                ),
            ),
            ToolSpec(
                name = UPDATE_CARD,
                description = """
                    修改一张已存在的卡片。只改传入的字段，其余保持不变。

                    参数：
                    - id（必填）：卡片 id，先用 list_cards 获取。
                    - title / enabled / sticky / priority（可选，至少给一个）：要修改的字段。
                    - confirm_locked_edit（可选，默认 false）：仅当卡片被仓库锁定时才需要。

                    受保护的卡片：来自组件仓库的卡片 renameable=false，模块默认不允许直接修改。
                    遇到 locked_card 错误时，先向用户说明这是仓库安装的卡片，得到同意后才带
                    confirm_locked_edit=true 重试；不要自己决定绕过保护。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("id", "卡片 id，来自 list_cards", ToolParam.ParamType.STRING),
                    ToolParam("title", "新的显示名", ToolParam.ParamType.STRING, required = false),
                    ToolParam("enabled", "启用或停用", ToolParam.ParamType.BOOLEAN, required = false),
                    ToolParam("sticky", "是否常驻显示", ToolParam.ParamType.BOOLEAN, required = false),
                    ToolParam("priority", "新的优先级", ToolParam.ParamType.INTEGER, required = false),
                    ToolParam("confirm_locked_edit", "确认修改仓库锁定的卡片", ToolParam.ParamType.BOOLEAN, required = false),
                ),
            ),
            ToolSpec(
                name = DELETE_CARD,
                description = """
                    删除一张卡片。

                    参数：id（必填），先用 list_cards 获取。

                    这是不可撤销的操作。删除前要确认用户指的是哪一张（用标题复述一遍），
                    不要凭猜测删除。本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(ToolParam("id", "卡片 id，来自 list_cards", ToolParam.ParamType.STRING)),
            ),
            ToolSpec(
                name = SET_CARD_ENABLED,
                description = """
                    单独启用或停用一张卡片。比 update_card 更明确，用于"把某张卡关掉"这类请求。

                    参数：id（必填）、enabled（必填，true 启用 / false 停用）。

                    如果卡片被仓库锁定，模块会拒绝，返回 verify_failed。
                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("id", "卡片 id，来自 list_cards", ToolParam.ParamType.STRING),
                    ToolParam("enabled", "true 启用 / false 停用", ToolParam.ParamType.BOOLEAN),
                ),
            ),
            ToolSpec(
                name = RENAME_COMPONENT,
                description = """
                    重命名一个组件标识（business）。

                    参数：
                    - business（必填）：现有组件标识，来自 list_components。
                    - new_business（必填）：新的组件标识，不能与已有组件重名。
                    - confirm_locked_edit（可选）：仅当组件由仓库安装、renameable=false 时需要。

                    这个操作会自动把所有引用该组件的卡片一起改过去，否则它们会指向一个
                    不存在的组件而失效。操作是原子的，失败时组件和卡片都不会被改动。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("business", "现有组件标识", ToolParam.ParamType.STRING),
                    ToolParam("new_business", "新的组件标识", ToolParam.ParamType.STRING),
                    ToolParam("confirm_locked_edit", "确认修改仓库锁定的组件", ToolParam.ParamType.BOOLEAN, required = false),
                ),
            ),
            ToolSpec(
                name = DELETE_COMPONENT,
                description = """
                    删除一个组件。

                    参数：
                    - business（必填）：组件标识。
                    - confirm_orphan_cards（可选，默认 false）：当还有卡片在用这个组件时需要。

                    重要：删除组件不会自动删除引用它的卡片，那些卡片会失效。
                    如果还有卡片在用，工具会先返回 component_in_use 并列出是哪几张。
                    请把这件事告诉用户，得到同意后才带 confirm_orphan_cards=true 重试。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("business", "组件标识", ToolParam.ParamType.STRING),
                    ToolParam("confirm_orphan_cards", "确认让引用它的卡片失效", ToolParam.ParamType.BOOLEAN, required = false),
                ),
            ),
            ToolSpec(
                name = REGISTER_SCENE_ROUTE,
                description = """
                    新增一条场景路由：把某个应用的通知场景映射到指定组件。

                    参数（都必填）：
                    - package_name：来源应用包名。
                    - scene：场景名。普通场景名直接写，例如 carHailing、foodDelivery、incall、
                      countdown；也可以写正则，前缀 regex: 或 re:，或用 /.../ 包裹。
                    - business：目标组件标识，必须已在 list_components 中存在。

                    工具会在写入前校验正则语法。模块本身接受非法正则却永远不匹配，
                    所以这里宁可先报错，避免产生一条静默失效的路由。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("package_name", "来源应用包名", ToolParam.ParamType.STRING),
                    ToolParam("scene", "场景名或正则", ToolParam.ParamType.STRING),
                    ToolParam("business", "目标组件标识", ToolParam.ParamType.STRING),
                ),
            ),
            ToolSpec(
                name = DELETE_SCENE_ROUTE,
                description = """
                    删除一条场景路由。

                    参数：id（必填），先用 list_scene_routes 获取。
                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(ToolParam("id", "路由 id，来自 list_scene_routes", ToolParam.ParamType.STRING)),
            ),
            ToolSpec(
                name = SWITCH_WALLPAPER,
                description = """
                    把当前背屏壁纸切换为指定的一张。

                    参数：wallpaper_id（必填），先用 list_wallpapers 获取。

                    重要限制：如果主屏正处于编辑模式，系统会忽略这次切换，但接口仍然报告成功。
                    因此不要向用户断言"已经换好了"，应该说"已发送切换请求"。
                    另外，背屏中心没有运行时切换也会失败。

                    本操作会修改设备状态，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(ToolParam("wallpaper_id", "壁纸 id，来自 list_wallpapers", ToolParam.ParamType.INTEGER)),
            ),
            ToolSpec(
                name = SET_WALLPAPER_ROTATION,
                description = """
                    把一张壁纸加入或移出轮播列表，并可同时设置它的切换间隔。

                    参数：
                    - wallpaper_id（必填）。
                    - in_rotation（必填）：true 加入轮播 / false 移出。
                    - interval_ms（可选）：该壁纸的切换间隔，毫秒；留空用模块默认值（60000）。
                      模块会把小于 100 的值提升到 100。

                    说明：轮播列表的顺序就是数组顺序，新加入的会排在末尾。
                    移出后如果列表为空，轮播会被自动关闭。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("wallpaper_id", "壁纸 id", ToolParam.ParamType.INTEGER),
                    ToolParam("in_rotation", "true 加入 / false 移出", ToolParam.ParamType.BOOLEAN),
                    ToolParam("interval_ms", "切换间隔（毫秒），可省略", ToolParam.ParamType.INTEGER, required = false),
                ),
            ),
            ToolSpec(
                name = SET_ROTATION_ENABLED,
                description = """
                    开启或关闭背屏壁纸轮播。

                    参数：enabled（必填）。

                    如果轮播列表是空的，工具会返回 empty_rotation 并拒绝开启——
                    模块本身也拒绝这种状态。请先把至少一张壁纸加入轮播。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(ToolParam("enabled", "true 开启 / false 关闭", ToolParam.ParamType.BOOLEAN)),
            ),
            ToolSpec(
                name = DELETE_WALLPAPER,
                description = """
                    删除一张由 REAREye 导入或组件仓库安装的壁纸。

                    参数：wallpaper_id（必填）。

                    限制：只有 imported=true 的壁纸能删除。系统自带壁纸会被模块拒绝，
                    工具会提前返回 not_imported，这时应当直接告诉用户"这张删不了"，不要重试。

                    删除后会自动把它从轮播列表中移除，保持一致。这是不可撤销的操作。
                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(ToolParam("wallpaper_id", "壁纸 id，来自 list_wallpapers", ToolParam.ParamType.INTEGER)),
            ),
            ToolSpec(
                name = UPDATE_WALLPAPER_METADATA,
                description = """
                    修改壁纸的信息（标题、描述、作者、设计者、分类等）。

                    参数：
                    - wallpaper_id（必填）。
                    - title / title_zh / description / description_zh / author / designer /
                      category / res_sub_type（可选）：要改的文本字段。
                    - editable / third_parties / support_aon（可选）：布尔标记。

                    只传要改的字段即可。留空的字段表示保持原值不变——模块把空字符串当作
                    "不修改"，所以不要为了让某个字段为空而传空串。

                    限制：只有 can_edit_metadata=true（即 imported=true）的壁纸可以改，
                    否则返回 not_editable，这时告知用户即可，不要重试。

                    本操作会修改配置，需要用户确认。
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("wallpaper_id", "壁纸 id", ToolParam.ParamType.INTEGER),
                    ToolParam("title", "标题（默认语言）", ToolParam.ParamType.STRING, required = false),
                    ToolParam("title_zh", "中文标题", ToolParam.ParamType.STRING, required = false),
                    ToolParam("description", "描述（默认语言）", ToolParam.ParamType.STRING, required = false),
                    ToolParam("description_zh", "中文描述", ToolParam.ParamType.STRING, required = false),
                    ToolParam("author", "作者", ToolParam.ParamType.STRING, required = false),
                    ToolParam("designer", "设计者", ToolParam.ParamType.STRING, required = false),
                    ToolParam("category", "分类", ToolParam.ParamType.STRING, required = false),
                    ToolParam("res_sub_type", "资源子类型", ToolParam.ParamType.STRING, required = false),
                    ToolParam("editable", "是否允许编辑", ToolParam.ParamType.BOOLEAN, required = false),
                    ToolParam("third_parties", "第三方标记", ToolParam.ParamType.BOOLEAN, required = false),
                    ToolParam("support_aon", "是否支持息屏显示", ToolParam.ParamType.BOOLEAN, required = false),
                ),
            ),
        )
    }
}

/** Null-safe argument readers: `JsonNull` is an object, so `as?` is the only safe navigation. */
internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

/** Reads a list of strings, skipping anything that is not a JSON string. */
internal fun JsonObject.stringArray(name: String): List<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
