package com.example.heyandroid.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a picked photo into a `data:` URL the model can read. Downscaled first so
 * the request stays small: a phone photo is many megabytes, a 1024px JPEG is not.
 */
internal object ImageAttachment {

    private const val MAX_EDGE = 1024
    private const val QUALITY = 80

    suspend fun toDataUrl(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        // Application context: the overlay can be dismissed while the decode runs.
        val appContext = context.applicationContext
        runCatching {
            val resolver = appContext.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val decoded = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null

            try {
                val scaled = scaleDown(decoded)
                val bytes = ByteArrayOutputStream()
                    .also { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                    .toByteArray()
                if (scaled !== decoded) scaled.recycle()
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
        }.getOrNull()
    }

    /** Reads a text document (txt, md, csv, json…) for the conversation. */
    suspend fun readText(context: Context, uri: Uri, maxChars: Int = 8000): String? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText().take(maxChars).ifBlank { null }
                }
            }.getOrNull()
        }

    /** Largest power-of-two downsample that keeps the long edge above [MAX_EDGE]. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
