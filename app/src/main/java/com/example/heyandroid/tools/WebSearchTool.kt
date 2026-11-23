package com.example.heyandroid.tools

import com.example.heyandroid.llm.LlmSettingsStore
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Online search through Tavily.
 *
 * The device stores the user's own Tavily key; without one the tool answers with a
 * plain explanation instead of failing, so the assistant can still say what is
 * missing. Results are trimmed to a few lines each: the model only needs titles,
 * links and the relevant snippet to answer, not whole pages.
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val settings: LlmSettingsStore
) : Tool {

    override val name = "web_search"

    override val description =
        "Search the public web for current information: news, prices, opening " +
            "hours, sports results, releases, or anything you are unsure about or " +
            "that changed after your training. Use it before answering questions " +
            "about the outside world, and prefer memory tools for the user's own data."

    override val parametersJson = stringParameters(
        required = mapOf("query" to "What to search the web for, in a few clear words"),
        optional = mapOf(
            "depth" to "\"basic\" (fast, default) or \"advanced\" (more thorough)",
            "answers" to "\"yes\" to also get a short written answer (default yes)"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val key = settings.tavilyKey.value.trim()
        if (key.isBlank()) {
            return "Web search is off: no Tavily API key is saved. " +
                "Add one in Settings → Web search to turn it on."
        }

        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val query = args.optString("query").trim()
        if (query.isBlank()) return "Search not run: query is required"

        val depth = args.optString("depth").trim().lowercase()
            .let { if (it == "advanced") "advanced" else "basic" }
        val wantAnswer = !args.optString("answers").equals("no", true)

        val body = JSONObject()
            .put("query", query)
            .put("search_depth", depth)
            .put("max_results", 5)
            .put("include_answer", wantAnswer)
            .put("include_raw_content", false)
            .toString()

        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }
                connection = conn
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (code == 401 || code == 403) {
                    "Tavily rejected the saved API key. Check it in Settings → Web search."
                } else if (code == 432 || code == 433) {
                    "The Tavily plan limit was reached for this key. Try again later " +
                        "or check the plan in the Tavily dashboard."
                } else if (code !in 200..299) {
                    "Web search failed (HTTP $code): ${text.take(200).ifBlank { "no body" }}"
                } else {
                    format(text, query)
                }
            } catch (e: Exception) {
                "Could not reach the web: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                connection?.disconnect()
            }
        }
    }

    /** One compact block: the answer first, then the sources it came from. */
    private fun format(raw: String, query: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return raw.take(2_000)
        val results = json.optJSONArray("results")
        val answer = json.optString("answer").trim()

        val lines = buildString {
            append("Web results for \"").append(query).append("\"")
            if (answer.isNotBlank()) append("\nAnswer: ").append(answer.take(700))
            if (results != null && results.length() > 0) {
                for (index in 0 until minOf(results.length(), 5)) {
                    val item = results.optJSONObject(index) ?: continue
                    append("\n\n").append(index + 1).append(". ")
                    append(item.optString("title").trim().ifBlank { "Untitled" })
                    val url = item.optString("url").trim()
                    if (url.isNotBlank()) append("\n   ").append(url)
                    val snippet = item.optString("content").trim().replace('\n', ' ')
                    if (snippet.isNotBlank()) append("\n   ").append(snippet.take(400))
                }
            }
        }
        return if (results == null || results.length() == 0) {
            "No web results for \"$query\"."
        } else {
            lines
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
    }
}
