package com.example.heyandroid.assistant

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds the newest screenshot on the device.
 *
 * The user's workflow is: see something, take a screenshot with the power
 * button, then long-press to summon the assistant. So the screenshot is the
 * default attachment, and it needs no screen-capture consent: the image is
 * already a file in the gallery, read through MediaStore.
 */
object ScreenshotSource {

    data class Shot(val uri: android.net.Uri, val takenAt: Long) {
        val age: Long get() = System.currentTimeMillis() - takenAt
    }

    /** Permission the gallery read needs on this Android version. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Newest screenshot within [maxAgeMillis], or null. Scans the most recent
     * images and keeps the ones whose path or name says "screenshot", because
     * folders differ per manufacturer (Pictures/Screenshots, DCIM/Screenshots…).
     */
    suspend fun latest(
        context: Context,
        maxAgeMillis: Long = TimeUnit.DAYS.toMillis(30)
    ): Shot? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        val cutoffSeconds = (System.currentTimeMillis() - maxAgeMillis) / 1000
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(cutoffSeconds.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

                var scanned = 0
                while (cursor.moveToNext() && scanned < 120) {
                    scanned++
                    val name = cursor.getString(nameIndex).orEmpty().lowercase()
                    val path = cursor.getString(pathIndex).orEmpty().lowercase()
                    if (!name.contains("screenshot") && !path.contains("screenshot")) continue

                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex)
                    )
                    val takenAt = cursor.getLong(dateIndex) * 1000
                    return@use Shot(uri, takenAt)
                }
                null
            }
        }.getOrNull()
    }
}
