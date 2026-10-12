package hk.uwu.reareye.agent.data

import android.content.Context
import androidx.core.content.edit
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager

/**
 * Supplies agent configuration to the controller. Abstracted so the dialogue pipeline can be
 * exercised in tests without the module's RemotePreferences service.
 */
interface AgentSettingsSource {
    fun isReady(): Boolean
    fun load(): AgentConfig
    fun save(config: AgentConfig)
    fun saveApiKey(key: String): Boolean
    fun loadApiKey(): String
    fun isApiKeyUnreadable(): Boolean
}

/**
 * Persists the Mary agent configuration.
 *
 * Split deliberately across two stores:
 *  - non-sensitive settings (endpoint, model, limits) go to RemotePreferences, alongside the rest
 *    of the module configuration;
 *  - the API key goes to a private [android.content.SharedPreferences] file encrypted with a
 *    Keystore-backed AES-GCM key, because RemotePreferences is shared with the hook processes and
 *    is not a secret store.
 */
class AgentSettingsStore(private val context: Context) : AgentSettingsSource {

    private val appContext = context.applicationContext

    private val prefsManager: PrefsManager
        get() = appContext.getPrefsManager()

    private val secrets = appContext.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    /** True when the module's remote configuration service is reachable. Writes fail otherwise. */
    override fun isReady(): Boolean = runCatching { prefsManager.isRemoteReady() }.getOrDefault(false)

    override fun load(): AgentConfig {
        val prefs = prefsManager
        return AgentConfig(
            provider = ProviderConfig(
                baseUrl = prefs.getString(ConfigKeys.AGENT_BASE_URL, ProviderConfig.DEFAULT_BASE_URL),
                apiKey = loadApiKey(),
                model = prefs.getString(ConfigKeys.AGENT_MODEL),
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
            systemPrompt = prefs.getString(
                ConfigKeys.AGENT_SYSTEM_PROMPT,
                AgentConfig.DEFAULT_SYSTEM_PROMPT,
            ),
        )
    }

    /** Persists everything except the API key. Requires the remote preferences service. */
    override fun save(config: AgentConfig) {
        val prefs = prefsManager
        prefs.putString(ConfigKeys.AGENT_BASE_URL, config.provider.baseUrl.trim())
        prefs.putString(ConfigKeys.AGENT_MODEL, config.provider.model.trim())
        config.provider.temperature?.let {
            prefs.putFloat(ConfigKeys.AGENT_TEMPERATURE, it.toFloat())
        } ?: prefs.putFloat(ConfigKeys.AGENT_TEMPERATURE, Float.NaN)
        prefs.putInt(ConfigKeys.AGENT_MAX_OUTPUT_TOKENS, config.provider.maxOutputTokens ?: 0)
        prefs.putInt(ConfigKeys.AGENT_MAX_ITERATIONS, config.maxIterations)
        prefs.putInt(
            ConfigKeys.AGENT_MAX_CONSECUTIVE_TOOL_FAILURES,
            config.maxConsecutiveToolFailures,
        )
        prefs.putInt(
            ConfigKeys.AGENT_TURN_BUDGET_SECONDS,
            (config.turnBudgetMs / 1000).toInt(),
        )
        prefs.putInt(ConfigKeys.AGENT_MAX_TOOL_RESULT_CHARS, config.maxToolResultChars)
        prefs.putBoolean(ConfigKeys.AGENT_AUTO_APPROVE_READ_ONLY, config.autoApproveReadOnly)
        prefs.putString(ConfigKeys.AGENT_SYSTEM_PROMPT, config.systemPrompt)
    }

    /** Returns false when the keystore refused to encrypt, so the caller can warn the user. */
    override fun saveApiKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            secrets.edit { remove(KEY_API_KEY_CIPHERTEXT) }
            return true
        }
        val encrypted = KeystoreCrypto.encrypt(trimmed) ?: return false
        secrets.edit { putString(KEY_API_KEY_CIPHERTEXT, encrypted) }
        return true
    }

    /** Null when nothing is stored or the payload can no longer be decrypted. */
    override fun loadApiKey(): String {
        val encoded = secrets.getString(KEY_API_KEY_CIPHERTEXT, null) ?: return ""
        return KeystoreCrypto.decrypt(encoded).orEmpty()
    }

    /**
     * True when a key is stored but cannot be read 鈥?typically after a restore onto new hardware,
     * where the Keystore key no longer exists.
     */
    override fun isApiKeyUnreadable(): Boolean {
        val encoded = secrets.getString(KEY_API_KEY_CIPHERTEXT, null) ?: return false
        return KeystoreCrypto.decrypt(encoded) == null
    }

    private companion object {
        const val SECRETS_FILE = "reareye_agent_secrets"
        const val KEY_API_KEY_CIPHERTEXT = "api_key_ciphertext"
    }
}
