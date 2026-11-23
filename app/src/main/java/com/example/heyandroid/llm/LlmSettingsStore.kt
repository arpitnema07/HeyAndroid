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
    val model: String
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
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

    private fun loadConfig(): LlmConfig {
        val provider = LlmProvider.fromId(prefs.getString("provider_id", null))
        val savedModel = prefs.getString(modelName(provider), null)
        return LlmConfig(
            provider = provider,
            apiKey = prefs.getString(keyName(provider), "").orEmpty(),
            model = savedModel?.takeIf { provider.isFreeModel(it) } ?: provider.defaultModel
        )
    }

    fun selectProvider(provider: LlmProvider) {
        prefs.edit().putString("provider_id", provider.id).apply()
        _config.value = loadConfig()
    }

    fun setApiKey(provider: LlmProvider, apiKey: String) {
        prefs.edit().putString(keyName(provider), apiKey.trim()).apply()
        _config.value = loadConfig()
    }

    fun setModel(provider: LlmProvider, model: String) {
        val value = model.trim().takeIf { provider.isFreeModel(it) } ?: provider.defaultModel
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
        private const val MCP_URL = "mcp_url"
        private const val MCP_TOKEN = "mcp_token"

        fun getInstance(context: Context): LlmSettingsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmSettingsStore(context).also { INSTANCE = it }
            }
    }
}
