package hk.uwu.reareye.agent.tools

import android.content.Context
import hk.uwu.reareye.agent.htmlwidget.HtmlWidgetPackage
import hk.uwu.reareye.agent.htmlwidget.WidgetHtmlValidator
import hk.uwu.reareye.agent.htmlwidget.WidgetSpec
import hk.uwu.reareye.agent.luascript.LuaScriptValidator
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
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.script.ScriptProjectKind
import hk.uwu.reareye.script.ScriptProjectStore
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleCodec
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleEntry
import hk.uwu.reareye.widgetapi.RearWidgetSceneRouteSpec
import kotlinx.coroutines.CancellationException
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
    private val scriptEditing by lazy { MaryScriptEditingTools(appContext) }
    private val componentEditing by lazy { MaryComponentEditingTools(appContext) }
    private val configEditing by lazy { MaryConfigEditingTools(appContext) }

    override fun availableTools(): List<ToolSpec> = ALL

    internal fun scriptingModelReference(): String = SCRIPT_MODEL_SPEC

    override suspend fun execute(toolName: String, args: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            scriptEditing.execute(toolName, args)?.let { return@withContext it }
            componentEditing.execute(toolName, args)?.let { return@withContext it }
            configEditing.execute(toolName, args)?.let { return@withContext it }
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

                // --- lua scripts
                LIST_SCRIPTS -> listScripts()
                READ_SCRIPT -> readScript(args)
                GET_SCRIPT_SPEC -> getScriptSpec()
                CREATE_SCRIPT -> createScript(args)
                DELETE_SCRIPT -> deleteScript(args)

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

                else -> ToolResult.error("Unknown tool: $toolName")
            }
        }

    // ================================================================ diagnostics

    private fun getModuleStatus(): ToolResult {
        val state = XposedModuleStatus.current()
        val readable = isRemoteReady()

        val explanation = when (state) {
            ModuleActivationState.ACTIVE ->
                "Module active; at least one scoped target process is running."

            ModuleActivationState.NO_RUNNING_TARGET ->
                "Module enabled and scope authorized, but no target process is running (rear-screen center, themes or system UI may be inactive)."

            ModuleActivationState.SCOPE_NOT_AUTHORIZED ->
                "Module enabled, but no scope authorized. Select target scopes in LSPosed."

            ModuleActivationState.SERVICE_UNAVAILABLE ->
                "Cannot connect to libxposed; the module may be disabled in LSPosed or the framework unsupported."
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
        val order = RearWidgetManagerRepository.loadCardOrderSettings(prefs)

        return ToolResult.ok(
            buildJsonObject {
                put("count", cards.size)
                put("cards", buildJsonArray {
                    cards.forEachIndexed { index, card ->
                        add(buildJsonObject {
                            put("id", card.id)
                            put("title", card.title)
                            put("package_name", card.packageName)
                            put("business", card.business)
                            put("enabled", card.enabled)
                            put("sticky", card.sticky)
                            put("priority", card.priority)
                            put("automatic_priority", order[card.id]?.automatic ?: true)
                            put("order_position", order[card.id]?.position ?: index)
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
                    "These are app launch entries, not display cards or components. " +
                            if (next < all.size) "More results available; continue with offset=$next." else "",
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
        if (title.isEmpty()) return invalidArgs("title is required.")
        if (business.isEmpty()) {
            return invalidArgs("component_business is required; get it from list_components.")
        }

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        if (components.none { it.business == business }) {
            return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "Component '$business' does not exist. App cards require a registered component.",
                    "recovery" to "Call list_components for a valid business value; do not invent identifiers.",
                ),
            )
        }

        var appId: String? = null
        return mutateWithVerification(
            action = "register_app_card",
            mutate = {
                val result = RearAppCardRepository.register(appContext, prefs, title, business)
                if (!result.success) {
                    error(
                        result.error
                            ?: "App card registration failed without a reason from the module."
                    )
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
        if (appId.isEmpty()) return invalidArgs("app_id is required; get it from list_app_cards.")
        if (title.isEmpty()) return invalidArgs("title is required.")

        writeGuard()?.let { return it }
        val existing = RearAppCardRepository.loadCatalog(appContext).firstOrNull { it.appId == appId }
            ?: return ToolResult.error(
                json(
                    "error" to "app_card_not_found",
                    "detail" to "App card '$appId' not found.",
                    "recovery" to "Call list_app_cards for a valid app_id.",
                ),
            )

        return mutateWithVerification(
            action = "rename_app_card",
            mutate = { _ ->
                val result = RearAppCardRepository.rename(appContext, appId, title)
                if (!result.success) error(
                    result.error ?: "Rename failed without a reason from the module."
                )
            },
            readBack = { RearAppCardRepository.loadCatalog(appContext) },
            verify = { saved -> saved.any { it.appId == appId && it.title == title } },
            detail = mapOf("app_id" to appId, "title" to title),
        )
    }

    private fun deleteAppCard(args: JsonObject): ToolResult {
        val appId = args.string("app_id")?.trim().orEmpty()
        if (appId.isEmpty()) return invalidArgs("app_id is required; get it from list_app_cards.")

        writeGuard()?.let { return it }
        val existing = RearAppCardRepository.loadCatalog(appContext).firstOrNull { it.appId == appId }
            ?: return ToolResult.error(
                json(
                    "error" to "app_card_not_found",
                    "detail" to "App card '$appId' not found.",
                    "recovery" to "Call list_app_cards for a valid app_id.",
                ),
            )

        return mutateWithVerification(
            action = "delete_app_card",
            mutate = { _ ->
                val result = RearAppCardRepository.delete(appContext, appId)
                if (!result.success) error(
                    result.error ?: "Deletion failed without a reason from the module."
                )
            },
            readBack = { RearAppCardRepository.loadCatalog(appContext) },
            verify = { saved -> saved.none { it.appId == appId } },
            detail = mapOf("app_id" to appId, "title" to existing.title),
        )
    }

    private fun reorderAppCards(args: JsonObject): ToolResult {
        val ids = args.stringArray("app_ids").map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            return invalidArgs("app_ids is required; obtain all ids from list_app_cards.")
        }
        if (ids.size != ids.toSet().size) {
            return invalidArgs("app_ids contains duplicates; each entry must appear exactly once.")
        }

        writeGuard()?.let { return it }
        val catalog = RearAppCardRepository.loadCatalog(appContext)
        // The module reorders by the full list, so a partial list would silently drop the rest.
        val missing = ids.filter { id -> catalog.none { it.appId == id } }
        if (missing.isNotEmpty()) {
            return ToolResult.error(
                json(
                    "error" to "app_card_not_found",
                    "detail" to "Unknown app ids: ${missing.joinToString()}",
                    "recovery" to "Call list_app_cards for a valid app_id.",
                ),
            )
        }
        if (ids.size != catalog.size) {
            return ToolResult.error(
                json(
                    "error" to "incomplete_order",
                    "detail" to "Received ${ids.size} ids but there are ${catalog.size} app cards; " +
                            "the order must include every entry to avoid dropping others.",
                    "recovery" to "Fetch all ids with list_app_cards and supply the complete desired order.",
                ),
            )
        }

        return mutateWithVerification(
            action = "reorder_app_cards",
            mutate = { _ ->
                val result = RearAppCardRepository.reorder(appContext, ids)
                if (!result.success) error(
                    result.error ?: "Reorder failed without a reason from the module."
                )
            },
            readBack = { RearAppCardRepository.loadCatalog(appContext) },
            verify = { saved -> saved.map { it.appId } == ids },
            detail = mapOf("order" to ids.joinToString()),
        )
    }

    // ================================================================ lua scripts

    /**
     * Lists the Lua scripts installed in REAREye.
     *
     * Read-only and non-executing: a script's descriptor can only be obtained by loading it, which
     * runs its top-level code, so this reports only what the storage layer knows.
     */
    private fun listScripts(): ToolResult {
        val projects = ScriptProjectStore(appContext).list()
        return ToolResult.ok(
            buildJsonObject {
                put("count", projects.size)
                put(
                    "note",
                    "STANDALONE is a single-file script; PROJECT is a multi-file directory; " +
                            "COMPONENT is component-owned and cannot be independently deleted. Use type-qualified ids.",
                )
                put("scripts", buildJsonArray {
                    projects.forEach { project ->
                        add(buildJsonObject {
                            put("id", qualifiedScriptId(project.kind.name, project.id))
                            put("local_id", project.id)
                            put("name", project.name)
                            put("kind", project.kind.name)
                            put("valid", project.isValid)
                            project.componentId?.let { put("component_id", it) }
                            project.error?.let { put("error", it) }
                        })
                    }
                })
            }.toString(),
        )
    }

    private fun readScript(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return invalidArgs("id is required; use the type-qualified id from list_scripts.")

        val store = ScriptProjectStore(appContext)
        val matches = store.list().filter { scriptIdMatches(id, it.kind.name, it.id) }
        if (matches.size > 1) return ToolResult.error(
            json(
                "error" to "ambiguous_script_id",
                "detail" to "Multiple scripts match '$id'; no script was read or deleted.",
                "recovery" to "Call list_scripts and use the exact type-qualified id (KIND:local_id).",
            )
        )
        val project = matches.singleOrNull()
            ?: return ToolResult.error(
                json(
                    "error" to "script_not_found",
                    "detail" to "Script '$id' not found.",
                    "recovery" to "Call list_scripts for the exact type-qualified id.",
                ),
            )

        val source = store.read(project)
        return ToolResult.ok(
            buildJsonObject {
                put("id", qualifiedScriptId(project.kind.name, project.id))
                put("local_id", project.id)
                put("name", project.name)
                put("kind", project.kind.name)
                put("valid", project.isValid)
                project.error?.let { put("error", it) }
                // Surfacing the static checks here lets the model see what would block a rewrite
                // without this tool having to execute anything.
                put("issues", LuaScriptValidator.validate(source).report())
                put("source", source)
            }.toString(),
        )
    }

    /**
     * Returns the scripting API reference.
     *
     * The model-facing English reference mirrors SCRIPTING.md. Keep it synchronized with that
     * user-facing document and the authoritative runtime when changing scripting APIs.
     */
    private fun getScriptSpec(): ToolResult {
        val spec = SCRIPT_MODEL_SPEC

        return ToolResult.ok(
            buildJsonObject {
                put(
                    "usage",
                    "This is the complete REAREye scripting specification. " +
                            "Follow it exactly. Use create_script only for a new script. " +
                            "For edits, list_script_files/read_script first, then update_script with the returned full-file sha256; " +
                            "do not delete and recreate an existing project.",
                )
                put("spec", spec)
            }.toString(),
        )
    }

    private fun createScript(args: JsonObject): ToolResult {
        val source = args.string("source").orEmpty()
        if (source.isBlank()) {
            return invalidArgs("source is required and must contain the complete Lua script.")
        }

        val validation = LuaScriptValidator.validate(source)
        if (!validation.passed) {
            return ToolResult.error(
                json(
                    "error" to "script_validation_failed",
                    "detail" to validation.report(),
                    "recovery" to "Fix the reported script issues and retry. Do not ignore any ERROR.",
                    "error_count" to validation.errors.size.toString(),
                ),
            )
        }

        writeGuard()?.let { return it }

        val store = ScriptProjectStore(appContext)
        val existing = store.list().mapTo(mutableSetOf()) { it.name }

        val requested = args.string("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: args.string("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "script"
        val base = sanitizeScriptId(requested)
        val id = uniqueScriptId(base, existing)

        val result = mutateWithVerification(
            action = "create_script",
            mutate = {
                // createStandalone writes unconditionally, so the id has to be free before the call.
                // The uniqueness pass above guarantees it; this only fires on a listing/write race.
                if (store.hasStandalone("$id.lua")) error("Script $id already exists")
                store.createStandalone(id, source)
            },
            readBack = { store.list() },
            verify = { saved -> saved.any { it.id == id && it.kind == ScriptProjectKind.STANDALONE } },
            detail = buildMap {
                put("id", qualifiedScriptId(ScriptProjectKind.STANDALONE.name, id))
                put("warnings", validation.warnings.size.toString())
                validation.warnings.firstOrNull()?.let { put("warning", it.code) }
            },
        )
        if (result.isError) return result

        return ToolResult.ok(
            json(
                "ok" to "true",
                "action" to "create_script",
                "id" to qualifiedScriptId(ScriptProjectKind.STANDALONE.name, id),
                "note" to if (id != base) {
                    "Local script id is $id ($base existed, so a suffix was added)."
                } else {
                    "Local script id is $id."
                },
            ),
        )
    }

    private fun deleteScript(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return invalidArgs("id is required; use the type-qualified id from list_scripts.")

        writeGuard()?.let { return it }
        val store = ScriptProjectStore(appContext)
        val matches = store.list().filter { scriptIdMatches(id, it.kind.name, it.id) }
        if (matches.size > 1) return ToolResult.error(
            json(
                "error" to "ambiguous_script_id",
                "detail" to "Multiple scripts match '$id'; no script was read or deleted.",
                "recovery" to "Call list_scripts and use the exact type-qualified id (KIND:local_id).",
            )
        )
        val project = matches.singleOrNull()
            ?: return ToolResult.error(
                json(
                    "error" to "script_not_found",
                    "detail" to "Script '$id' not found.",
                    "recovery" to "Call list_scripts for the exact type-qualified id.",
                ),
            )

        // A component-owned script belongs to the component that installed it. Deleting its
        // directory here would desynchronise it from that component, and only a reinstall could put
        // it back.
        if (project.kind == ScriptProjectKind.COMPONENT) {
            return ToolResult.error(
                json(
                    "error" to "component_owned_script",
                    "detail" to "Script '${project.name}' is component-owned and cannot be independently deleted.",
                    "recovery" to "Explain that this entry is removed with its component; do not retry.",
                ),
            )
        }

        return mutateWithVerification(
            action = "delete_script",
            mutate = { store.delete(project) },
            readBack = { store.list() },
            verify = { saved -> saved.none { it.id == project.id && it.kind == project.kind } },
            detail = mapOf(
                "id" to qualifiedScriptId(project.kind.name, project.id),
                "name" to project.name
            ),
        )
    }

    /**
     * Turns a name into a standalone script id.
     *
     * The runtime allows dots in a declared id, but a standalone script's id *is* its file name and
     * the store only accepts `[A-Za-z0-9_-]`, so the stricter rule applies here.
     */
    private fun sanitizeScriptId(raw: String): String {
        val cleaned = raw.trim().removeSuffix(".lua")
            .map { ch ->
                if ((ch.isLetterOrDigit() && ch.code < 128) || ch == '_' || ch == '-') ch else '_'
            }
            .joinToString("")
            .trim('_')
            .take(48)
        return cleaned.ifBlank { "script" }
    }

    private fun uniqueScriptId(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired
        var index = 2
        while ("${desired}_$index" in taken) index++
        return "${desired}_$index"
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
                "content is required: describe functions, state machine, visual design and text; " +
                        "this fills the CONTENT section.",
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
                    "Follow this complete parameterized specification for single-file HTML, " +
                            "then submit new widgets using create_html_widget. To edit an existing component, " +
                            "read_component first and use update_component with the returned content hash, preserving its identity. " +
                            "Mandatory requirements are checked; validation failures do not save the requested edit.",
                )
                put("spec", prompt)
            }.toString(),
        )
    }

    private fun createHtmlWidget(args: JsonObject): ToolResult {        val html = args.string("html").orEmpty()
        if (html.isBlank()) {
            return invalidArgs("html is required: supply complete single-file HTML.")
        }

        val baseWidth = args.int("base_width") ?: DEFAULT_BASE_WIDTH
        val baseHeight = args.int("base_height") ?: DEFAULT_BASE_HEIGHT
        if (baseWidth <= baseHeight) {
            return invalidArgs(
                "Dimensions are reversed: landscape requires BASE_W > BASE_H (received " +
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
                            "Fix every reported HTML issue and retry; do not ignore any ERROR. " +
                            "Header numbers must match calculated LAYOUT values.",
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
            mutate = { current ->
                check(current.none { it.business == business }) { "Component identifier is no longer available." }
                check(!zipFile.exists()) { "Staging file already exists; refusing to overwrite it." }
                HtmlWidgetPackage.writeZip(zipFile, html)
                RearWidgetManagerRepository.saveBusinesses(
                    appContext,
                    prefs,
                    current + component,
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
                "note" to "Component name is $business ($requestedBusiness existed; suffix added).",
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
            return invalidArgs("title and business are required.")
        }

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        val component = components.firstOrNull { it.business == business }
            ?: return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "Component '$business' is not registered.",
                    "recovery" to "Call list_components and retry with an existing business.",
                ),
            )

        val cards = RearWidgetManagerRepository.loadCards(prefs)
        if (cards.any { it.business == business && it.title == title }) {
            return ToolResult.error(
                json(
                    "error" to "duplicate_card",
                    "detail" to "A card titled '$title' already uses component '$business'.",
                    "recovery" to "Choose another title or update the existing card using update_card.",
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
        if (id.isEmpty()) return invalidArgs("id is required; get it from list_cards.")

        writeGuard()?.let { return it }
        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val existing = cards.firstOrNull { it.id == id }
            ?: return ToolResult.error(
                json(
                    "error" to "card_not_found",
                    "detail" to "Card '$id' not found.",
                    "recovery" to "Call list_cards for a valid id.",
                ),
            )

        val changes = buildList {
            args.string("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { add("Set title to $it") }
            args.boolean("enabled")?.let { add(if (it) "enabled" else "disabled") }
            args.boolean("sticky")
                ?.let { add(if (it) "persistent display" else "disable persistence") }
            args.int("priority")?.let { add("Set priority to $it") }
        }
        if (changes.isEmpty()) {
            return invalidArgs("No changes supplied (title / enabled / sticky / priority).")
        }

        // Changing a store-installed card requires bypassing the locked merge, which is the same
        // escape hatch the store installer uses. Never do that silently.
        val touchesLocked = !existing.renameable
        val confirmed = args.boolean("confirm_locked_edit") ?: false
        if (touchesLocked && !confirmed) {
            return ToolResult.error(
                json(
                    "error" to "locked_card",
                    "detail" to "Card '${existing.title}' is repository-installed and protected from direct edits.",
                    "recovery" to "Explain the repository-installed card protection; retry with confirm_locked_edit=true only after explicit consent.",
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
                        after.sticky == updated.sticky &&
                    after.priority == updated.priority
            },
            detail = mapOf("card_id" to id, "changes" to changes.joinToString("、")),
        )
    }

    private fun deleteCard(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return invalidArgs("id is required; get it from list_cards.")

        writeGuard()?.let { return it }
        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val existing = cards.firstOrNull { it.id == id }
            ?: return ToolResult.error(
                json("error" to "card_not_found", "detail" to "Card '$id' not found."),
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
        if (id.isEmpty() || enabled == null) return invalidArgs("id and enabled are required.")

        writeGuard()?.let { return it }
        val before = RearWidgetManagerRepository.loadCards(prefs).firstOrNull { it.id == id }
            ?: return ToolResult.error(
                json("error" to "card_not_found", "detail" to "Card '$id' not found."),
            )
        if (before.enabled == enabled) {
            return ToolResult.ok(
                json(
                    "no_change" to true,
                    "detail" to "Card '${before.title}' already has this state."
                ),
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
                    "detail" to "Requested state ${if (enabled) "enabled" else "disabled"}, but read-back did not change.",
                    "recovery" to "The card may be locked (renameable=false). Check list_cards and inform the user.",
                ),
            )
        }
    }

    // ================================================================ components

    private fun renameComponent(args: JsonObject): ToolResult {
        val from = args.string("business")?.trim().orEmpty()
        val to = args.string("new_business")?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) return invalidArgs("business and new_business are required.")

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        val existing = components.firstOrNull { it.business == from }
            ?: return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "Component '$from' does not exist.",
                    "recovery" to "Call list_components first.",
                ),
            )
        if (components.any { it.business == to }) {
            return ToolResult.error(
                json(
                    "error" to "duplicate_component",
                    "detail" to "Component '$to' already exists."
                ),
            )
        }
        if (!existing.renameable) {
            return ToolResult.error(
                json(
                    "error" to "locked_component",
                    "detail" to "Component '$from' is locked (renameable=false) and cannot be renamed.",
                    "recovery" to "Confirmation cannot override this restriction. Do not retry renaming this component.",
                ),
            )
        }

        val cards = RearWidgetManagerRepository.loadCards(prefs)
        val affected = cards.filter { it.business == from }
        if (!canRenameComponent(existing.renameable, affected.map { it.renameable })) {
            return ToolResult.error(
                json(
                    "error" to "locked_dependent_card",
                    "detail" to "A locked card references this component; renaming would leave a partial migration.",
                    "recovery" to "Do not retry with a confirmation override. Keep the component identifier unchanged.",
                )
            )
        }

        return mutateWithVerification(
            action = "rename_component",
            mutate = { current ->
                val cardsNow = RearWidgetManagerRepository.loadCards(prefs)
                check(current.singleOrNull { it.business == from }?.renameable == true) {
                    "Component is missing or locked; no changes were made."
                }
                check(current.none { it.business == to }) { "Target component already exists." }
                check(
                    canRenameComponent(
                        true,
                        cardsNow.filter { it.business == from }.map { it.renameable })
                ) {
                    "A dependent card is locked; no changes were made."
                }
                RearWidgetManagerRepository.saveBusinesses(
                    appContext,
                    prefs,
                    current.map { if (it.business == from) it.copy(business = to) else it },
                )
                // A card points at its component by name, so renaming the component orphans every
                // card that referenced it unless they are updated in the same operation.
                if (cardsNow.any { it.business == from }) {
                    RearWidgetManagerRepository.saveCards(
                        appContext,
                        prefs,
                        cardsNow.map { if (it.business == from) it.copy(business = to) else it },
                    )
                }
            },
            readBack = { RearWidgetManagerRepository.loadBusinesses(prefs) },
            verify = { saved ->
                saved.any { it.business == to } && saved.none { it.business == from } &&
                        RearWidgetManagerRepository.loadCards(prefs).let { savedCards ->
                            savedCards.none { it.business == from } && affected.all { original ->
                                savedCards.any { it.id == original.id && it.business == to }
                            }
                        }
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
        if (business.isEmpty()) return invalidArgs("business is required.")

        writeGuard()?.let { return it }
        val components = RearWidgetManagerRepository.loadBusinesses(prefs)
        if (components.none { it.business == business }) {
            return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "Component '$business' does not exist."
                ),
            )
        }

        val dependent = RearWidgetManagerRepository.loadCards(prefs).filter { it.business == business }
        if (dependent.isNotEmpty() && !(args.boolean("confirm_orphan_cards") ?: false)) {
            return ToolResult.error(
                json(
                    "error" to "component_in_use",
                    "detail" to "This component is used by ${dependent.size} cards: ${
                        dependent.joinToString("、") { it.title }
                    }。",
                    "recovery" to "Explain these cards will stop working. Retry with confirm_orphan_cards=true only after consent, or remove dependent cards first.",
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
        val scene = RearWidgetSceneRouteSpec.normalizeScenePattern(args.string("scene").orEmpty())
        val business = args.string("business")?.trim().orEmpty()
        if (packageName.isEmpty() || scene.isEmpty() || business.isEmpty()) {
            return invalidArgs("package_name, scene and business are required.")
        }

        writeGuard()?.let { return it }
        if (RearWidgetManagerRepository.loadBusinesses(prefs).none { it.business == business }) {
            return ToolResult.error(
                json(
                    "error" to "component_not_found",
                    "detail" to "Target component '$business' is not registered.",
                    "recovery" to "Call list_components first.",
                ),
            )
        }

        // A malformed pattern is accepted and persisted by the module, then silently never matches.
        val regexError = validateScenePattern(scene)
        if (regexError != null) return invalidArgs(regexError)

        val route = hk.uwu.reareye.repository.rearwidget.RearWidgetSceneRouteConfig(
            id = RearWidgetConfigCodec.newSceneRouteId(packageName, scene),
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
            verify = { saved ->
                saved.any {
                    it.id == route.id && it.packageName == route.packageName &&
                            it.business == route.business && it.scene == route.scene
                }
            },
            detail = mapOf("package_name" to packageName, "scene" to scene, "business" to business),
        )
    }

    private fun deleteSceneRoute(args: JsonObject): ToolResult {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return invalidArgs("id is required; get it from list_scene_routes.")

        writeGuard()?.let { return it }
        val routes = RearWidgetManagerRepository.loadSceneRoutes(prefs)
        if (routes.none { it.id == id }) {
            return ToolResult.error(
                json(
                    "error" to "route_not_found",
                    "detail" to "Scene route '$id' not found.",
                    "recovery" to "Call list_scene_routes first.",
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
            ?: return invalidArgs("wallpaper_id is required; get it from list_wallpapers.")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        val target = catalog.wallpapers.firstOrNull { it.wallpaperId == id }
            ?: return ToolResult.error(
                json(
                    "error" to "wallpaper_not_found",
                    "detail" to "Wallpaper $id not found.",
                    "recovery" to "Call list_wallpapers for a valid id.",
                ),
            )

        val requested = RearWallpaperRepository.switchWallpaper(appContext, id)
        return if (requested) {
            ToolResult.ok(
                json(
                    "wallpaper_id" to id,
                    "title" to target.name,
                    "note" to "Switch request sent. Main-screen edit mode may silently ignore it despite API success; rely on what the user observes.",
                ),
            )
        } else {
            ToolResult.error(
                json(
                    "error" to "switch_failed",
                    "detail" to "Wallpaper switch request was rejected.",
                    "recovery" to "Rear-screen center (com.xiaomi.subscreencenter) may not be running; call get_module_status.",
                ),
            )
        }
    }

    private suspend fun setWallpaperRotation(args: JsonObject): ToolResult {
        val id = args.int("wallpaper_id")
            ?: return invalidArgs("wallpaper_id is required.")
        val inRotation = args.boolean("in_rotation")
            ?: return invalidArgs("in_rotation is required (true adds, false removes).")
        val intervalMs = args.int("interval_ms")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        if (catalog.wallpapers.none { it.wallpaperId == id }) {
            return ToolResult.error(
                json("error" to "wallpaper_not_found", "detail" to "Wallpaper $id not found."),
            )
        }

        val current = RearWallpaperRepository.loadSchedule(prefs)
        val next = updatedMaryWallpaperSchedule(current, id, inRotation, intervalMs?.toLong())

        RearWallpaperRepository.saveSchedule(prefs, next)
        val enabled = RearWallpaperRepository.isScheduleEnabled(prefs) && next.isNotEmpty()
        RearWallpaperRepository.setScheduleEnabled(prefs, enabled)
        RearWallpaperRepository.syncSchedule(appContext, enabled, next)

        val saved = RearWallpaperRepository.loadSchedule(prefs)
        val ok = saved == next && RearWallpaperRepository.isScheduleEnabled(prefs) == enabled
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
                    "detail" to "Rotation read-back does not match requested changes.",
                ),
            )
        }
    }

    private suspend fun setRotationEnabled(args: JsonObject): ToolResult {
        val enabled = args.boolean("enabled") ?: return invalidArgs("enabled is required.")
        writeGuard()?.let { return it }

        val schedule = RearWallpaperRepository.loadSchedule(prefs)
        if (enabled && schedule.isEmpty()) {
            return ToolResult.error(
                json(
                    "error" to "empty_rotation",
                    "detail" to "Cannot enable an empty rotation list.",
                    "recovery" to "Add a wallpaper using set_wallpaper_rotation first.",
                ),
            )
        }

        RearWallpaperRepository.setScheduleEnabled(prefs, enabled)
        RearWallpaperRepository.syncSchedule(appContext, enabled, schedule)

        val actual = RearWallpaperRepository.isScheduleEnabled(prefs)
        return if (actual == enabled) {
            ToolResult.ok(json("rotation_enabled" to actual, "rotation_count" to schedule.size.toString()))
        } else {
            ToolResult.error(
                json(
                    "error" to "verify_failed",
                    "detail" to "Rotation enabled-state verification failed."
                )
            )
        }
    }

    private suspend fun deleteWallpaper(args: JsonObject): ToolResult {
        val id = args.int("wallpaper_id") ?: return invalidArgs("wallpaper_id is required.")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        val target = catalog.wallpapers.firstOrNull { it.wallpaperId == id }
            ?: return ToolResult.error(
                json("error" to "wallpaper_not_found", "detail" to "Wallpaper $id not found."),
            )

        // `imported` is the only gate the module enforces for delete; checking it here turns an
        // opaque host-side refusal into an explanation the model can act on.
        if (!target.imported) {
            return ToolResult.error(
                json(
                    "error" to "not_imported",
                    "detail" to "Wallpaper '${target.name}' is not imported or repository-installed; system wallpapers cannot be deleted.",
                    "recovery" to "Tell the user this wallpaper cannot be deleted; do not retry.",
                ),
            )
        }

        val result = RearWallpaperRepository.deleteWallpaper(appContext, id)
        if (!result.success) {
            return ToolResult.error(
                json(
                    "error" to "delete_failed",
                    "detail" to (result.error ?: "The module rejected the deletion request."),
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
        val id = args.int("wallpaper_id") ?: return invalidArgs("wallpaper_id is required.")
        writeGuard()?.let { return it }

        val catalog = RearWallpaperRepository.loadCatalog(appContext)
        val target = catalog.wallpapers.firstOrNull { it.wallpaperId == id }
            ?: return ToolResult.error(
                json("error" to "wallpaper_not_found", "detail" to "Wallpaper $id not found."),
            )
        if (!target.canEditMetadata) {
            return ToolResult.error(
                json(
                    "error" to "not_editable",
                    "detail" to "Wallpaper '${target.name}' is not imported; metadata cannot be modified.",
                    "recovery" to "Tell the user this wallpaper cannot be edited; do not retry.",
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
                    "detail" to (result.error ?: "The module rejected the update request."),
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
                        "detail" to "Changes were submitted but read-back did not match. Module APIs can fail silently; do not claim success.",
                        "recovery" to "Read current state again and report accurately; do not claim success.",
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ToolResult.error(
                json(
                    "error" to "write_failed",
                    "action" to action,
                    "detail" to (t.message ?: t.javaClass.simpleName).take(300),
                    "recovery" to "The operation failed and may have partially changed state. Re-read current state and report accurately; do not assume rollback.",
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
                    "detail" to "Module configuration service is disconnected; cards, components and wallpapers cannot be read or modified.",
                    "recovery" to "Call get_module_status. Do not report empty configuration when it is actually unreadable.",
                ),
            )
        }

    /** Write operations need the same readiness check, but failing to write is more serious. */
    private fun writeGuard(): ToolResult? = requireReadable()

    private fun invalidArgs(detail: String): ToolResult = ToolResult.error(
        json(
            "error" to "invalid_args",
            "detail" to detail,
            "recovery" to "Supply valid required arguments and retry.",
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
        if (pattern.isEmpty()) return "Scene pattern is an empty regular expression."
        return runCatching { Regex(pattern) }
            .exceptionOrNull()
            ?.let { "Invalid scene regular expression (${it.message}). The module would accept but never match it; fix and retry." }
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

        /** Lua scripting: listing, reading, authoring and removing script projects. */
        const val LIST_SCRIPTS = "list_scripts"
        const val READ_SCRIPT = "read_script"
        const val GET_SCRIPT_SPEC = "get_script_spec"
        const val CREATE_SCRIPT = "create_script"
        const val DELETE_SCRIPT = "delete_script"

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

        val SCRIPT_MODEL_SPEC = """
            REAREye LuaJIT scripting API
            The entry must return a descriptor table. Example:
            local function hello(params, config, context)
                return "Hello " .. tostring(params.name or "REAREye")
            end
            return { id = "hello_script", name = "Hello Script", providers = { hello = hello } }

            Descriptor: optional id (default main filename; letters, digits, dot, underscore, hyphen),
            name (default project name), description, group; required providers map; optional configs array.
            Provider ids and config ids follow the same id character rule. Business data and OPTIONS
            values are not descriptor ids. Providers can be functions or tables with required handler,
            optional name (default provider id), description, mimeType (default text/plain), mode
            (IMMEDIATE, CACHE_FIRST, CACHE_ONLY, REFRESH) and ttlMillis (0..86400000).
            Handler signature: function(params, config, context) -> any. params are caller arguments;
            config contains normalized declared settings. Return strings, numbers, booleans, arrays,
            or tables with string keys.

            configs example: { { id="city", type="STRING", default="Shanghai", note="City" },
                { id="count", type="NUMBER", default=3, min=1, max=10, step=1 },
                { id="enabled", type="BOOLEAN", default=true },
                { id="unit", type="OPTIONS", default="celsius",
                  options={ {id="celsius",name="Celsius"}, {id="fahrenheit",name="Fahrenheit"} } } }
            STRING converts to text, default empty. NUMBER normalizes min/max/step, default 0.
            BOOLEAN accepts true or string "true". OPTIONS requires an option value, otherwise uses
            the first option. Do not confuse option values with config entry ids.

            context:now() returns host timestamp in milliseconds.
            context:log(message), or print(...), writes debug logs (4096 chars per entry, 200 per run).
            context:readText(path) reads UTF-8 project-root files. Path must be relative with no dot
            or parent segments, backslashes, absolute paths or drive letters. Not available to standalone scripts.
            Project require("a.b") searches a/b.lua then a/b/init.lua. Module names allow Lua
            identifiers and dots only, not absolute paths or traversal. Standalone scripts cannot
            load arbitrary project files.

            context.root:exec(command, options) executes su -c with user-granted root authorization.
            options: stdin (string), timeoutMillis (default 10000, range 100..120000).
            Returns exitCode, stdout, stderr, timedOut, outputLimitExceeded. stdout/stderr have host
            size limits. Nonzero exits do not throw automatically: inspect exitCode, timedOut, stderr.
            Root commands can be destructive; obtain explicit user consent and minimize privileges.

            context.http:request({url=..., method="GET", query={}, headers={}, body="...",
                timeoutMillis=15000, followRedirects=true, followSslRedirects=true}) uses host OkHttp.
            url is required and must be HTTP/HTTPS. Methods: GET, HEAD, POST, PUT, PATCH, DELETE.
            query/headers are string-keyed tables; query values are URL encoded and duplicate request
            headers replace prior values. body is a string. Timeout applies to connect/read/write/call
            and is clamped to 100..120000 ms. Both redirect flags default true.
            Returns statusCode, ok (2xx), headers (duplicate values comma-joined), body, contentType,
            url (final URL). Network errors throw Lua errors; 4xx/5xx return tables with ok=false.
            Shortcuts: context.http:get(url, options), :delete(url, options),
            :post(url, body, options), :put(url, body, options), :patch(url, body, options).
            Shortcut options match request query/headers/timeouts/redirects. For JSON set Content-Type
            and Accept headers explicitly and pass serialized body. Responses are not JSON-decoded;
            include a project Lua JSON module or let the caller interpret the declared mimeType.
            Use pcall for thrown network errors and inspect response.ok for HTTP failures.

            Debug UI executes providers directly. External ContentProvider forms:
            content://hk.uwu.reareye.script/{projectId}/{providerId}?name=REAREye
            content://hk.uwu.reareye.script/run?project=my_project&provider=hello&name=REAREye
            Result columns: project_id, provider_id, mime_type, data, error, elapsed_millis.
            Tool ids KIND:local_id are storage selection handles, not these runtime project ids.

            Limits: default execution 5 seconds, runtime maximum 30 seconds; project file maximum
            2 MiB; returned values maximum depth 24, 10000 elements and 262144 string characters.
            UTF-8 source only; bytecode is forbidden. io, package, debug, ffi, jit, java and luajava
            are disabled. os exposes only time/date/difftime/clock. Root and HTTP are host-injected;
            pure rear-script-runtime tests do not provide real root or network access.
            Prefer documented context APIs; never depend on Android internals, LuaJIT private APIs
            or host Java reflection. Static creation checks are not a Lua parser or sandbox;
            runtime descriptor validation remains authoritative. Test generated scripts in the UI.
        """.trimIndent()

        val ALL: List<ToolSpec> = run {
            val editing =
                MaryScriptEditingTools.SPECS + MaryComponentEditingTools.SPECS + MaryConfigEditingTools.SPECS
            check(editing.map { it.name }
                .distinct().size == editing.size) { "Duplicate editing tool names" }
            val replacements = editing.mapTo(mutableSetOf()) { it.name }
            (readTools() + writeTools()).filterNot { it.name in replacements } + editing
        }

        private fun readTools(): List<ToolSpec> = listOf(
            ToolSpec(
                name = GET_MODULE_STATUS,
                description = """
                    Read module activation, scope authorization and configuration availability before other operations. Returns activation_state, module_active, config_readable and explanation. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_COMPONENTS,
                description = """
                    List reusable component templates and dependent cards. Returns business, package_name, template_path, default_priority, renameable, from_store and used_by_cards. Use business when creating cards. Components are not app cards. config_unavailable means unreadable, not empty. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_CARDS,
                description = """
                    List display cards with id, title, package_name, business, enabled, sticky, priority, renameable, from_store and has_template_values. Get ids before changes. Not application launch entries. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_APP_CARDS,
                description = """
                    List application launch entries, not cards or components. Use this for rear-screen apps; never substitute list_cards or list_components. Returns total, offset, count, has_more, next_offset and app_cards (app_id, title, component_business, bind_package, optional can_rename/can_delete). Follow next_offset until has_more=false to list all. Default page size: $DEFAULT_APP_CARD_PAGE_SIZE. total=0 means empty. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
                params = listOf(
                    ToolParam(
                        "offset",
                        "Zero-based starting index; default 0",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                    ),
                    ToolParam(
                        "limit",
                        "Maximum entries; default $DEFAULT_APP_CARD_PAGE_SIZE",
                        ToolParam.ParamType.INTEGER,
                        required = false,
                    ),
                ),
            ),
            ToolSpec(
                name = REGISTER_APP_CARD,
                description = """
                    Create application launch entry from title and existing component_business from list_components. Does not create a display card. Returns ok and app_id. Never invent identifiers. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("title", "Display title", ToolParam.ParamType.STRING),
                    ToolParam(
                        "component_business",
                        "Existing component from list_components",
                        ToolParam.ParamType.STRING,
                    ),
                ),
            ),
            ToolSpec(
                name = RENAME_APP_CARD,
                description = """
                    Rename app_id from list_app_cards to title. Every app card can be renamed, including external apps that were not registered by REAREye. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "app_id",
                        "App card id from list_app_cards",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam("title", "Display title", ToolParam.ParamType.STRING),
                ),
            ),
            ToolSpec(
                name = DELETE_APP_CARD,
                description = """
                    Permanently delete app_id from list_app_cards. Confirm intended title first. Every app card can be deleted, including external apps that were not registered by REAREye. Requires explicit user confirmation.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                params = listOf(
                    ToolParam(
                        "app_id",
                        "App card id from list_app_cards",
                        ToolParam.ParamType.STRING
                    ),
                ),
            ),
            ToolSpec(
                name = REORDER_APP_CARDS,
                description = """
                    Reorder every current app_id exactly once, including external apps that were not registered by REAREye. Partial orders are rejected to prevent dropped entries. Fetch all list_app_cards pages first. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "app_ids",
                        "All app card ids in desired order",
                        ToolParam.ParamType.STRING_ARRAY,
                    ),
                ),
            ),
            ToolSpec(
                name = LIST_SCRIPTS,
                description = """
                    List Lua scripts without execution. Returns type-qualified id (KIND:local_id), local_id, name, kind (STANDALONE/PROJECT/COMPONENT), valid, component_id and error. Use returned ids for read/delete; ambiguous legacy ids rejected. Does not load providers. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = READ_SCRIPT,
                description = """
                    Read Lua source and static diagnostics without execution. Use type-qualified id from list_scripts. Legacy local ids must be unique across kinds. Returns id, name, kind, valid, issues and source. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
                params = listOf(
                    ToolParam(
                        "id",
                        "Exact id from the list tool; scripts use KIND:local_id",
                        ToolParam.ParamType.STRING
                    ),
                ),
            ),
            ToolSpec(
                name = GET_SCRIPT_SPEC,
                description = """
                    Fetch complete Lua API documentation before authoring or editing scripts. Follow documented APIs and runtime limits. Use create_script for new projects; read_script then update_script for existing files, preserving identity. Never invent APIs. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = CREATE_SCRIPT,
                description = """
                    Create standalone Lua from source after get_script_spec. name supplies filename unless optional id is given; collisions get suffixes. Reliable failures are errors; heuristic descriptor/id checks are warnings. No execution; authoritative validation occurs at runtime. Suggest script-screen testing. Returns type-qualified id. Generated code may access root or network when run, so explicit user confirmation is mandatory.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                params = listOf(
                    ToolParam("source", "Complete Lua source", ToolParam.ParamType.STRING),
                    ToolParam(
                        "name",
                        "Display name used to derive filename",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "id",
                        "Exact id from the list tool; scripts use KIND:local_id",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                ),
            ),
            ToolSpec(
                name = DELETE_SCRIPT,
                description = """
                    Permanently delete type-qualified id from list_scripts. Ambiguous legacy ids rejected. COMPONENT scripts cannot be deleted independently; explain removal with owning component and do not retry. Confirm intended name. Requires explicit user confirmation.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                params = listOf(
                    ToolParam(
                        "id",
                        "Exact id from the list tool; scripts use KIND:local_id",
                        ToolParam.ParamType.STRING
                    ),
                ),
            ),
            ToolSpec(
                name = GET_WIDGET_SPEC,
                description = """
                    Get HTML widget specification before generation. content describes functions, state machine, visuals, interactions and text in desired UI language. Optional app_name and dimensions default to $DEFAULT_BASE_WIDTH x $DEFAULT_BASE_HEIGHT. Follow spec then create_html_widget for new widgets, or read_component/update_component to edit an existing one in place. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
                params = listOf(
                    ToolParam(
                        "content",
                        "Functions, state machine, visuals, interactions and UI text",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "app_name",
                        "Application name",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "base_width",
                        "Design width; default $DEFAULT_BASE_WIDTH",
                        ToolParam.ParamType.INTEGER,
                        required = false
                    ),
                    ToolParam(
                        "base_height",
                        "Design height; default $DEFAULT_BASE_HEIGHT",
                        ToolParam.ParamType.INTEGER,
                        required = false
                    ),
                ),
            ),
            ToolSpec(
                name = CREATE_HTML_WIDGET,
                description = """
                    Create a new component from complete html and title after get_widget_spec. Follow layout, safe-area, touch, lifecycle and header rules. Optional business is made unique. Dimensions default to $DEFAULT_BASE_WIDTH x $DEFAULT_BASE_HEIGHT; width must exceed height. Validation failure creates nothing; fix all errors. Returns business; then register_app_card or create_card creates display entries. Generated active content requires explicit user confirmation.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                // The result echoes back the submitted HTML, which is large by nature. The global
                // cap would cut it in half and hand the model broken JSON to reason about.
                maxResultChars = 200_000,
                params = listOf(
                    ToolParam("html", "Complete single-file HTML", ToolParam.ParamType.STRING),
                    ToolParam("title", "Display title", ToolParam.ParamType.STRING),
                    ToolParam(
                        "business",
                        "Component identifier",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "base_width",
                        "Design width; default $DEFAULT_BASE_WIDTH",
                        ToolParam.ParamType.INTEGER,
                        required = false
                    ),
                    ToolParam(
                        "base_height",
                        "Design height; default $DEFAULT_BASE_HEIGHT",
                        ToolParam.ParamType.INTEGER,
                        required = false
                    ),
                ),
            ),
            ToolSpec(
                name = LIST_SCENE_ROUTES,
                description = """
                    List scene routes with id, package_name, scene and business. Excludes system-level notification routing. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
            ToolSpec(
                name = LIST_WALLPAPERS,
                description = """
                    List wallpapers, current_wallpaper_id, rotation_enabled and rotation entries (wallpaper_id, interval_ms). Inspect imported/can_edit_metadata before edits; system wallpapers cannot be deleted. Read-only.
                """.trimIndent(),
                risk = ToolRisk.READ_ONLY,
            ),
        )

        private fun writeTools(): List<ToolSpec> = listOf(
            ToolSpec(
                name = CREATE_CARD,
                description = """
                    Create card from title and existing business from list_components. package_name defaults to component package, enabled/sticky to true, priority to component default. Duplicate title/business rejected. Returns card_id. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("title", "Display title", ToolParam.ParamType.STRING),
                    ToolParam("business", "Component identifier", ToolParam.ParamType.STRING),
                    ToolParam(
                        "package_name",
                        "Application package name",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "enabled",
                        "Whether enabled",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                    ToolParam(
                        "sticky",
                        "Whether persistently displayed",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                    ToolParam(
                        "priority",
                        "Priority; larger values appear first",
                        ToolParam.ParamType.INTEGER,
                        required = false
                    ),
                ),
            ),
            ToolSpec(
                name = UPDATE_CARD,
                description = """
                    Update supplied title/enabled/sticky/priority fields of id from list_cards; at least one required. For renameable=false cards, explain protection and get explicit consent before confirm_locked_edit=true. Never bypass protection yourself. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "id",
                        "Exact id from the list tool; scripts use KIND:local_id",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam(
                        "title",
                        "Display title",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "enabled",
                        "Whether enabled",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                    ToolParam(
                        "sticky",
                        "Whether persistently displayed",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                    ToolParam(
                        "priority",
                        "Priority; larger values appear first",
                        ToolParam.ParamType.INTEGER,
                        required = false
                    ),
                    ToolParam(
                        "confirm_locked_edit",
                        "Explicit user consent to modify a locked card",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                ),
            ),
            ToolSpec(
                name = DELETE_CARD,
                description = """
                    Permanently delete id from list_cards. Confirm intended title; never guess. Requires explicit user confirmation.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                params = listOf(
                    ToolParam(
                        "id",
                        "Exact id from the list tool; scripts use KIND:local_id",
                        ToolParam.ParamType.STRING
                    )
                ),
            ),
            ToolSpec(
                name = SET_CARD_ENABLED,
                description = """
                    Set enabled for id from list_cards. Locked cards may reject changes; report verification failure honestly. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "id",
                        "Exact id from the list tool; scripts use KIND:local_id",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam("enabled", "Whether enabled", ToolParam.ParamType.BOOLEAN),
                ),
            ),
            ToolSpec(
                name = RENAME_COMPONENT,
                description = """
                    Rename business to unused new_business and migrate cards. renameable=false components can never be renamed. Locked dependent cards block writing. Writes are not atomic; never report verification failure as success. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam("business", "Component identifier", ToolParam.ParamType.STRING),
                    ToolParam(
                        "new_business",
                        "New unused component identifier",
                        ToolParam.ParamType.STRING
                    ),
                ),
            ),
            ToolSpec(
                name = DELETE_COMPONENT,
                description = """
                    Delete business. Dependent cards become unusable, not deleted. Explain component_in_use and obtain explicit consent before confirm_orphan_cards=true, or remove dependent cards first. Requires explicit user confirmation.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                params = listOf(
                    ToolParam("business", "Component identifier", ToolParam.ParamType.STRING),
                    ToolParam(
                        "confirm_orphan_cards",
                        "Explicit consent to make dependent cards unusable",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                ),
            ),
            ToolSpec(
                name = REGISTER_SCENE_ROUTE,
                description = """
                    Route package_name and scene to existing business from list_components. scene accepts plain names or regex prefixed regex:, re: or /.../. Invalid regex rejected before writing. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "package_name",
                        "Application package name",
                        ToolParam.ParamType.STRING
                    ),
                    ToolParam("scene", "Scene name or regex", ToolParam.ParamType.STRING),
                    ToolParam("business", "Component identifier", ToolParam.ParamType.STRING),
                ),
            ),
            ToolSpec(
                name = DELETE_SCENE_ROUTE,
                description = """
                    Delete id from list_scene_routes. Requires explicit user confirmation.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                params = listOf(
                    ToolParam(
                        "id",
                        "Exact id from the list tool; scripts use KIND:local_id",
                        ToolParam.ParamType.STRING
                    )
                ),
            ),
            ToolSpec(
                name = SWITCH_WALLPAPER,
                description = """
                    Request wallpaper_id from list_wallpapers. Main-screen edit mode may silently ignore API success. Report request sent, not visible success. Inactive rear-screen host may also fail. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "wallpaper_id",
                        "Wallpaper id from list_wallpapers",
                        ToolParam.ParamType.INTEGER
                    )
                ),
            ),
            ToolSpec(
                name = SET_WALLPAPER_ROTATION,
                description = """
                    Add/remove wallpaper_id using in_rotation. interval_ms defaults to 60000, minimum 100. New entries append; removing last disables rotation. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "wallpaper_id",
                        "Wallpaper id from list_wallpapers",
                        ToolParam.ParamType.INTEGER
                    ),
                    ToolParam(
                        "in_rotation",
                        "True adds to rotation; false removes",
                        ToolParam.ParamType.BOOLEAN
                    ),
                    ToolParam(
                        "interval_ms",
                        "Rotation interval in milliseconds",
                        ToolParam.ParamType.INTEGER,
                        required = false
                    ),
                ),
            ),
            ToolSpec(
                name = SET_ROTATION_ENABLED,
                description = """
                    Set enabled for rotation. Empty lists cannot be enabled; add wallpaper using set_wallpaper_rotation first. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "enabled",
                        "Whether enabled",
                        ToolParam.ParamType.BOOLEAN
                    )
                ),
            ),
            ToolSpec(
                name = DELETE_WALLPAPER,
                description = """
                    Permanently delete imported=true wallpaper_id and its rotation entries. System wallpapers return not_imported; do not retry. Requires explicit user confirmation.
                """.trimIndent(),
                risk = ToolRisk.DESTRUCTIVE,
                params = listOf(
                    ToolParam(
                        "wallpaper_id",
                        "Wallpaper id from list_wallpapers",
                        ToolParam.ParamType.INTEGER
                    )
                ),
            ),
            ToolSpec(
                name = UPDATE_WALLPAPER_METADATA,
                description = """
                    Update supplied metadata for wallpaper_id: title/title_zh, description/description_zh, author, designer, category, res_sub_type, editable, third_parties, support_aon. Empty strings preserve values. Requires can_edit_metadata=true (imported); do not retry not_editable. Requires user confirmation.
                """.trimIndent(),
                risk = ToolRisk.MUTATING,
                params = listOf(
                    ToolParam(
                        "wallpaper_id",
                        "Wallpaper id from list_wallpapers",
                        ToolParam.ParamType.INTEGER
                    ),
                    ToolParam(
                        "title",
                        "Display title",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "title_zh",
                        "Chinese title",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "description",
                        "Description in default language",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "description_zh",
                        "Chinese description",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam("author", "Author", ToolParam.ParamType.STRING, required = false),
                    ToolParam("designer", "Designer", ToolParam.ParamType.STRING, required = false),
                    ToolParam("category", "Category", ToolParam.ParamType.STRING, required = false),
                    ToolParam(
                        "res_sub_type",
                        "Resource subtype",
                        ToolParam.ParamType.STRING,
                        required = false
                    ),
                    ToolParam(
                        "editable",
                        "Whether editing is allowed",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                    ToolParam(
                        "third_parties",
                        "Third-party flag",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                    ToolParam(
                        "support_aon",
                        "Whether always-on display is supported",
                        ToolParam.ParamType.BOOLEAN,
                        required = false
                    ),
                ),
            ),
        )
    }
}

internal fun canRenameComponent(renameable: Boolean, dependentRenameable: List<Boolean>): Boolean =
    renameable && dependentRenameable.all { it }

internal fun qualifiedScriptId(kind: String, localId: String): String = "$kind:$localId"

/** Legacy ids are accepted only when the caller subsequently finds exactly one match. */
internal fun scriptIdMatches(requested: String, kind: String, localId: String): Boolean =
    if (':' in requested) requested == qualifiedScriptId(kind, localId) else requested == localId

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

/** Editing an existing rotation member must not silently move it or reset its delay. */
internal fun updatedMaryWallpaperSchedule(
    current: List<RearWallpaperScheduleEntry>,
    id: Int,
    inRotation: Boolean,
    intervalMs: Long?,
): List<RearWallpaperScheduleEntry> {
    if (!inRotation) return current.filterNot { it.wallpaperId == id }
    val previous = current.firstOrNull { it.wallpaperId == id }
    val delay = (intervalMs ?: previous?.delayMs ?: RearWallpaperScheduleCodec.DEFAULT_DELAY_MS)
        .coerceAtLeast(RearWallpaperScheduleCodec.MIN_DELAY_MS)
    val updated = RearWallpaperScheduleEntry(wallpaperId = id, delayMs = delay)
    return if (previous == null) current + updated else current.map { if (it.wallpaperId == id) updated else it }
}
