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
    val freeTierNote: String
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
    );

    val defaultModel: String get() = freeModels.first().id

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
