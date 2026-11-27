package hk.uwu.reareye.agent.data

import android.content.Context
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class MaryWebSearchEngine { BING }

data class MarySettings(
    val configuration: AgentConfig = AgentConfig(),
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val inheritApprovalModel: Boolean = true,
    val approvalProvider: ProviderConfig = ProviderConfig(),
    val agentMode: Boolean = true,
    val mainModelId: String? = null,
    val approvalModelId: String? = null,
    val subagentModelId: String? = null,
    val models: List<MaryModelConfig> = emptyList(),
    val providers: List<MaryProviderConfig> = emptyList(),
    private val providerKeys: Map<String, String> = emptyMap(),
    private val unreadableProviderKeys: Set<String> = emptySet(),
    val webEnabled: Boolean = true,
    val webSearchEngine: MaryWebSearchEngine = MaryWebSearchEngine.BING,
) {
    fun resolveProvider(id: String?): ProviderConfig? = providers.firstOrNull { it.id == id }
        ?.let { it.connection(providerKeys[it.id].orEmpty()) }

    fun resolveModel(id: String?): ProviderConfig? = models.firstOrNull { it.id == id }?.let { model ->
        resolveProvider(model.providerId)?.let(model::resolve)
    }

    fun isProviderKeyUnreadable(providerId: String): Boolean = providerId in unreadableProviderKeys
    fun isModelReferenced(modelId: String): Boolean = modelId == mainModelId || modelId == approvalModelId || modelId == subagentModelId
    fun isProviderReferenced(providerId: String): Boolean = models.any { it.providerId == providerId }

    internal fun projectLegacy() = copy(
        configuration = configuration.copy(provider = resolveModel(mainModelId) ?: ProviderConfig()),
        approvalProvider = resolveModel(approvalModelId) ?: ProviderConfig(),
    )

    override fun toString(): String = "MarySettings(agentMode=$agentMode, approvalMode=$approvalMode, providers=${providers.size}, models=${models.size})"
}

@Serializable
internal data class SettingsSnapshot(
    val configuration: AgentConfig,
    val approvalMode: String = "ASK",
    val inheritApprovalModel: Boolean = true,
    val approvalProvider: ProviderConfig = ProviderConfig(),
    val registry: MaryRegistrySnapshot? = null,
    val webEnabled: Boolean = true,
    // A removed or unknown engine must not make the rest of the snapshot unreadable.
    val webSearchEngine: String = MaryWebSearchEngine.BING.name,
)

internal data class PreparedSettingsWrite(
    val snapshot: SettingsSnapshot,
    val dialogueCipher: String?,
    val approvalCipher: String?
)

/** Pure preparation: no writes can happen if either encryption fails. Null drafts preserve keys. */
internal fun prepareSettingsWrite(
    settings: MarySettings,
    dialogueKey: String?,
    approvalKey: String?,
    encrypt: (String) -> String?,
): PreparedSettingsWrite? {
    val dialogueCipher =
        dialogueKey?.trim()?.takeIf { it.isNotEmpty() }?.let { encrypt(it) ?: return null }
    val approvalCipher =
        approvalKey?.trim()?.takeIf { it.isNotEmpty() }?.let { encrypt(it) ?: return null }
    return PreparedSettingsWrite(
        SettingsSnapshot(
            configuration = settings.configuration.copy(
                provider = settings.configuration.provider.copy(
                    apiKey = "",
                    credentialScopeTag = null
                )
            ),
            approvalMode = settings.approvalMode.name,
            inheritApprovalModel = settings.inheritApprovalModel,
            approvalProvider = settings.approvalProvider.copy(
                apiKey = "",
                credentialScopeTag = null
            ),
            webEnabled = settings.webEnabled,
            webSearchEngine = settings.webSearchEngine.name,
        ), dialogueCipher, approvalCipher
    )
}

interface AgentSettingsSource {
    fun loadMarySettings(): MarySettings = MarySettings(configuration = load())

    /** Legacy sources support config-only updates; never silently discard approval changes. */
    fun updateMarySettings(update: (MarySettings) -> MarySettings): MarySettings =
        synchronized(this) {
            val previous = loadMarySettings()
            val next = update(previous)
            check(next.copy(configuration = previous.configuration) == previous) {
                "This settings source does not support approval updates"
            }
            if (next != previous) save(next.configuration)
            next
        }
    fun isReady(): Boolean
    fun load(): AgentConfig
    fun save(config: AgentConfig)
    fun saveApiKey(key: String): Boolean
    fun loadApiKey(): String
    fun isApiKeyUnreadable(): Boolean
}

/**
 * The registry, role selections and per-provider encrypted credentials share one private preferences commit.
 * JSON never contains credentials. RemotePreferences remains a read-only migration source.
 */
class AgentSettingsStore internal constructor(
    context: Context,
    private val encryptKey: (String) -> String?,
    private val decryptKey: (String) -> String?,
    private val legacyPreferences: (() -> PrefsManager)? = null,
) : AgentSettingsSource {
    constructor(context: Context) : this(context, KeystoreCrypto::encrypt, KeystoreCrypto::decrypt)

    private val appContext = context.applicationContext

    private val prefsManager: PrefsManager
        get() = legacyPreferences?.invoke() ?: appContext.getPrefsManager()

    private val secrets = appContext.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    /** Existing canonical settings remain usable if the legacy remote service is unavailable. */
    override fun isReady(): Boolean = secrets.contains(KEY_SETTINGS_SNAPSHOT) ||
            runCatching { prefsManager.isRemoteReady() }.getOrDefault(false)

    override fun load(): AgentConfig = loadMarySettings().configuration

    private fun loadLegacyConfig(prefs: PrefsManager): AgentConfig {
        return AgentConfig(
            provider = ProviderConfig(
                baseUrl = prefs.getString(ConfigKeys.AGENT_BASE_URL, ProviderConfig.DEFAULT_BASE_URL),
                model = prefs.getString(ConfigKeys.AGENT_MODEL),
                protocol = runCatching { Protocol.valueOf(prefs.getString(ConfigKeys.AGENT_PROTOCOL)) }
                    .getOrDefault(Protocol.CHAT_COMPLETIONS),
                contextWindowTokens = prefs.getInt(ConfigKeys.AGENT_CONTEXT_WINDOW, 128000)
                    .coerceAtLeast(1),
                temperature = prefs.getFloat(ConfigKeys.AGENT_TEMPERATURE, Float.NaN)
                    .takeUnless { it.isNaN() }?.toDouble(),
                maxOutputTokens = prefs.getInt(ConfigKeys.AGENT_MAX_OUTPUT_TOKENS, 0)
                    .takeIf { it > 0 },
            ),
            maxIterations = prefs.getInt(
                ConfigKeys.AGENT_MAX_ITERATIONS,
                AgentConfig.DEFAULT_MAX_ITERATIONS,
            ),
            maxToolResultChars = prefs.getInt(
                ConfigKeys.AGENT_MAX_TOOL_RESULT_CHARS,
                AgentConfig.DEFAULT_MAX_TOOL_RESULT_CHARS,
            ),
            maxConsecutiveToolFailures = prefs.getInt(
                ConfigKeys.AGENT_MAX_CONSECUTIVE_TOOL_FAILURES,
                AgentConfig.DEFAULT_MAX_CONSECUTIVE_TOOL_FAILURES,
            ),
            turnBudgetMs = prefs.getInt(
                ConfigKeys.AGENT_TURN_BUDGET_SECONDS,
                (AgentConfig.DEFAULT_TURN_BUDGET_MS / 1000).toInt(),
            ).toLong() * 1000,
            autoApproveReadOnly = prefs.getBoolean(ConfigKeys.AGENT_AUTO_APPROVE_READ_ONLY, true),
            systemPrompt = migratePrompt(
                prefs.getString(
                ConfigKeys.AGENT_SYSTEM_PROMPT,
                AgentConfig.DEFAULT_SYSTEM_PROMPT,
                )
            ),
        )
    }

    /** Preserves the remaining Mary settings and the separately encrypted secrets. */
    override fun save(config: AgentConfig) {
        updateMarySettings { it.copy(configuration = config) }
    }

    /** One read-modify-commit transaction, shared by every store instance in this process. */
    override fun updateMarySettings(update: (MarySettings) -> MarySettings): MarySettings =
        synchronized(SETTINGS_LOCK) {
            val previous = loadMarySettings()
            val next = update(previous)
            if (next == previous) return@synchronized previous
            val merged = mergeLegacy(previous, next) ?: error("Conflicting edits to the same model")
            check(isReady() && commitRegistry(merged.settings, previous, emptyMap(), merged.copiedKeys)) {
                "Cannot save Mary settings"
            }
            loadMarySettings()
        }

    private val snapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** SHA-256 of the entire pre-migration built-in prompt, not a substring/prefix match. */
    private fun migratePrompt(prompt: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return if (hash == "af4c93a1946ff793d2e5530eb946b136b6fdcfb08098ab4da2486f1c2119502c")
            AgentConfig.DEFAULT_SYSTEM_PROMPT else prompt
    }

    private data class SnapshotRead(val encoded: String, val result: Result<SettingsSnapshot>)
    private var snapshotRead: SnapshotRead? = null

    // Only called under SETTINGS_LOCK. An encoded value also detects commits by other store instances.
    private fun readSettingsSnapshot(): SettingsSnapshot? {
        val encoded = secrets.getString(KEY_SETTINGS_SNAPSHOT, null) ?: return null
        val cached = snapshotRead?.takeIf { it.encoded == encoded } ?: SnapshotRead(encoded, runCatching {
            snapshotJson.decodeFromString<SettingsSnapshot>(encoded).also { it.registry?.validate() }
        }).also { snapshotRead = it }
        return cached.result.getOrElse { throw IllegalStateException("Mary settings snapshot is unreadable", it) }
    }

    /** Live web policy reads only the canonical local snapshot, never legacy remote prefs or keys. */
    fun webToolsEnabled(): Boolean = synchronized(SETTINGS_LOCK) {
        runCatching { readSettingsSnapshot()?.webEnabled ?: true }.getOrDefault(false)
    }

    private fun readSnapshot(): MarySettings? {
        val snapshot = readSettingsSnapshot() ?: return null
        val registry = snapshot.registry?.withoutUnusedApprovalPlaceholder(
            snapshot.inheritApprovalModel && snapshot.approvalProvider == ProviderConfig(),
            secrets.contains(KEY_APPROVAL_API_KEY_CIPHERTEXT),
        ) ?: legacyRegistry(snapshot.configuration.provider,
            snapshot.approvalProvider.takeUnless { snapshot.inheritApprovalModel })
        return attachCredentials(MarySettings(
            configuration = snapshot.configuration.copy(systemPrompt = migratePrompt(snapshot.configuration.systemPrompt)),
            approvalMode = runCatching { ApprovalMode.valueOf(snapshot.approvalMode) }.getOrDefault(ApprovalMode.ASK),
            inheritApprovalModel = snapshot.inheritApprovalModel,
            webEnabled = snapshot.webEnabled,
            webSearchEngine = MaryWebSearchEngine.entries.firstOrNull { it.name == snapshot.webSearchEngine }
                ?: MaryWebSearchEngine.BING,
        ).withRegistry(registry))
    }

    override fun loadMarySettings(): MarySettings = synchronized(SETTINGS_LOCK) {
        readSnapshot()?.let { return@synchronized it }
        val prefs = prefsManager
        val inheritApprovalModel = prefs.getBoolean(ConfigKeys.AGENT_APPROVAL_INHERIT, true)
        val legacy = MarySettings(
            configuration = loadLegacyConfig(prefs),
            // Missing/unknown values always migrate to ASK; legacy auto-read never grants AUTO.
            approvalMode = runCatching { ApprovalMode.valueOf(prefs.getString(ConfigKeys.AGENT_APPROVAL_MODE)) }
                .getOrDefault(ApprovalMode.ASK),
            inheritApprovalModel = inheritApprovalModel,
            approvalProvider = if (inheritApprovalModel) ProviderConfig() else ProviderConfig(
                baseUrl = prefs.getString(
                    ConfigKeys.AGENT_APPROVAL_BASE_URL,
                    ProviderConfig.DEFAULT_BASE_URL
                ),
                model = prefs.getString(ConfigKeys.AGENT_APPROVAL_MODEL),
                protocol = runCatching { Protocol.valueOf(prefs.getString(ConfigKeys.AGENT_APPROVAL_PROTOCOL)) }
                    .getOrDefault(Protocol.CHAT_COMPLETIONS),
                contextWindowTokens = prefs.getInt(ConfigKeys.AGENT_APPROVAL_CONTEXT_WINDOW, 128000)
                    .coerceAtLeast(1),
                temperature = prefs.getFloat(ConfigKeys.AGENT_APPROVAL_TEMPERATURE, Float.NaN)
                    .takeUnless { it.isNaN() }?.toDouble(),
                maxOutputTokens = prefs.getInt(ConfigKeys.AGENT_APPROVAL_MAX_OUTPUT_TOKENS, 0)
                    .takeIf { it > 0 },
            ),
        )
        attachCredentials(legacy.withRegistry(legacyRegistry(legacy.configuration.provider,
            legacy.approvalProvider.takeUnless { inheritApprovalModel })))
    }

    /** Omitted keys are preserved; an explicit blank clears the key. Reject stale user drafts. */
    fun saveRegistrySettings(
        settings: MarySettings,
        providerApiKeys: Map<String, String> = emptyMap(),
        expected: MarySettings? = null,
    ): Boolean = synchronized(SETTINGS_LOCK) {
        if (!isReady()) return@synchronized false
        val previous = loadMarySettings()
        if (expected != null && expected != previous) return@synchronized false
        commitRegistry(settings, previous, providerApiKeys)
    }

    /** Compatibility for callers editing the old effective configuration, not the registry. */
    fun saveMarySettings(
        settings: MarySettings,
        dialogueApiKey: String? = null,
        approvalApiKey: String? = null,
    ): Boolean = synchronized(SETTINGS_LOCK) {
        if (!isReady()) return@synchronized false
        val previous = loadMarySettings()
        val merged = mergeLegacy(previous, settings, preserveRegistryForLegacyCaller = true) ?: return@synchronized false
        val next = merged.settings
        val updates = mutableMapOf<String, String>()
        for ((modelId, key) in listOf(next.mainModelId to dialogueApiKey, next.approvalModelId to approvalApiKey)) {
            if (key == null) continue
            val providerId = next.providerId(modelId) ?: return@synchronized false
            if (updates[providerId]?.let { it != key.trim() } == true) return@synchronized false
            updates[providerId] = key.trim()
        }
        commitRegistry(next, previous, updates, merged.copiedKeys)
    }

    private data class MergedLegacy(val settings: MarySettings, val copiedKeys: Map<String, String>)

    private fun mergeLegacy(
        previous: MarySettings,
        proposal: MarySettings,
        preserveRegistryForLegacyCaller: Boolean = false,
    ): MergedLegacy? {
        var next = if (preserveRegistryForLegacyCaller && proposal.providers.isEmpty() && proposal.models.isEmpty() &&
            proposal.mainModelId == null && proposal.approvalModelId == null && proposal.subagentModelId == null) {
            proposal.withRegistry(previous.registrySnapshot().copy(agentMode = proposal.agentMode))
        } else proposal
        val copiedKeys = mutableMapOf<String, String>()
        val main = proposal.configuration.provider.withoutSecrets()
        val approval = proposal.approvalProvider.withoutSecrets()
        val mainChanged = next.mainModelId == previous.mainModelId && main != previous.configuration.provider.withoutSecrets() &&
            next.models.firstOrNull { it.id == next.mainModelId } == previous.models.firstOrNull { it.id == previous.mainModelId }
        val approvalChanged = next.approvalModelId == previous.approvalModelId && approval != previous.approvalProvider.withoutSecrets() &&
            next.models.firstOrNull { it.id == next.approvalModelId } == previous.models.firstOrNull { it.id == previous.approvalModelId }
        if (mainChanged && approvalChanged && next.mainModelId != null && next.mainModelId == next.approvalModelId && main != approval) return null

        fun editRole(id: String?, config: ProviderConfig): String {
            val existing = next.models.firstOrNull { it.id == id }
            val provider = next.providers.firstOrNull { it.id == existing?.providerId }
            var providerId = provider?.id ?: newMaryRegistryId()
            if (provider == null) {
                next = next.copy(providers = next.providers + MaryProviderConfig(providerId,
                    baseUrl = config.baseUrl, protocol = config.protocol))
            } else if (provider.baseUrl != config.baseUrl || provider.protocol != config.protocol) {
                // Legacy callers edit just their selected role. Do not reroute other models
                // which happen to share its provider; copy the encrypted credential as-is.
                if (next.models.count { it.providerId == provider.id } > 1) {
                    providerId = newMaryRegistryId()
                    secrets.getString(credentialKey(provider.id), null)?.let { copiedKeys[providerId] = it }
                    next = next.copy(providers = next.providers + provider.copy(id = providerId,
                        baseUrl = config.baseUrl, protocol = config.protocol, modelCount = null))
                } else next = next.copy(providers = next.providers.map {
                    if (it.id == provider.id) it.copy(baseUrl = config.baseUrl, protocol = config.protocol, modelCount = null) else it
                })
            }
            val updated = (existing ?: MaryModelConfig(newMaryRegistryId(), providerId, config.model))
                .copy(providerId = providerId).withLegacyProjection(config)
            next = next.copy(models = if (existing == null) next.models + updated else next.models.map {
                if (it.id == updated.id) updated else it
            })
            return updated.id
        }
        if (mainChanged) {
            val id = editRole(next.mainModelId, main)
            next = next.copy(mainModelId = id)
        }
        if (approvalChanged) {
            val id = editRole(next.approvalModelId, approval)
            next = next.copy(approvalModelId = id)
        }
        return MergedLegacy(next, copiedKeys)
    }

    private fun commitRegistry(settings: MarySettings, previous: MarySettings, updates: Map<String, String>,
        copiedKeys: Map<String, String> = emptyMap()): Boolean {
        val registry = settings.registrySnapshot()
        if (runCatching { registry.validate() }.isFailure) return false
        val providerIds = registry.providers.map { it.id }.toSet()
        if (!providerIds.containsAll(updates.keys) || !providerIds.containsAll(copiedKeys.keys)) return false
        val prepared = mutableMapOf<String, String?>()
        prepared.putAll(copiedKeys)
        for ((id, draft) in updates) {
            val key = draft.trim()
            prepared[id] = if (key.isEmpty()) null else (encryptKey(key) ?: return false)
        }
        val projected = settings.projectLegacy()
        val snapshot = SettingsSnapshot(
            configuration = projected.configuration.copy(provider = projected.configuration.provider.withoutSecrets()),
            approvalMode = projected.approvalMode.name,
            inheritApprovalModel = projected.inheritApprovalModel,
            approvalProvider = projected.approvalProvider.withoutSecrets(),
            registry = registry,
            webEnabled = projected.webEnabled,
            webSearchEngine = projected.webSearchEngine.name,
        )
        val editor = secrets.edit().putString(KEY_SETTINGS_SNAPSHOT, snapshotJson.encodeToString(snapshot))
        // Also remove dormant legacy credentials omitted by the first migration.
        (previous.providers.map { it.id } + setOf(LEGACY_DIALOGUE_PROVIDER_ID, LEGACY_APPROVAL_PROVIDER_ID))
            .filterNot { it in providerIds }.forEach { editor.remove(credentialKey(it)) }
        prepared.forEach { (id, ciphertext) ->
            val key = credentialKey(id)
            if (ciphertext == null) editor.remove(key) else editor.putString(key, ciphertext)
        }
        return editor.commit()
    }

    private fun attachCredentials(settings: MarySettings): MarySettings {
        val keys = mutableMapOf<String, String>()
        val unreadable = mutableSetOf<String>()
        settings.providers.forEach { provider ->
            secrets.getString(credentialKey(provider.id), null)?.let { encrypted ->
                val key = decryptKey(encrypted)
                if (key == null) unreadable += provider.id else keys[provider.id] = key
            }
        }
        return settings.copy(providerKeys = keys, unreadableProviderKeys = unreadable).projectLegacy()
    }

    fun loadApprovalApiKey(): String = synchronized(SETTINGS_LOCK) {
        if (isReady()) loadMarySettings().approvalProvider.apiKey else readUnmigratedKey(LEGACY_APPROVAL_PROVIDER_ID)
    }

    fun isApprovalApiKeyUnreadable(): Boolean = synchronized(SETTINGS_LOCK) {
        if (!isReady()) return@synchronized unreadableLegacyKey(LEGACY_APPROVAL_PROVIDER_ID)
        val settings = loadMarySettings()
        settings.providerId(settings.approvalModelId)?.let(settings::isProviderKeyUnreadable) ?: false
    }

    override fun saveApiKey(key: String): Boolean = synchronized(SETTINGS_LOCK) {
        val id = if (!isReady()) LEGACY_DIALOGUE_PROVIDER_ID else {
            val settings = loadMarySettings()
            settings.providerId(settings.mainModelId) ?: return@synchronized false
        }
        val trimmed = key.trim()
        val ciphertext = if (trimmed.isEmpty()) null else encryptKey(trimmed) ?: return@synchronized false
        val editor = secrets.edit()
        if (ciphertext == null) editor.remove(credentialKey(id)) else editor.putString(credentialKey(id), ciphertext)
        editor.commit()
    }

    override fun loadApiKey(): String = synchronized(SETTINGS_LOCK) {
        if (isReady()) load().provider.apiKey else readUnmigratedKey(LEGACY_DIALOGUE_PROVIDER_ID)
    }

    override fun isApiKeyUnreadable(): Boolean = synchronized(SETTINGS_LOCK) {
        if (!isReady()) return@synchronized unreadableLegacyKey(LEGACY_DIALOGUE_PROVIDER_ID)
        val settings = loadMarySettings()
        settings.providerId(settings.mainModelId)?.let(settings::isProviderKeyUnreadable) ?: false
    }

    private fun readUnmigratedKey(id: String) = secrets.getString(credentialKey(id), null)?.let(decryptKey).orEmpty()
    private fun unreadableLegacyKey(id: String) = secrets.getString(credentialKey(id), null)?.let { decryptKey(it) == null } == true
    private fun MarySettings.providerId(modelId: String?) = models.firstOrNull { it.id == modelId }?.providerId
    private fun ProviderConfig.withoutSecrets() = copy(apiKey = "", credentialScopeTag = null, reasoningCatalogCapability = null)
    private fun credentialKey(providerId: String): String = when (providerId) {
        LEGACY_DIALOGUE_PROVIDER_ID -> KEY_API_KEY_CIPHERTEXT
        LEGACY_APPROVAL_PROVIDER_ID -> KEY_APPROVAL_API_KEY_CIPHERTEXT
        else -> "provider_api_key_ciphertext.$providerId"
    }

    private companion object {
        val SETTINGS_LOCK = Any()
        const val SECRETS_FILE = "reareye_agent_secrets"
        const val KEY_API_KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_APPROVAL_API_KEY_CIPHERTEXT = "approval_api_key_ciphertext"
        const val KEY_SETTINGS_SNAPSHOT = "settings_snapshot_v1"
    }
}
