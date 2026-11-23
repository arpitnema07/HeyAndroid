package com.example.heyandroid.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the rendered brief PNG to the widget host.
 *
 * Passing a full-width bitmap through RemoteViews means pushing it over a binder
 * transaction, which caps it around 1 MB - so the image had to be downscaled and
 * looked soft. Handing the host a URI instead lets the launcher read the original
 * file at full resolution.
 *
 * The provider only ever exposes `files/brief.png`, is not exported, and the app
 * grants read access to the home app for that one URI.
 */
class BriefImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("no context")
        val file = BriefWidgetProvider.briefFile(context)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only")

    companion object {
        const val AUTHORITY = "com.example.heyandroid.brief"
        private const val PATH = "brief.png"

        /**
         * The URI of the current image.
         *
         * [version] is part of the URI on purpose: a launcher caches widget images
         * by URI, so handing it the same URI again after a redraw kept showing the
         * previous PNG. A new version makes it read the file again.
         */
        fun uri(version: Long = 0L): Uri =
            Uri.parse("content://$AUTHORITY/$PATH").buildUpon()
                .appendQueryParameter("v", version.toString())
                .build()
    }
}
