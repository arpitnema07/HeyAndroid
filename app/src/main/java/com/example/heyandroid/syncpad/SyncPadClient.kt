package com.example.heyandroid.syncpad

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A readable failure from SyncPad, e.g. "That code doesn't match a room". */
class SyncPadException(message: String) : IOException(message)

/**
 * Minimal HTTP client for SyncPad's `/api/integrations/…` endpoints.
 *
 * Plain `HttpURLConnection` on purpose: the app already has no HTTP stack, and
 * the integration needs four requests, not a library. Every call is authenticated
 * with the saved API key and fails with the server's own message.
 */
class SyncPadClient(private val settings: SyncPadSettings) {

    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = request("GET", "/api/integrations/ping").body
            JSONObject(body).optString("account", "SyncPad")
        }
    }

    suspend fun listClips(limit: Int = 40): Result<List<SyncPadClip>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = request("GET", "/api/integrations/clips?limit=$limit").body
            val items: JSONArray = JSONObject(body).optJSONArray("clips") ?: JSONArray()
            (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.let { SyncPadClip.fromJson(it) }
            }
        }
    }

    suspend fun addText(content: String): Result<SyncPadClip> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().put("content", content).toString().toByteArray()
            val body = request("POST", "/api/integrations/clips", payload, "application/json").body
            SyncPadClip.fromJson(JSONObject(body).getJSONObject("clip"))
        }
    }

    suspend fun upload(fileName: String, mimeType: String, bytes: ByteArray): Result<SyncPadClip> =
        withContext(Dispatchers.IO) {
            runCatching {
                val boundary = "----heyandroid${UUID.randomUUID()}"
                val safeName = fileName.ifBlank { "upload.bin" }.replace("\"", "")
                val type = mimeType.ifBlank { "application/octet-stream" }
                val head = "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                    "Content-Type: $type\r\n\r\n"
                val tail = "\r\n--$boundary--\r\n"
                val body = head.toByteArray() + bytes + tail.toByteArray()
                val response = request(
                    method = "POST",
                    path = "/api/integrations/clips/upload",
                    body = body,
                    contentType = "multipart/form-data; boundary=$boundary"
                ).body
                SyncPadClip.fromJson(JSONObject(response).getJSONObject("clip"))
            }
        }

    suspend fun deleteClip(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            request("DELETE", "/api/integrations/clips/$id")
            Unit
        }
    }

    /** Raw bytes of an attachment (used for in-app image previews). */
    suspend fun downloadBytes(id: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = endpoint("/api/integrations/clips/$id/file")
                ?: throw SyncPadException("Add your SyncPad domain in Settings.")
            val key = settings.apiKey.value
            if (key.isBlank()) throw SyncPadException("Add a SyncPad API key in Settings.")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw SyncPadException("Could not load that attachment.")
                }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Signed download URL for DownloadManager, or null when unconfigured. */
    fun downloadUrl(id: String): String? =
        endpoint("/api/integrations/clips/$id/file?download=1")?.toString()

    fun apiKey(): String = settings.apiKey.value

    private data class Payload(val status: Int, val body: String)

    private fun endpoint(path: String): URL? {
        val base = settings.baseUrl ?: return null
        return runCatching { URL(base + path) }.getOrNull()
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null
    ): Payload {
        val url = endpoint(path) ?: throw SyncPadException("Add your SyncPad domain in Settings.")
        val key = settings.apiKey.value
        if (key.isBlank()) throw SyncPadException("Add a SyncPad API key in Settings.")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
            connection.setFixedLengthStreamingMode(body.size)
            BufferedOutputStream(connection.outputStream).use { it.write(body) }
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        connection.disconnect()

        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "SyncPad returned HTTP $status."
            throw SyncPadException(message)
        }

        return Payload(status, text)
    }
}
