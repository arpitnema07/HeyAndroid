package com.example.heyandroid.syncpad

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * One clip from the user's SyncPad clipboard, in the same shape the web app
 * shows it: text, a code snippet, a link, an image or a file.
 */
data class SyncPadClip(
    val id: String,
    val type: String,
    val title: String?,
    val content: String?,
    val language: String?,
    val fileName: String?,
    val fileSize: Long?,
    val mimeType: String?,
    val hasFile: Boolean,
    val isPinned: Boolean,
    val createdAt: Long
) {
    val isText: Boolean get() = type == "text" || type == "code" || type == "url"

    /** What a widget row shows on its second line. */
    val preview: String
        get() = when (type) {
            "url" -> content.orEmpty()
            "image" -> fileName ?: "Image"
            "file" -> buildString {
                append(fileName ?: "File")
                fileSize?.let { append(" · ").append(formatSize(it)) }
            }

            else -> content.orEmpty().replace('\n', ' ').trim()
        }

    val headline: String
        get() = when (type) {
            "url" -> title ?: content.orEmpty()
            "image" -> fileName ?: "Image"
            "file" -> fileName ?: "File"
            "code" -> title ?: content.orEmpty().lineSequence().firstOrNull().orEmpty()
            else -> content.orEmpty().lineSequence().firstOrNull().orEmpty()
        }.take(120)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("title", title)
        .put("content", content)
        .put("language", language)
        .put("file_name", fileName)
        .put("file_size", fileSize ?: JSONObject.NULL)
        .put("mime_type", mimeType)
        .put("has_file", hasFile)
        .put("is_pinned", isPinned)
        .put("created_at", createdAt)

    companion object {
        fun fromJson(json: JSONObject): SyncPadClip = SyncPadClip(
            id = json.optString("id"),
            type = json.optString("type", "text"),
            title = json.stringOrNull("title"),
            content = json.stringOrNull("content"),
            language = json.stringOrNull("language"),
            fileName = json.stringOrNull("file_name"),
            fileSize = if (json.isNull("file_size")) null else json.optLong("file_size"),
            mimeType = json.stringOrNull("mime_type"),
            hasFile = json.optBoolean("has_file", false),
            isPinned = json.optBoolean("is_pinned", false),
            createdAt = parseTime(json.optString("created_at"))
        )

        fun encode(clips: List<SyncPadClip>): String {
            val array = JSONArray()
            clips.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun decode(raw: String?): List<SyncPadClip> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { fromJson(it) }
                }
            }.getOrDefault(emptyList())
        }

        private fun JSONObject.stringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

        /** Supabase sends `2026-11-23T12:34:56.789+00:00`. */
        private fun parseTime(value: String): Long {
            if (value.isBlank()) return 0L
            return runCatching { Instant.parse(value).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
