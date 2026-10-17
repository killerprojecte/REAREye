package hk.uwu.reareye.agent.data

import android.content.Context
import androidx.core.content.edit
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Supplies agent configuration to the controller. Abstracted so the dialogue pipeline can be
 * exercised in tests without the module's RemotePreferences service.
 */
data class MarySettings(
    val configuration: AgentConfig = AgentConfig(),
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val inheritApprovalModel: Boolean = true,
    val approvalProvider: ProviderConfig = ProviderConfig(),
)

@Serializable
internal data class SettingsSnapshot(
    val configuration: AgentConfig,
    val approvalMode: String = "ASK",
    val inheritApprovalModel: Boolean = true,
    val approvalProvider: ProviderConfig = ProviderConfig(),
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
 * Canonical settings and two Keystore-encrypted credentials share one private preferences commit.
 * The JSON snapshot contains no plaintext credentials. RemotePreferences is only a legacy migration
 * source and is never written during a save, preventing cross-store partial updates.
 */
class AgentSettingsStore(private val context: Context) : AgentSettingsSource {

    private val appContext = context.applicationContext

    private val prefsManager: PrefsManager
        get() = appContext.getPrefsManager()

    private val secrets = appContext.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    /** Existing canonical settings remain usable if the legacy remote service is unavailable. */
    override fun isReady(): Boolean = secrets.contains(KEY_SETTINGS_SNAPSHOT) ||
            runCatching { prefsManager.isRemoteReady() }.getOrDefault(false)

    override fun load(): AgentConfig = synchronized(SETTINGS_LOCK) {
        readSnapshot()?.configuration ?: loadLegacyConfig()
    }

    private fun loadLegacyConfig(): AgentConfig {
        val prefs = prefsManager
        return AgentConfig(
            provider = ProviderConfig(
                baseUrl = prefs.getString(ConfigKeys.AGENT_BASE_URL, ProviderConfig.DEFAULT_BASE_URL),
                apiKey = loadApiKey(),
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
            if (next != previous) check(saveMarySettings(next)) { "Cannot save Mary settings" }
            next
        }

    private val snapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** SHA-256 of the entire pre-migration built-in prompt, not a substring/prefix match. */
    private fun migratePrompt(prompt: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return if (hash == "af4c93a1946ff793d2e5530eb946b136b6fdcfb08098ab4da2486f1c2119502c")
            AgentConfig.DEFAULT_SYSTEM_PROMPT else prompt
    }

    private fun readSnapshot(): MarySettings? {
        val encoded = secrets.getString(KEY_SETTINGS_SNAPSHOT, null) ?: return null
        // A corrupt canonical snapshot must never silently restore an old, more permissive policy.
        val snapshot = runCatching { snapshotJson.decodeFromString<SettingsSnapshot>(encoded) }
            .getOrElse { throw IllegalStateException("Mary settings snapshot is unreadable", it) }
        return MarySettings(
            configuration = snapshot.configuration.copy(
                provider = snapshot.configuration.provider.copy(apiKey = loadApiKey()),
                systemPrompt = migratePrompt(snapshot.configuration.systemPrompt),
            ),
            approvalMode = runCatching { ApprovalMode.valueOf(snapshot.approvalMode) }.getOrDefault(
                ApprovalMode.ASK
            ),
            inheritApprovalModel = snapshot.inheritApprovalModel,
            approvalProvider = snapshot.approvalProvider.copy(apiKey = loadApprovalApiKey()),
        )
    }

    override fun loadMarySettings(): MarySettings = synchronized(SETTINGS_LOCK) {
        readSnapshot()?.let { return@synchronized it }
        val prefs = prefsManager
        MarySettings(
            configuration = load(),
            // Missing/unknown values always migrate to ASK; legacy auto-read never grants AUTO.
            approvalMode = runCatching { ApprovalMode.valueOf(prefs.getString(ConfigKeys.AGENT_APPROVAL_MODE)) }
                .getOrDefault(ApprovalMode.ASK),
            inheritApprovalModel = prefs.getBoolean(ConfigKeys.AGENT_APPROVAL_INHERIT, true),
            approvalProvider = ProviderConfig(
                baseUrl = prefs.getString(
                    ConfigKeys.AGENT_APPROVAL_BASE_URL,
                    ProviderConfig.DEFAULT_BASE_URL
                ),
                apiKey = loadApprovalApiKey(),
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
    }

    /** Null key drafts preserve existing ciphertext. Encrypt BOTH keys before any write. */
    fun saveMarySettings(
        settings: MarySettings,
        dialogueApiKey: String? = null,
        approvalApiKey: String? = null
    ): Boolean = synchronized(SETTINGS_LOCK) {
        if (!isReady()) return@synchronized false
        val prepared =
            prepareSettingsWrite(settings, dialogueApiKey, approvalApiKey, KeystoreCrypto::encrypt)
                ?: return@synchronized false
        val dialogueCipher = prepared.dialogueCipher
        val approvalCipher = prepared.approvalCipher
        val encoded = snapshotJson.encodeToString(prepared.snapshot)
        val editor = secrets.edit().putString(KEY_SETTINGS_SNAPSHOT, encoded)
        if (dialogueApiKey != null) {
            if (dialogueCipher == null) editor.remove(KEY_API_KEY_CIPHERTEXT)
            else editor.putString(KEY_API_KEY_CIPHERTEXT, dialogueCipher)
        }
        if (approvalApiKey != null) {
            if (approvalCipher == null) editor.remove(KEY_APPROVAL_API_KEY_CIPHERTEXT)
            else editor.putString(KEY_APPROVAL_API_KEY_CIPHERTEXT, approvalCipher)
        }
        editor.commit()
    }

    fun loadApprovalApiKey(): String = secrets.getString(KEY_APPROVAL_API_KEY_CIPHERTEXT, null)
        ?.let { KeystoreCrypto.decrypt(it) }.orEmpty()

    fun isApprovalApiKeyUnreadable(): Boolean =
        secrets.getString(KEY_APPROVAL_API_KEY_CIPHERTEXT, null)
            ?.let { KeystoreCrypto.decrypt(it) == null } ?: false

    /** Returns false when the keystore refused to encrypt, so the caller can warn the user. */
    override fun saveApiKey(key: String): Boolean = synchronized(SETTINGS_LOCK) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            secrets.edit { remove(KEY_API_KEY_CIPHERTEXT) }
            return@synchronized true
        }
        val encrypted = KeystoreCrypto.encrypt(trimmed) ?: return@synchronized false
        secrets.edit { putString(KEY_API_KEY_CIPHERTEXT, encrypted) }
        true
    }

    /** Null when nothing is stored or the payload can no longer be decrypted. */
    override fun loadApiKey(): String {
        val encoded = secrets.getString(KEY_API_KEY_CIPHERTEXT, null) ?: return ""
        return KeystoreCrypto.decrypt(encoded).orEmpty()
    }

    /**
     * True when a key is stored but cannot be read, typically after a restore onto new hardware,
     * where the Keystore key no longer exists.
     */
    override fun isApiKeyUnreadable(): Boolean {
        val encoded = secrets.getString(KEY_API_KEY_CIPHERTEXT, null) ?: return false
        return KeystoreCrypto.decrypt(encoded) == null
    }

    private companion object {
        val SETTINGS_LOCK = Any()
        const val SECRETS_FILE = "reareye_agent_secrets"
        const val KEY_API_KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_APPROVAL_API_KEY_CIPHERTEXT = "approval_api_key_ciphertext"
        const val KEY_SETTINGS_SNAPSHOT = "settings_snapshot_v1"
    }
}
