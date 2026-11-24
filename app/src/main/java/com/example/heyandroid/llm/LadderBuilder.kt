package com.example.heyandroid.llm

/**
 * Builds the fallback ladder: the saved model, then the rest of that provider's
 * catalog, then the saved model of every other provider that already has a key.
 *
 * Kept apart from [LlmAgentFactory] so the order can be checked without a phone:
 * everything it needs comes in as a value or a lookup.
 */
internal object LadderBuilder {

    /** Saved model plus four alternates: the floor the fallback keeps. */
    const val MAX_TARGETS = 5
    const val MAX_PER_PROVIDER = 4

    fun build(
        home: LlmProvider,
        apiKey: String,
        baseUrl: String,
        model: String,
        forImages: Boolean,
        knownModels: (LlmProvider) -> List<String>,
        apiKeyFor: (LlmProvider) -> String,
        modelFor: (LlmProvider) -> String
    ): List<ModelTarget> {
        val targets = mutableListOf<ModelTarget>()
        fun add(provider: LlmProvider, key: String, baseUrl: String, model: String) {
            if (key.isBlank() || model.isBlank()) return
            if (forImages && !readsImages(provider, model)) return
            if (targets.any { it.provider == provider && it.model == model }) return
            targets += ModelTarget(provider = provider, apiKey = key, baseUrl = baseUrl, model = model)
        }

        if (forImages) {
            home.visionModel?.let { add(home, apiKey, baseUrl, it) }
        } else {
            add(home, apiKey, baseUrl, model)
        }
        // The rest of the selected provider: a retired or overloaded id is exactly
        // what these alternates exist for.
        knownModels(home).take(MAX_PER_PROVIDER).forEach { id ->
            add(home, apiKey, baseUrl, id)
        }

        // A different vendor that already has a key is the strongest fallback: one
        // provider's outage cannot take every model down at once.
        LlmProvider.entries
            .filter { it != home && !it.isCustom }
            .forEach { provider ->
                val key = apiKeyFor(provider)
                if (key.isBlank()) return@forEach
                if (forImages) {
                    provider.visionModel?.let { add(provider, key, provider.baseUrl, it) }
                    return@forEach
                }
                add(provider, key, provider.baseUrl, modelFor(provider))
                knownModels(provider).take(1).forEach { id ->
                    add(provider, key, provider.baseUrl, id)
                }
            }

        return targets.take(MAX_TARGETS)
    }

    private fun readsImages(provider: LlmProvider, model: String): Boolean =
        provider.freeModels.any { it.supportsImages && it.id == model }
}
