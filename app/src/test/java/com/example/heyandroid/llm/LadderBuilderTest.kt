package com.example.heyandroid.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LadderBuilderTest {

    private fun build(
        home: LlmProvider = LlmProvider.GROQ,
        apiKey: String = "home-key",
        model: String = "saved",
        forImages: Boolean = false,
        knownModels: (LlmProvider) -> List<String> = { emptyList() },
        apiKeyFor: (LlmProvider) -> String = { "" },
        modelFor: (LlmProvider) -> String = { it.defaultModel }
    ): List<ModelTarget> = LadderBuilder.build(
        home = home,
        apiKey = apiKey,
        baseUrl = home.baseUrl,
        model = model,
        forImages = forImages,
        knownModels = knownModels,
        apiKeyFor = apiKeyFor,
        modelFor = modelFor
    )

    private fun models(targets: List<ModelTarget>) = targets.map { it.model }

    @Test
    fun `the saved model comes first, then the rest of its provider`() {
        val ladder = build(
            knownModels = { provider ->
                if (provider == LlmProvider.GROQ) {
                    listOf("saved", "alt-1", "alt-2", "alt-3", "alt-4")
                } else {
                    emptyList()
                }
            }
        )
        // The saved model is not repeated, and one provider contributes at most four.
        assertEquals(listOf("saved", "alt-1", "alt-2", "alt-3"), models(ladder))
    }

    @Test
    fun `other providers with a key follow the first provider`() {
        val ladder = build(
            knownModels = { provider ->
                when (provider) {
                    LlmProvider.GROQ -> listOf("saved")
                    LlmProvider.GEMINI -> listOf("gemini-alt")
                    else -> emptyList()
                }
            },
            apiKeyFor = { provider -> if (provider == LlmProvider.GEMINI) "gemini-key" else "" },
            modelFor = { provider ->
                if (provider == LlmProvider.GEMINI) "gemini-saved" else provider.defaultModel
            }
        )
        assertEquals(listOf("saved", "gemini-saved", "gemini-alt"), models(ladder))
    }

    @Test
    fun `providers without a key are skipped`() {
        val ladder = build(
            knownModels = { provider ->
                if (provider == LlmProvider.GROQ) listOf("saved") else listOf("unused")
            }
        )
        assertEquals(listOf("saved"), models(ladder))
    }

    @Test
    fun `the custom provider is never a fallback`() {
        val ladder = build(
            knownModels = { provider ->
                if (provider == LlmProvider.GROQ) listOf("saved") else listOf("custom-model")
            },
            apiKeyFor = { provider -> if (provider == LlmProvider.CUSTOM) "custom-key" else "" }
        )
        assertEquals(listOf("saved"), models(ladder))
    }

    @Test
    fun `the ladder keeps at most five models`() {
        val ladder = build(
            knownModels = { provider ->
                when (provider) {
                    LlmProvider.GROQ -> listOf("saved", "alt-1", "alt-2", "alt-3", "alt-4")
                    LlmProvider.GEMINI -> listOf("gemini-alt")
                    else -> emptyList()
                }
            },
            apiKeyFor = { provider -> if (provider == LlmProvider.GEMINI) "gemini-key" else "" },
            modelFor = { provider ->
                if (provider == LlmProvider.GEMINI) "gemini-saved" else provider.defaultModel
            }
        )
        assertEquals(LadderBuilder.MAX_TARGETS, ladder.size)
        // The first provider fills its share first; the fifth slot goes to the
        // next provider before anything is dropped.
        assertEquals(listOf("saved", "alt-1", "alt-2", "alt-3", "gemini-saved"), models(ladder))
    }

    @Test
    fun `a blank key never produces a target`() {
        val ladder = build(
            apiKey = "",
            knownModels = { provider -> if (provider == LlmProvider.GROQ) listOf("saved") else emptyList() }
        )
        assertEquals(emptyList<String>(), models(ladder))
    }

    @Test
    fun `an image turn keeps only models that read images`() {
        val ladder = build(
            forImages = true,
            knownModels = { provider ->
                if (provider == LlmProvider.GROQ) {
                    listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b")
                } else {
                    emptyList()
                }
            },
            apiKeyFor = { provider -> if (provider == LlmProvider.GEMINI) "gemini-key" else "" }
        )
        assertEquals(
            listOf("qwen/qwen3.8-27b", "gemini-3.8-flash"),
            models(ladder)
        )
    }
}
