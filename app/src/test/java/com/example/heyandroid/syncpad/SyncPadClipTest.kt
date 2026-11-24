package com.example.heyandroid.syncpad

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric only for `org.json`, which is a stub in plain JVM unit tests; the
 * parsing itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
class SyncPadClipTest {

    private fun json(type: String, extra: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject()
            .put("id", "clip-1")
            .put("type", type)
            .put("created_at", "2026-11-23T12:34:56.789+00:00")
            .apply(extra)

    @Test
    fun `a url clip parses its timestamp and preview`() {
        val clip = SyncPadClip.fromJson(
            json("url") {
                put("title", "Android docs")
                put("content", "https://developer.android.com")
                put("is_pinned", true)
                put("tags", org.json.JSONArray(listOf("dev", "read")))
            }
        )

        assertEquals("clip-1", clip.id)
        assertTrue(clip.isText)
        assertTrue(clip.isPinned)
        assertEquals(listOf("dev", "read"), clip.tags)
        assertEquals("https://developer.android.com", clip.preview)
        assertEquals("Android docs", clip.headline)
        assertTrue(clip.createdAt > 0)
    }

    @Test
    fun `a file clip shows its name and size`() {
        val clip = SyncPadClip.fromJson(
            json("file") {
                put("file_name", "report.pdf")
                put("file_size", 2_048L)
                put("has_file", true)
            }
        )

        assertEquals("report.pdf · 2 KB", clip.preview)
        assertEquals("report.pdf", clip.headline)
        assertNull(clip.content)
    }

    @Test
    fun `a code clip takes its first line as the headline`() {
        val clip = SyncPadClip.fromJson(
            json("code") {
                put("content", "fun main() {\n    println(\"hi\")\n}")
                put("language", "kotlin")
            }
        )

        assertEquals("fun main() {", clip.headline)
        assertEquals("kotlin", clip.language)
    }

    @Test
    fun `encoding and decoding round trips`() {
        val clips = listOf(
            SyncPadClip.fromJson(json("text") { put("content", "milk") }),
            SyncPadClip.fromJson(json("url") { put("content", "https://example.com") })
        )

        val decoded = SyncPadClip.decode(SyncPadClip.encode(clips))

        assertEquals(clips, decoded)
    }

    @Test
    fun `garbage decodes to an empty list instead of crashing`() {
        assertEquals(emptyList<SyncPadClip>(), SyncPadClip.decode("not json"))
        assertEquals(emptyList<SyncPadClip>(), SyncPadClip.decode(null))
    }

    @Test
    fun `sizes are human readable`() {
        assertEquals("512 B", SyncPadClip.formatSize(512))
        assertEquals("2 KB", SyncPadClip.formatSize(2_048))
        assertEquals("1.5 MB", SyncPadClip.formatSize((1.5 * 1024 * 1024).toLong()))
    }
}
