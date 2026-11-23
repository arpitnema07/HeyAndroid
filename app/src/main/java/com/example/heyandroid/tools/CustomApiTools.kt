package com.example.heyandroid.tools

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One HTTP tool the user defined: a name the model can ask for, an endpoint, an
 * optional auth header and an optional body template with `{{placeholders}}`.
 */
data class CustomTool(
    val name: String,
    val description: String = "",
    val url: String,
    val method: String = "GET",
    val authHeader: String = "",
    val authValue: String = "",
    val bodyTemplate: String = ""
) {
    val key: String get() = name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
}

/**
 * Stores user-defined API tools on this device. Kept out of the database on
 * purpose: this is configuration, and an auth value must never end up in a
 * memory, a backup of the chat, or the model prompt.
 */
class CustomToolStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("custom_tools", Context.MODE_PRIVATE)

    private val _tools = MutableStateFlow(load())
    val tools: StateFlow<List<CustomTool>> = _tools

    private fun load(): List<CustomTool> {
        val raw = prefs.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (name.isBlank() || url.isBlank()) continue
                    add(
                        CustomTool(
                            name = name,
                            description = item.optString("description").trim(),
                            url = url,
                            method = item.optString("method", "GET").uppercase(),
                            authHeader = item.optString("authHeader").trim(),
                            authValue = item.optString("authValue").trim(),
                            bodyTemplate = item.optString("bodyTemplate")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(tools: List<CustomTool>) {
        val array = JSONArray()
        tools.forEach { tool ->
            array.put(
                JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("url", tool.url)
                    put("method", tool.method)
                    put("authHeader", tool.authHeader)
                    put("authValue", tool.authValue)
                    put("bodyTemplate", tool.bodyTemplate)
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        _tools.value = tools
    }

    fun add(tool: CustomTool) {
        val clean = tool.copy(
            name = tool.name.trim(),
            url = tool.url.trim(),
            method = tool.method.uppercase().takeIf { it == "POST" } ?: "GET"
        )
        if (clean.name.isBlank() || clean.url.isBlank()) return
        persist(_tools.value.filterNot { it.key == clean.key } + clean)
    }

    fun remove(name: String) {
        persist(_tools.value.filterNot { it.key == name.lowercase() })
    }

    fun find(name: String): CustomTool? {
        val key = name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        return _tools.value.firstOrNull { it.key == key || it.name.equals(name, true) }
    }

    companion object {
        private const val KEY = "tools_json"

        @Volatile
        private var INSTANCE: CustomToolStore? = null

        fun getInstance(context: Context): CustomToolStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CustomToolStore(context).also { INSTANCE = it }
            }
    }
}

/**
 * The single tool advertised to the model for everything the user configured.
 * Parameters are passed through as a JSON object; `GET` appends them as a query,
 * `POST` sends them as a JSON body (or renders the user's body template).
 */
@Singleton
class CallCustomApiTool @Inject constructor(
    private val store: CustomToolStore
) : Tool {

    override val name = "call_custom_api"

    override val description: String
        get() {
            val available = store.tools.value
                .joinToString(", ") { "${it.name} (${it.method})" }
                .ifBlank { "none configured yet" }
            return "Call one of the user's own HTTP tools. Available: $available. " +
                "Pass the tool name and an arguments object, e.g. " +
                "{\"tool\":\"weather\",\"arguments\":{\"city\":\"Delhi\"}}."
        }

    override val parametersJson = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "tool",
                    JSONObject().put("type", "string")
                        .put("description", "Name of the user's configured tool")
                )
                .put(
                    "arguments",
                    JSONObject().put("type", "object")
                        .put("description", "Parameters for the endpoint")
                )
        )
        .put("required", JSONArray().put("tool"))
        .put("additionalProperties", false)
        .toString()

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val asked = args.optString("tool").trim()
        if (asked.isBlank()) return "No tool named. Configured: " + configured()

        val tool = store.find(asked)
            ?: return "No custom tool named \"$asked\". Configured: ${configured()}"
        val params = args.optJSONObject("arguments") ?: JSONObject()

        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val built = buildUrl(tool.url, params)
                val conn = (URL(built).openConnection() as HttpURLConnection).apply {
                    requestMethod = tool.method
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    if (tool.authHeader.isNotBlank() && tool.authValue.isNotBlank()) {
                        setRequestProperty(tool.authHeader, tool.authValue)
                    }
                    setRequestProperty("Accept", "application/json")
                }
                connection = conn

                if (tool.method == "POST") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = if (tool.bodyTemplate.isBlank()) {
                        params.toString()
                    } else {
                        renderTemplate(tool.bodyTemplate, params)
                    }
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }

                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (code !in 200..299) {
                    "HTTP $code from ${tool.name}: ${text.take(300).ifBlank { "no response body" }}"
                } else {
                    text.take(3_000).ifBlank { "The endpoint returned an empty response." }
                }
            } catch (e: Exception) {
                "Could not call ${tool.name}: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun configured(): String =
        store.tools.value.joinToString(", ") { it.name }.ifBlank { "none" }

    private fun buildUrl(template: String, params: JSONObject): String {
        var url = template
        val leftover = JSONObject()
        params.keys().forEach { key ->
            val value = params.opt(key)?.toString().orEmpty()
            val token = "{{$key}}"
            if (url.contains(token)) {
                url = url.replace(token, enc(value))
            } else {
                leftover.put(key, value)
            }
        }
        if (leftover.length() == 0) return url
        if (template.contains("{{")) return url
        val separator = if (url.contains("?")) "&" else "?"
        val query = leftover.keys().asSequence().joinToString("&") { key ->
            "${enc(key)}=${enc(leftover.opt(key)?.toString().orEmpty())}"
        }
        return url + separator + query
    }

    private fun renderTemplate(template: String, params: JSONObject): String {
        var body = template
        params.keys().forEach { key ->
            body = body.replace("{{$key}}", enc(params.opt(key)?.toString().orEmpty()))
        }
        return body
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
