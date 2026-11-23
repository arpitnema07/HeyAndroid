package com.example.heyandroid.syncpad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a link clip looks like in the app: the page's own title, its summary and
 * its preview image, read from the page's Open Graph tags.
 *
 * A class rather than a data class because of the bitmap; nothing here is keyed
 * or compared.
 */
class LinkPreview(
    val url: String,
    val host: String,
    val title: String?,
    val description: String?,
    val image: Bitmap?
)

/**
 * Fetches that preview with plain `HttpURLConnection` - the app has no HTML
 * parser and does not need one. Only the first part of a page is read, only
 * `<meta>` tags and `<title>` are looked at, and the result is cached in memory
 * for a short while so scrolling the list does not re-request a page.
 */
object LinkPreviewFetcher {

    private const val MAX_HTML_BYTES = 128 * 1024
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    private const val MAX_IMAGE_EDGE = 1080
    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_CACHED = 20
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) HeyAndroid/1.0"

    private class Entry(val preview: LinkPreview, val at: Long)

    private val cache = LinkedHashMap<String, Entry>()

    suspend fun preview(url: String): Result<LinkPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            synchronized(cache) {
                cache[url]?.takeIf { now - it.at < TTL_MS }?.preview
            }?.let { return@runCatching it }

            val preview = parse(url, fetchText(url))
            synchronized(cache) {
                if (cache.size >= MAX_CACHED) cache.remove(cache.keys.first())
                cache[url] = Entry(preview, now)
            }
            preview
        }
    }

    /** `example.com` - the host without `www.`, for the line under a title. */
    fun hostOf(url: String): String = runCatching {
        URL(url).host.removePrefix("www.")
    }.getOrDefault(url)

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            val text = readCapped(connection.inputStream, MAX_HTML_BYTES)
                .toString(Charsets.UTF_8)
            require(text.isNotBlank()) { "Empty page" }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchImage(url: String): Bitmap? = runCatching {
        val connection = open(url)
        try {
            val bytes = readCapped(connection.inputStream, MAX_IMAGE_BYTES)
            decodeDownsampled(bytes)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun open(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*")
        }
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        return connection
    }

    private fun readCapped(stream: java.io.InputStream?, limit: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        stream.use {
            while (out.size() < limit) {
                val read = it.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, minOf(read, limit - out.size()))
            }
        }
        return out.toByteArray()
    }

    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_EDGE ||
            bounds.outHeight / (sample * 2) >= MAX_IMAGE_EDGE
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun parse(url: String, html: String): LinkPreview {
        val meta = metaTags(html)
        val host = hostOf(url)
        val imageUrl = listOf("og:image", "og:image:url", "twitter:image", "twitter:image:src")
            .firstNotNullOfOrNull { meta[it]?.takeIf(String::isNotBlank) }
            ?.let { resolve(url, it) }

        return LinkPreview(
            url = url,
            host = meta["og:site_name"]?.takeIf { it.isNotBlank() } ?: host,
            title = listOf("og:title", "twitter:title")
                .firstNotNullOfOrNull { meta[it]?.takeIf(String::isNotBlank) }
                ?: titleTag(html),
            description = listOf("og:description", "twitter:description", "description")
                .firstNotNullOfOrNull { meta[it]?.takeIf(String::isNotBlank) },
            image = imageUrl?.let { fetchImage(it) }
        )
    }

    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attribute = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val titleContent = Regex(
        "<title[^>]*>([\\s\\S]*?)</title>",
        RegexOption.IGNORE_CASE
    )

    private fun metaTags(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        metaTag.findAll(html).forEach { tag ->
            val attributes = mutableMapOf<String, String>()
            attribute.findAll(tag.value).forEach { match ->
                val name = match.groupValues[1].lowercase()
                val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
                attributes[name] = value
            }
            val key = (attributes["property"] ?: attributes["name"])?.lowercase() ?: return@forEach
            val content = attributes["content"] ?: return@forEach
            result.putIfAbsent(key, decodeEntities(content.trim()))
        }
        return result
    }

    private fun titleTag(html: String): String? =
        titleContent.find(html)?.groupValues?.get(1)
            ?.let { decodeEntities(it.trim()) }
            ?.takeIf { it.isNotBlank() }

    /** Resolves `/img.png` and `//cdn/img.png` against the page it came from. */
    private fun resolve(base: String, candidate: String): String? =
        runCatching { URL(URL(base), candidate).toString() }.getOrNull()

    private fun decodeEntities(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .trim()
}
