package com.example.heyandroid.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the model catalogue of an OpenAI-compatible endpoint.
 *
 * The built-in lists in [LlmProvider] go stale: providers retire ids (NVIDIA
 * answers 410 Gone for a retired model) and add new ones. `GET /v1/models` is
 * the endpoint's own answer to "what may I call?", so the picker uses it when
 * the user has a key.
 */
object ModelCatalog {

    /** Ids the endpoint accepts for this key; throws with a readable message. */
    suspend fun fetch(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val root = baseUrl.trim().trimEnd('/').ifBlank {
                throw IOException("No base URL saved")
            }
            if (apiKey.isBlank()) throw IOException("No API key saved")

            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$root/models").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "application/json")
                    // A few providers sit behind a CDN that rejects unknown
                    // clients outright (Groq answers 403 to a blank agent).
                    setRequestProperty("User-Agent", "HeyAndroid/1.0")
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val body = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    val detail = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    throw IOException(
                        when (code) {
                            401 -> "Invalid API key"
                            403 -> "Access denied (HTTP 403)"
                            404 -> "This endpoint has no /models route"
                            else -> "HTTP $code" + (detail?.take(120)?.let { ": $it" } ?: "")
                        }
                    )
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { index ->
                    data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
                }
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * The ids that can answer a chat: embeddings, rerankers, image and audio
     * generators, OCR/ASR and safety detectors sit in the same catalogue but
     * cannot hold a conversation. Gemini also prefixes its ids with `models/`,
     * which the chat call does not want.
     */
    fun chatModels(ids: List<String>): List<String> =
        ids.map { it.trim().removePrefix("models/") }
            .filter { it.isNotBlank() }
            .filterNot { id ->
                val lower = id.lowercase()
                NON_CHAT.any { lower.contains(it) }
            }
            .distinct()
            .sorted()

    private val NON_CHAT = listOf(
        "embed", "rerank", "clip", "gliner", "detector", "content-safety",
        "topic-control", "safety-guard", "arctic-embed", "ocr", "-asr", "tts",
        "riva-translate", "starcoder", "lyria", "whisper", "orpheus", "imagen",
        "veo", "nano-banana", "-image", "transcribe", "realtime", "guardian",
        "moderation", "safeguard", "voice", "reranker", "parse"
    )
}
