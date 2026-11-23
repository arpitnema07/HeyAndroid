package com.example.heyandroid.llm

/**
 * A model the user can pick. Only models that are usable without paying are listed.
 */
data class LlmModel(
    val id: String,
    val label: String,
    val note: String,
    val supportsImages: Boolean = false
)

/**
 * Providers the app can talk to. All current entries speak the OpenAI-compatible
 * chat-completions protocol, so one client implementation covers them.
 */
enum class LlmProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val freeModels: List<LlmModel>,
    val keyHint: String,
    val keyUrl: String,
    val freeTierNote: String,
    /** True for the entry whose URL, key and model the user types in themselves. */
    val isCustom: Boolean = false
) {
    GROQ(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        freeModels = listOf(
            LlmModel("openai/gpt-oss-120b", "GPT-OSS 120B", "Strongest free model · tool calling"),
            LlmModel("openai/gpt-oss-20b", "GPT-OSS 20B", "Fastest free model · tool calling"),
            LlmModel(
                "qwen/qwen3.8-27b",
                "Qwen 3.8 27B",
                "Preview · can read images",
                supportsImages = true
            )
        ),
        keyHint = "gsk_…",
        keyUrl = "console.groq.com/keys",
        freeTierNote = "Free plan · 30 requests/min · 1,000/day per model"
    ),

    /**
     * Google's OpenAI-compatible endpoint. The free tier is generous but its
     * limits are per project and shown in AI Studio rather than in the docs,
     * and only the Flash line is free - the Pro models have no free quota.
     */
    GEMINI(
        id = "gemini",
        displayName = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        freeModels = listOf(
            LlmModel(
                "gemini-3.8-flash",
                "Gemini 3.8 Flash",
                "Current flagship · tools, 1M context, reads images",
                supportsImages = true
            ),
            LlmModel(
                "gemini-3.5-flash",
                "Gemini 3.5 Flash",
                "Stable · tools and images",
                supportsImages = true
            ),
            LlmModel(
                "gemini-3.5-flash-lite",
                "Gemini 3.5 Flash-Lite",
                "Fastest, highest free quota",
                supportsImages = true
            ),
            LlmModel(
                "gemini-flash-latest",
                "Gemini Flash (latest)",
                "Alias that follows whatever Flash is current",
                supportsImages = true
            )
        ),
        keyHint = "AIza…",
        keyUrl = "aistudio.google.com/apikey",
        freeTierNote = "Free tier · rate limits are per project, shown in Google AI Studio"
    ),

    /**
     * An OpenAI-compatible gateway in front of every major model vendor. The free
     * tier is a standing set of models whose ids end in `:free`; everything else
     * draws on the pay-as-you-go balance, so only the free ids are listed here.
     */
    TOKEN_HARBOR(
        id = "tokenharbor",
        displayName = "Token Harbor",
        baseUrl = "https://tokenharbor.ai/v1",
        freeModels = listOf(
            LlmModel(
                "mimo-v2.6-flash:free",
                "MiMo V2.6 Flash",
                "Free tier · 1M context"
            ),
            LlmModel(
                "deepseek-v4.1-flash:free",
                "DeepSeek V4.1 Flash",
                "Free tier · 1M context"
            )
        ),
        keyHint = "thk_live_…",
        keyUrl = "tokenharbor.ai/dashboard/api-keys",
        freeTierNote = "Free tier · model ids ending in :free never charge the balance"
    ),

    /**
     * NVIDIA's hosted NIM catalog. Keys come from a free NVIDIA Developer Program
     * account and every hosted endpoint is free to prototype with, rate limited per
     * account rather than billed per token.
     */
    NVIDIA(
        id = "nvidia",
        displayName = "NVIDIA",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        // Only ids that /v1/models still returns; NVIDIA retires old ones and
        // answers 410 Gone for them, so the built-in list is a fallback and the
        // settings page can refresh the endpoint's own catalogue.
        freeModels = listOf(
            LlmModel(
                "nvidia/nemotron-3.5-bolt-e4b",
                "Nemotron 3.5 Bolt",
                "Compact · tool calling and agentic chat"
            ),
            LlmModel(
                "nvidia/nemotron-3-ultra-550b-a55b",
                "Nemotron 3 Ultra",
                "Strongest · 1M context, tool calling"
            ),
            LlmModel(
                "nvidia/nemotron-3.5-super-vl-120b-a12b",
                "Nemotron 3.5 Super VL",
                "Reasoning · tools, reads images",
                supportsImages = true
            ),
            LlmModel(
                "z-ai/glm-5.3-flash",
                "GLM 5.3 Flash",
                "Fast · multimodal, tool calling"
            )
        ),
        keyHint = "nvapi-…",
        keyUrl = "build.nvidia.com/settings/api-keys",
        freeTierNote = "Free developer endpoints · about 40 requests/min per account"
    ),

    /**
     * Any other OpenAI-compatible endpoint: the user pastes its base URL, key and a
     * model id. Nothing is validated against a catalog, because the app cannot know
     * what the endpoint serves.
     */
    CUSTOM(
        id = "custom",
        displayName = "Custom",
        baseUrl = "",
        freeModels = emptyList(),
        keyHint = "API key",
        keyUrl = "",
        freeTierNote = "Your own OpenAI-compatible endpoint: base URL, key and model",
        isCustom = true
    );

    val defaultModel: String get() = freeModels.firstOrNull()?.id.orEmpty()

    /** Free model used for photo questions, or null when none of them is multimodal. */
    val visionModel: String? get() = freeModels.firstOrNull { it.supportsImages }?.id

    fun isFreeModel(modelId: String): Boolean = freeModels.any { it.id == modelId }

    fun modelLabel(modelId: String): String =
        freeModels.firstOrNull { it.id == modelId }?.label ?: modelId

    companion object {
        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.id == id } ?: GROQ
    }
}
