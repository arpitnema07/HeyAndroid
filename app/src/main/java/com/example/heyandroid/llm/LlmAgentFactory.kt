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

        val targets = mutableListOf<ModelTarget>()
        fun add(provider: LlmProvider, key: String, baseUrl: String, model: String) {
            if (key.isBlank() || model.isBlank()) return
            if (forImages && !readsImages(provider, model)) return
            if (targets.any { it.provider == provider && it.model == model }) return
            targets += ModelTarget(provider = provider, apiKey = key, baseUrl = baseUrl, model = model)
        }

        val home = config.provider
        if (forImages) {
            home.visionModel?.let { add(home, config.apiKey, config.baseUrl, it) }
        } else {
            add(home, config.apiKey, config.baseUrl, config.model)
        }
        // The rest of the selected provider: a retired or overloaded id is exactly
        // what these alternates exist for.
        settings.knownModels(home).take(MAX_PER_PROVIDER).forEach { model ->
            add(home, config.apiKey, config.baseUrl, model)
        }

        // A different vendor that already has a key is the strongest fallback: one
        // provider's outage cannot take every model down at once.
        LlmProvider.entries
            .filter { it != home && !it.isCustom }
            .forEach { provider ->
                val key = settings.apiKeyFor(provider)
                if (key.isBlank()) return@forEach
                if (forImages) {
                    provider.visionModel?.let { add(provider, key, provider.baseUrl, it) }
                    return@forEach
                }
                add(provider, key, provider.baseUrl, settings.modelFor(provider))
                settings.knownModels(provider).take(1).forEach { model ->
                    add(provider, key, provider.baseUrl, model)
                }
            }

        return targets.take(MAX_TARGETS)
    }

    private fun readsImages(provider: LlmProvider, model: String): Boolean =
        provider.freeModels.any { it.supportsImages && it.id == model }

    private companion object {
        /** Saved model plus four alternates: the floor the fallback keeps. */
        const val MAX_TARGETS = 5
        const val MAX_PER_PROVIDER = 4
    }
}
