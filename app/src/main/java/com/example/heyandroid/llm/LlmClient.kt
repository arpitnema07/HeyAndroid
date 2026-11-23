package com.example.heyandroid.llm

/**
 * One message in a chat. Assistant messages that ask for tools carry [toolCalls]
 * and no content; tool results carry the [toolCallId] they answer.
 */
data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    /** Data URLs (`data:image/jpeg;base64,…`) sent as image content parts. */
    val images: List<String> = emptyList()
) {
    companion object {
        fun system(content: String) = LlmMessage("system", content)
        fun user(content: String, images: List<String> = emptyList()) =
            LlmMessage(role = "user", content = content, images = images)
        fun toolResult(callId: String, content: String) =
            LlmMessage(role = "tool", content = content, toolCallId = callId)

        fun assistantToolCalls(calls: List<LlmToolCall>) =
            LlmMessage(role = "assistant", content = null, toolCalls = calls)
    }
}

/**
 * A tool the model asked to run. [argumentsJson] is the raw JSON object the
 * model produced, validated later by the tool that owns the schema.
 */
data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * A tool advertised to the model as a JSON-schema function.
 */
data class LlmToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

sealed interface LlmResult {
    data class Success(val text: String) : LlmResult
    data class ToolCalls(val calls: List<LlmToolCall>) : LlmResult
    data class Failure(val message: String) : LlmResult
}

/**
 * One thing the app needs from a model: send messages, get text or tool calls back.
 */
interface LlmClient {
    val provider: LlmProvider

    suspend fun complete(
        messages: List<LlmMessage>,
        maxTokens: Int = 512
    ): LlmResult

    suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int = 512
    ): LlmResult
}
