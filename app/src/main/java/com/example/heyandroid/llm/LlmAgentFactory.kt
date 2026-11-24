package com.example.heyandroid.llm

import android.content.Context
import com.example.heyandroid.tools.ToolRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an agent for the saved provider and key, plus a short ladder of alternate
 * models the agent falls back to. Returns null when the user has not added a key
 * yet, so background work simply leaves captures pending.
 *
 * The ladder is the saved model first, then the rest of that provider's catalog,
 * then the saved model of every other provider that already has a key. One vendor
 * going down, retiring a model or timing out therefore costs a retry, not the turn.
 */
@Singleton
class LlmAgentFactory @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: LlmSettingsStore,
    private val tools: ToolRegistry
) {
    private val scoreboard = ModelScoreboard.getInstance(context)

    /** Active provider and model, for diagnostics in the Log tab. */
    val config: LlmConfig get() = settings.config.value

    /** Every model a turn may use, before the scoreboard orders them. */
    fun ladder(forImages: Boolean = false): List<ModelTarget> = buildLadder(forImages)

    fun create(): LlmAgent? = agent(forImages = false)

    /**
     * Agent for a turn with a photo attached. Photos need a multimodal model, so the
     * ladder only keeps models known to read images.
     */
    fun createForImages(): LlmAgent? = agent(forImages = true)

    private fun agent(forImages: Boolean): LlmAgent? {
        val targets = buildLadder(forImages)
        if (targets.isEmpty()) return null
        return LlmAgent(
            client = FallbackLlmClient(targets = targets, scoreboard = scoreboard),
            tools = tools
        )
    }

    private fun buildLadder(forImages: Boolean): List<ModelTarget> {
        val config = settings.config.value
        if (!config.isConfigured) return emptyList()
        return LadderBuilder.build(
            home = config.provider,
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            model = config.model,
            forImages = forImages,
            knownModels = settings::knownModels,
            apiKeyFor = settings::apiKeyFor,
            modelFor = settings::modelFor
        )
    }
}
