package com.example.heyandroid.llm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the assistant starts: listening, or with the keyboard ready. */
enum class InputMode {
    VOICE,
    KEYBOARD
}

/**
 * Active provider plus its credentials.
 */
data class LlmConfig(
    val provider: LlmProvider,
    val apiKey: String,
    val model: String,
    /** Where the requests go; a custom provider fills this in itself. */
    val baseUrl: String = provider.baseUrl
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()
}

/**
 * Per-device LLM settings. Keys are stored per provider so switching back and
 * forth does not lose them. SharedPreferences is app-private; there is no
 * sync or backup of this file.
 */
class LlmSettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<LlmConfig> = _config

    private val _inputMode = MutableStateFlow(
        if (prefs.getString("input_mode", null) == InputMode.KEYBOARD.name) {
            InputMode.KEYBOARD
        } else {
            InputMode.VOICE
        }
    )
    val inputMode: StateFlow<InputMode> = _inputMode

    fun setInputMode(mode: InputMode) {
        prefs.edit().putString("input_mode", mode.name).apply()
        _inputMode.value = mode
    }

    /**
     * Tavily key for the online search tool. Empty means web search is off; the
     * memory tools keep working offline either way.
     */
    private val _tavilyKey = MutableStateFlow(prefs.getString(TAVILY_KEY, "").orEmpty())
    val tavilyKey: StateFlow<String> = _tavilyKey

    fun setTavilyKey(key: String) {
        val value = key.trim()
        prefs.edit().putString(TAVILY_KEY, value).apply()
        _tavilyKey.value = value
    }

    /**
     * MCP (Model Context Protocol) server the assistant can call tools on, e.g. a
     * Google Workspace MCP endpoint. An empty URL means the connector is off.
     */
    private val _mcpUrl = MutableStateFlow(prefs.getString(MCP_URL, "").orEmpty())
    val mcpUrl: StateFlow<String> = _mcpUrl

    private val _mcpToken = MutableStateFlow(prefs.getString(MCP_TOKEN, "").orEmpty())
    val mcpToken: StateFlow<String> = _mcpToken

    fun setMcpConnection(url: String, token: String) {
        val cleanUrl = url.trim()
        val cleanToken = token.trim()
        prefs.edit().putString(MCP_URL, cleanUrl).putString(MCP_TOKEN, cleanToken).apply()
        _mcpUrl.value = cleanUrl
        _mcpToken.value = cleanToken
    }

    fun clearMcpConnection() = setMcpConnection("", "")

    private fun keyName(provider: LlmProvider) = "api_key_${provider.id}"
    private fun modelName(provider: LlmProvider) = "model_${provider.id}"
    private fun modelsName(provider: LlmProvider) = "models_${provider.id}"

    /**
     * Model ids read from each provider's own `/models` endpoint, keyed by
     * provider id. Empty for a provider that has never been refreshed, which is
     * when the built-in catalog is used instead.
     */
    private val _remoteModels = MutableStateFlow(loadRemoteModels())
    val remoteModels: StateFlow<Map<String, List<String>>> = _remoteModels

    private fun loadRemoteModels(): Map<String, List<String>> =
        LlmProvider.entries.associate { provider ->
            provider.id to prefs.getString(modelsName(provider), "").orEmpty()
                .split('\n')
                .filter { it.isNotBlank() }
        }.filterValues { it.isNotEmpty() }

    /**
     * Asks the endpoint which models this key may call and keeps the answer, so
     * the picker no longer offers ids the provider has retired (NVIDIA answers
     * 410 Gone for those).
     */
    suspend fun refreshModels(
        provider: LlmProvider,
        apiKey: String,
        baseUrl: String
    ): Result<Int> = runCatching {
        val ids = ModelCatalog.chatModels(
            ModelCatalog.fetch(
                baseUrl = baseUrl.ifBlank { provider.baseUrl },
                apiKey = apiKey.trim()
            )
        )
        if (ids.isEmpty()) error("The endpoint listed no chat models")
        prefs.edit().putString(modelsName(provider), ids.joinToString("\n")).apply()
        _remoteModels.value = loadRemoteModels()
        ids.size
    }

    /**
     * The endpoint of the custom provider: any OpenAI-compatible API. Kept apart
     * from the built-in entries, whose URLs are fixed in the catalog.
     */
    private val _customBaseUrl = MutableStateFlow(prefs.getString(CUSTOM_URL, "").orEmpty())
    val customBaseUrl: StateFlow<String> = _customBaseUrl

    fun setCustomBaseUrl(url: String) {
        val clean = url.trim()
        prefs.edit().putString(CUSTOM_URL, clean).apply()
        _customBaseUrl.value = clean
        _config.value = loadConfig()
    }

    private fun loadConfig(): LlmConfig {
        val provider = LlmProvider.fromId(prefs.getString("provider_id", null))
        val savedModel = prefs.getString(modelName(provider), null)
        // A model the user typed themselves is kept as-is; the lists are only
        // suggestions, so a newer id than the catalog can still be used.
        val model = savedModel?.takeIf { it.isNotBlank() } ?: provider.defaultModel
        return LlmConfig(
            provider = provider,
            apiKey = prefs.getString(keyName(provider), "").orEmpty(),
            model = model,
            baseUrl = if (provider.isCustom) {
                prefs.getString(CUSTOM_URL, "").orEmpty()
            } else {
                provider.baseUrl
            }
        )
    }

    fun selectProvider(provider: LlmProvider) {
        prefs.edit().putString("provider_id", provider.id).apply()
        _config.value = loadConfig()
    }

    /** The key saved for a provider, empty when it has never been set. */
    fun apiKeyFor(provider: LlmProvider): String =
        prefs.getString(keyName(provider), "").orEmpty()

    /** The model saved for a provider, or its catalog default. */
    fun modelFor(provider: LlmProvider): String =
        prefs.getString(modelName(provider), null)?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel

    /**
     * Every model id known for a provider: what its endpoint reported when the
     * list was refreshed, or the built-in catalog before that.
     */
    fun knownModels(provider: LlmProvider): List<String> =
        _remoteModels.value[provider.id] ?: provider.freeModels.map { it.id }

    fun setApiKey(provider: LlmProvider, apiKey: String) {
        prefs.edit().putString(keyName(provider), apiKey.trim()).apply()
        _config.value = loadConfig()
    }

    fun setModel(provider: LlmProvider, model: String) {
        val value = model.trim().ifBlank { provider.defaultModel }
        prefs.edit().putString(modelName(provider), value).apply()
        _config.value = loadConfig()
    }

    fun clearApiKey(provider: LlmProvider) {
        prefs.edit().remove(keyName(provider)).apply()
        _config.value = loadConfig()
    }

    companion object {
        @Volatile
        private var INSTANCE: LlmSettingsStore? = null

        private const val TAVILY_KEY = "tavily_api_key"
        private const val CUSTOM_URL = "custom_base_url"
        private const val MCP_URL = "mcp_url"
        private const val MCP_TOKEN = "mcp_token"

        fun getInstance(context: Context): LlmSettingsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmSettingsStore(context).also { INSTANCE = it }
            }
    }
}
