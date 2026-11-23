package com.example.heyandroid.tools

import android.content.Context
import android.net.Uri
import com.example.heyandroid.assistant.ScreenshotSource
import com.example.heyandroid.syncpad.SyncPadClip
import com.example.heyandroid.syncpad.SyncPadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The assistant's side of SyncPad: put a snippet, the newest screenshot or an
 * image onto the user's clipboard, so it is waiting on the web app and the
 * home-screen widget.
 *
 * Writes go through [SyncPadRepository], the same path the screen uses, so a
 * tool-created clip refreshes the widget exactly like a typed one.
 */

/** Clip text ceiling on the server; checked here so the model gets a clear answer. */
private const val MAX_CLIP_CHARS = 20_000
/** The SyncPad upload limit. */
private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

private const val NOT_CONNECTED =
    "SyncPad is not connected on this phone. The user can add the domain and a " +
        "device key in Settings → SyncPad."

/** Reads the optional `tags` argument: a list or a comma-separated string. */
private fun tagsFrom(args: JSONObject): List<String> {
    val raw = args.opt("tags") ?: return emptyList()
    val values = when (raw) {
        is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
        else -> raw.toString().split(',')
    }
    return values
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotBlank() }
        .distinct()
        .take(5)
}

private fun clipSummary(clip: SyncPadClip): String {
    val label = clip.headline.ifBlank { clip.id }
    return "Saved to SyncPad: $label"
}

/**
 * Saves a text snippet. The server detects whether it is plain text, code or a
 * link, so a URL lands as a link card instead of a paragraph.
 */
@Singleton
class SyncPadTextTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "syncpad_add_text"

    override val description =
        "Save a text snippet to the user's SyncPad clipboard. Use it whenever the " +
            "user asks to clip, copy or send something to SyncPad, so it is waiting on " +
            "their other devices and the home-screen widget."

    override val parametersJson = stringParameters(
        required = mapOf("content" to "The text to save, exactly as it should read"),
        optional = mapOf("tags" to "Up to five tags, comma separated")
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val content = args.optString("content").trim()
        if (content.isEmpty()) return "Nothing was saved: the content was empty."
        if (content.length > MAX_CLIP_CHARS) {
            return "Nothing was saved: SyncPad clips are limited to " +
                "$MAX_CLIP_CHARS characters and this one is ${content.length}."
        }

        val repository = SyncPadRepository.getInstance(context)
        if (!repository.isConfigured) return NOT_CONNECTED

        return repository.addText(content, tagsFrom(args)).fold(
            onSuccess = { clipSummary(it) },
            onFailure = { "SyncPad refused the clip: ${it.message ?: "unknown error"}" }
        )
    }
}

/**
 * Adds the newest screenshot on the phone. It reads the gallery's screenshot
 * file rather than capturing the screen, so no screen-capture consent is needed
 * - the user takes the screenshot first, then asks.
 */
@Singleton
class SyncPadScreenshotTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "syncpad_add_screenshot"

    override val description =
        "Add the newest screenshot on the phone to the user's SyncPad clipboard. Use " +
            "it when the user says something like \"send this screenshot to SyncPad\". " +
            "The screenshot must already exist on the device."

    override val parametersJson = stringParameters(
        required = emptyMap(),
        optional = mapOf(
            "max_age_minutes" to "How old the screenshot may be, in minutes (default 60)",
            "tags" to "Up to five tags, comma separated"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"

        val repository = SyncPadRepository.getInstance(context)
        if (!repository.isConfigured) return NOT_CONNECTED

        if (!ScreenshotSource.hasPermission(context)) {
            return "HeyAndroid cannot read the gallery, so it cannot see any screenshot. " +
                "Open the assistant once and allow photo access, then try again."
        }

        val minutes = args.optString("max_age_minutes").trim().toIntOrNull()
            ?.coerceIn(1, 24 * 60) ?: 60
        val shot = ScreenshotSource.latest(
            context = context,
            maxAgeMillis = TimeUnit.MINUTES.toMillis(minutes.toLong())
        ) ?: return "No screenshot from the last $minutes minutes was found on this phone."

        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(shot.uri)?.use { it.readBytes() }
            }.getOrNull()
        } ?: return "Could not read that screenshot."

        if (bytes.size > MAX_UPLOAD_BYTES) {
            return "That screenshot is too large for SyncPad (limit 10 MB)."
        }

        val mimeType = context.contentResolver.getType(shot.uri) ?: "image/png"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(shot.takenAt))
        return repository.upload(
            fileName = "screenshot-$stamp.png",
            mimeType = mimeType,
            bytes = bytes,
            tags = tagsFrom(args)
        ).fold(
            onSuccess = { clipSummary(it) },
            onFailure = { "SyncPad refused the screenshot: ${it.message ?: "unknown error"}" }
        )
    }
}

/**
 * Fetches an image from a public URL and stores it in SyncPad. The model can hand
 * over a link it found (a chart, a product shot), and the phone does the download.
 */
@Singleton
class SyncPadImageTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "syncpad_add_image"

    override val description =
        "Add an image to the user's SyncPad clipboard from a public http(s) URL. " +
            "Use it when the user wants a picture sent to SyncPad and it is reachable " +
            "on the web; for something already on the phone, use syncpad_add_screenshot."

    override val parametersJson = stringParameters(
        required = mapOf("url" to "Public http(s) URL of the image"),
        optional = mapOf(
            "file_name" to "Name to store the image under",
            "tags" to "Up to five tags, comma separated"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val url = args.optString("url").trim()
        if (url.isEmpty()) return "No image was added: the url was empty."
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Only http(s) image URLs can be fetched."
        }

        val repository = SyncPadRepository.getInstance(context)
        if (!repository.isConfigured) return NOT_CONNECTED

        val downloaded = runCatching {
            withContext(Dispatchers.IO) { download(url) }
        }.getOrElse { return "Could not download that image: ${it.message ?: "network error"}" }

        val extension = when (downloaded.mimeType.substringAfter('/', "")) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            else -> "img"
        }
        val name = args.optString("file_name").trim()
            .ifBlank { "image-${Uri.parse(url).lastPathSegment?.take(40) ?: "syncpad"}.$extension" }

        return repository.upload(
            fileName = name,
            mimeType = downloaded.mimeType,
            bytes = downloaded.bytes,
            tags = tagsFrom(args)
        ).fold(
            onSuccess = { clipSummary(it) },
            onFailure = { "SyncPad refused the image: ${it.message ?: "unknown error"}" }
        )
    }

    private class Downloaded(val bytes: ByteArray, val mimeType: String)

    private fun download(url: String): Downloaded {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HeyAndroid/1.0")
        }
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val stream = connection.inputStream ?: throw IllegalStateException("No response body")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            stream.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_UPLOAD_BYTES) {
                        throw IllegalStateException("image is larger than 10 MB")
                    }
                }
            }
            val mimeType = connection.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.startsWith("image/") }
                ?: "image/jpeg"
            return Downloaded(out.toByteArray(), mimeType)
        } finally {
            connection.disconnect()
        }
    }
}
