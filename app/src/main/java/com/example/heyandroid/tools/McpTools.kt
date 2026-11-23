package com.example.heyandroid.tools

import com.example.heyandroid.llm.LlmSettingsStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One tool a connected MCP server offers. */
data class McpToolInfo(val name: String, val description: String)

/**
 * A small Model Context Protocol client over streamable HTTP.
 *
 * The flow follows the spec: `initialize`, the `notifications/initialized` note,
 * then `tools/list` and `tools/call`, carrying the session id the server hands
 * back. Responses may be plain JSON or server-sent events, so both are accepted.
 *
 * This is the connector to a Google MCP server (or any other): the endpoint and
 * its bearer token are the user's, and everything is called straight from the
 * phone.
 */
@Singleton
class McpClient @Inject constructor() {

    /** Lists the tools a server offers. */
    suspend fun listTools(url: String, token: String): Result<List<McpToolInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = initialize(url, token)
                val reply = post(
                    url = url,
                    token = token,
                    sessionId = session,
                    payload = rpc(id = 2, method = "tools/list", params = JSONObject())
                )
                reply.error?.let { throw IOException(it) }
                val result = reply.json?.optJSONObject("result")
                    ?: throw IOException("The server did not answer tools/list.")
                val tools = result.optJSONArray("tools") ?: throw IOException("No tools listed.")
                buildList {
                    for (index in 0 until tools.length()) {
                        val item = tools.optJSONObject(index) ?: continue
                        val name = item.optString("name").trim()
                        if (name.isBlank()) continue
                        add(McpToolInfo(name, item.optString("description").trim()))
                    }
                }
            }
        }

    /** Calls one tool on the server and returns its text result. */
    suspend fun callTool(
        url: String,
        token: String,
        tool: String,
        arguments: JSONObject
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val session = initialize(url, token)
            val reply = post(
                url = url,
                token = token,
                sessionId = session,
                payload = rpc(
                    id = 2,
                    method = "tools/call",
                    params = JSONObject().put("name", tool).put("arguments", arguments)
                )
            )
            reply.error?.let { throw IOException(it) }
            val root = reply.json ?: throw IOException("The server sent no answer.")
            root.optJSONObject("error")?.let { error ->
                throw IOException(error.optString("message").ifBlank { "the call failed" })
            }
            val result = root.optJSONObject("result")
                ?: throw IOException("The server sent no result.")
            formatResult(result)
        }
    }

    private fun formatResult(result: JSONObject): String {
        val content = result.optJSONArray("content")
        if (content != null) {
            val text = buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    if (item.optString("type") == "text") {
                        if (isNotEmpty()) append("\n\n")
                        append(item.optString("text").trim())
                    }
                }
            }
            if (text.isNotBlank()) return text.take(4_000)
        }
        return result.toString().take(4_000)
    }

    /** Runs the MCP handshake and returns the session id, if the server issues one. */
    private fun initialize(url: String, token: String): String? {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "HeyAndroid").put("version", "1.0"))
        val reply = post(url, token, null, rpc(id = 1, method = "initialize", params = params))
        reply.error?.let { throw IOException(it) }
        // A notification; servers answer 202 with an empty body and it is fine to ignore.
        post(
            url = url,
            token = token,
            sessionId = reply.sessionId,
            payload = JSONObject().put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
        )
        return reply.sessionId
    }

    private fun rpc(id: Int, method: String, params: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    private data class Reply(
        val json: JSONObject?,
        val sessionId: String?,
        val error: String? = null
    )

    private fun post(
        url: String,
        token: String,
        sessionId: String?,
        payload: JSONObject
    ): Reply {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (!sessionId.isNullOrBlank()) setRequestProperty("Mcp-Session-Id", sessionId)
            }
            connection = conn
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            val newSession = conn.getHeaderField("Mcp-Session-Id") ?: sessionId
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            when {
                code == 401 || code == 403 ->
                    Reply(null, newSession, "The MCP server rejected the saved token.")

                code !in 200..299 ->
                    Reply(
                        null,
                        newSession,
                        "The MCP server answered HTTP $code${if (body.isBlank()) "" else ": " + body.take(200)}"
                    )

                else -> Reply(parse(body), newSession, null)
            }
        } catch (e: Exception) {
            Reply(null, sessionId, "Could not reach the MCP server: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    /** A plain JSON body, or the `data:` lines of a server-sent event stream. */
    private fun parse(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) return runCatching { JSONObject(trimmed) }.getOrNull()
        var found: JSONObject? = null
        trimmed.lineSequence().forEach { line ->
            val clean = line.trim()
            val data = when {
                clean.startsWith("data:") -> clean.removePrefix("data:").trim()
                else -> return@forEach
            }
            runCatching { JSONObject(data) }.getOrNull()?.let { found = it }
        }
        return found
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}

/**
 * The model-facing half of the connector: one tool that can list what the server
 * offers and call any of it. Without a configured URL it explains where to set one
 * up instead of failing.
 */
@Singleton
class McpTool @Inject constructor(
    private val settings: LlmSettingsStore,
    private val client: McpClient
) : Tool {

    override val name = "mcp_call"

    override val description =
        "Call a tool on the user's connected MCP server (for example a Google " +
            "Workspace server for Gmail, Calendar, Drive or Docs). Call it with no " +
            "tool name to see what the server offers, then call that tool by name."

    override val parametersJson = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "tool",
                    JSONObject().put("type", "string")
                        .put("description", "Tool name from the server, omit to list them")
                )
                .put(
                    "arguments",
                    JSONObject().put("type", "object")
                        .put("description", "Arguments for that tool, as the listing describes")
                )
        )
        .put("additionalProperties", false)
        .toString()

    override suspend fun run(argumentsJson: String): String {
        val url = settings.mcpUrl.value.trim()
        if (url.isBlank()) {
            return "No MCP server is connected. Add one in Settings → MCP connector."
        }
        val token = settings.mcpToken.value.trim()
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val tool = args.optString("tool").trim()

        val tools = client.listTools(url, token).getOrElse { error ->
            return error.message ?: "The MCP server could not be reached."
        }
        if (tool.isBlank()) {
            if (tools.isEmpty()) return "The MCP server is connected but offers no tools."
            return "MCP tools available:\n" + tools.joinToString("\n") { info ->
                "· ${info.name}" + info.description.takeIf { it.isNotBlank() }
                    ?.let { " — " + it.replace('\n', ' ').take(140) }.orEmpty()
            }
        }
        if (tools.none { it.name == tool }) {
            return "No MCP tool named \"$tool\". Available: " +
                tools.joinToString(", ") { it.name }
        }

        val arguments = args.optJSONObject("arguments") ?: JSONObject()
        return client.callTool(url, token, tool, arguments).getOrElse { error ->
            "MCP call failed: ${error.message ?: "unknown error"}"
        }
    }
}
