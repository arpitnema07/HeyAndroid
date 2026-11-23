package com.example.heyandroid.llm

import com.example.heyandroid.tools.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an agent for the currently saved provider and key. Returns null when the
 * user has not added a key yet, so background work simply leaves captures pending.
 */
@Singleton
class LlmAgentFactory @Inject constructor(
    private val settings: LlmSettingsStore,
    private val tools: ToolRegistry
) {
    /** Active provider and model, for diagnostics in the Log tab. */
    val config: LlmConfig get() = settings.config.value

    fun create(): LlmAgent? {
        val config = settings.config.value
        if (!config.isConfigured) return null
        return LlmAgent(
            client = LlmClientFactory.create(
                provider = config.provider,
                apiKey = config.apiKey,
                model = config.model
            ),
            tools = tools
        )
    }

    /**
     * Agent for a turn with a photo attached. Photos need a multimodal model, so this
     * switches to the provider's free vision model instead of the saved text model.
     */
    fun createForImages(): LlmAgent? {
        val config = settings.config.value
        if (!config.isConfigured) return null
        val model = config.provider.visionModel ?: return null
        return LlmAgent(
            client = LlmClientFactory.create(
                provider = config.provider,
                apiKey = config.apiKey,
                model = model
            ),
            tools = tools
        )
    }
}
