package com.example.heyandroid.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to any OpenAI-compatible `/chat/completions` endpoint (Groq today).
 * Uses HttpURLConnection so the app needs no HTTP dependency.
 *
 * Requests are serialised and spaced out, and `429`/`5xx` answers are retried
 * with exponential backoff, so a burst of tool steps cannot burn the free tier.
 */
class OpenAiCompatibleClient(
    override val provider: LlmProvider,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) : LlmClient {

    override suspend fun complete(messages: List<LlmMessage>, maxTokens: Int): LlmResult =
        request(messages = messages, tools = null, maxTokens = maxTokens)

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int
    ): LlmResult = request(messages = messages, tools = tools, maxTokens = maxTokens)

    private suspend fun request(
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>?,
        maxTokens: Int
    ): LlmResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext LlmResult.Failure("No API key saved for ${provider.displayName}")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", JSONArray().apply { messages.forEach { put(messageJson(it)) } })
            if (!tools.isNullOrEmpty()) {
                put("tool_choice", "auto")
                put(
                    "tools",
                    JSONArray().apply {
                        tools.forEach { spec ->
                            put(
                                JSONObject().apply {
                                    put("type", "function")
                                    put(
                                        "function",
                                        JSONObject().apply {
                                            put("name", spec.name)
                                            put("description", spec.description)
                                            put("parameters", JSONObject(spec.parametersJson))
                                        }
                                    )
                                }
                            )
                        }
                    }
                )
            }
        }

        var lastFailure: LlmResult.Failure? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) delay(RETRY_BASE_MS * (1L shl (attempt - 1)))
            gate()
            var retryAfterMs: Long? = null

            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection)
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }

                connection.outputStream.use { it.write(payload.toString().toByteArray()) }

                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (code in 200..299) return@withContext parseChoice(body)

                val failure = LlmResult.Failure(errorMessage(code, body))
                lastFailure = failure
                if (code == 429) {
                    retryAfterMs = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1_000L)
                }
                if (code == 429 || code in 500..599) continue

                // A request error (bad key, bad model) will not improve on retry.
                return@withContext failure
            } catch (e: IOException) {
                lastFailure = LlmResult.Failure(e.message ?: "Network error")
            } catch (e: Exception) {
                lastFailure = LlmResult.Failure(e.message ?: "Unexpected error")
            } finally {
                connection?.disconnect()
            }
            retryAfterMs?.let { penalize(it) }
        }

        lastFailure ?: LlmResult.Failure("Request failed")
    }

    /**
     * Paces every request through one queue.
     *
     * All callers (chat turns, the memory agent, the widget brief) share the same
     * mutex, so a turn that runs several tool steps - or two turns in a row - can
     * never fire bursts at the provider. Each slot waits for [MIN_INTERVAL_MS]
     * after the previous one, plus any rate-limit cool-down the provider asked for.
     */
    private suspend fun gate() {
        requestGate.withLock {
            val now = System.currentTimeMillis()
            val earliest = maxOf(lastRequestAt + MIN_INTERVAL_MS, cooldownUntil)
            val wait = earliest - now
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    /** Holds later requests back after a 429, honouring Retry-After when sent. */
    private fun penalize(retryAfterMillis: Long) {
        cooldownUntil = System.currentTimeMillis() + retryAfterMillis.coerceIn(1_000L, 60_000L)
    }

    private fun messageJson(message: LlmMessage): JSONObject = JSONObject().apply {
        put("role", message.role)
        if (message.toolCalls.isNotEmpty()) {
            put("content", JSONObject.NULL)
            put(
                "tool_calls",
                JSONArray().apply {
                    message.toolCalls.forEach { call ->
                        put(
                            JSONObject().apply {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    JSONObject().apply {
                                        put("name", call.name)
                                        put("arguments", call.argumentsJson)
                                    }
                                )
                            }
                        )
                    }
                }
            )
        } else if (message.images.isNotEmpty()) {
            // Vision models take an array of content parts instead of plain text.
            put(
                "content",
                JSONArray().apply {
                    message.content?.takeIf { it.isNotBlank() }?.let { text ->
                        put(JSONObject().apply { put("type", "text"); put("text", text) })
                    }
                    message.images.forEach { dataUrl ->
                        put(
                            JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply { put("url", dataUrl) })
                            }
                        )
                    }
                }
            )
            message.toolCallId?.let { put("tool_call_id", it) }
        } else {
            put("content", message.content.orEmpty())
            message.toolCallId?.let { put("tool_call_id", it) }
        }
    }

    private fun parseChoice(body: String): LlmResult {
        val message = JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        val calls = message.optJSONArray("tool_calls").orEmpty()
        if (calls.length() > 0) {
            val parsed = buildList {
                for (index in 0 until calls.length()) {
                    val call = calls.optJSONObject(index) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name")
                    if (name.isBlank()) continue
                    add(
                        LlmToolCall(
                            id = call.optString("id").ifBlank { "call_$index" },
                            name = name,
                            argumentsJson = function.optString("arguments").ifBlank { "{}" }
                        )
                    )
                }
            }
            if (parsed.isNotEmpty()) return LlmResult.ToolCalls(parsed)
        }

        val text = message.optString("content").trim()
        return if (text.isEmpty()) {
            LlmResult.Failure("${provider.displayName} returned an empty reply")
        } else {
            LlmResult.Success(text)
        }
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private fun errorMessage(code: Int, body: String): String {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()

        return when (code) {
            401, 403 -> "Invalid API key"
            404 -> apiMessage ?: "Model not found: $model"
            429 -> "Rate limited — try again shortly" +
                (apiMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            in 500..599 -> "${provider.displayName} is having trouble (HTTP $code)"
            else -> apiMessage ?: "HTTP $code from ${provider.displayName}"
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_BASE_MS = 2_500L

        /** Gap between any two model calls, across the whole process. */
        const val MIN_INTERVAL_MS = 1_500L

        val requestGate = Mutex()
        @Volatile
        var lastRequestAt = 0L
        @Volatile
        var cooldownUntil = 0L
    }
}

object LlmClientFactory {
    fun create(provider: LlmProvider, apiKey: String, model: String): LlmClient {
        return OpenAiCompatibleClient(
            provider = provider,
            baseUrl = provider.baseUrl,
            apiKey = apiKey,
            model = model
        )
    }
}
