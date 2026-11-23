package com.example.heyandroid.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the brief for the widget's real canvas:
 *
 *  1. read the placed widget's size in pixels,
 *  2. ask the model for a page made for exactly that size,
 *  3. measure the drawn page and, if it overflows, tell the model and try again,
 *  4. cache the PNG and push it to the widget.
 *
 * A failed render keeps the previous image rather than blanking the widget.
 */
@HiltWorker
class BriefWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val generator: BriefGenerator
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val size = BriefWidgetProvider.size(applicationContext)
        Log.d(
            TAG,
            "widget canvas ${size.widthDp}x${size.heightDp} css " +
                "(${size.widthPx}x${size.heightPx}px), phone ${size.deviceWidthDp}x" +
                "${size.deviceHeightDp} css"
        )

        var feedback: String? = null
        var best: Bitmap? = null
        var bestDelta = Int.MAX_VALUE

        for (attempt in 1..MAX_ATTEMPTS) {
            val html = runCatching {
                generator.html(
                    widthCss = size.widthDp,
                    heightCss = size.heightDp,
                    deviceWidthCss = size.deviceWidthDp,
                    deviceHeightCss = size.deviceHeightDp,
                    feedback = feedback
                )
            }
                .onFailure { Log.w(TAG, "generation failed", it) }
                .getOrNull()
            if (html.isNullOrBlank()) {
                Log.w(TAG, "no page to render")
                break
            }
            Log.d(TAG, "attempt $attempt: page ${html.length} chars")
            saveHtml(html)

            val needed = runCatching {
                HtmlImageRenderer.measure(applicationContext, html, size.widthPx, size.heightPx)
            }.getOrNull()
            // contentHeight is already reported in CSS pixels, which is what the
            // page was authored against.
            val neededCss = needed

            val bitmap = HtmlImageRenderer.render(
                context = applicationContext,
                html = html,
                width = size.widthPx,
                height = size.heightPx
            )
            if (bitmap == null) {
                Log.w(TAG, "render returned nothing")
                break
            }

            val delta = neededCss?.let { abs(it - size.heightDp) } ?: 0
            if (delta < bestDelta) {
                best?.recycle()
                best = bitmap
                bestDelta = delta
            } else {
                bitmap.recycle()
            }

            val fits = neededCss == null || neededCss <= size.heightDp + 4
            Log.d(
                TAG,
                "attempt $attempt: needs ${neededCss}css of ${size.heightDp}css, fits=$fits"
            )
            if (fits) break

            feedback = "The previous page needed ${neededCss}px but the canvas is only " +
                "${size.heightDp}px tall, so its bottom was clipped. Redesign for " +
                "${size.widthDp}x${size.heightDp} CSS pixels: smaller headline, shorter " +
                "item details, tighter gaps, no fixed-height clipping containers."
        }

        best?.let { write(it) }
        BriefWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }

    /** The exact page that produced the widget, kept for inspection. */
    private suspend fun saveHtml(html: String) = withContext(Dispatchers.IO) {
        runCatching { File(applicationContext.filesDir, "brief.html").writeText(html) }
    }

    /** Written to a temp file first, so a half-written PNG can never be shown. */
    private suspend fun write(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        runCatching {
            // Stored at full render size: the widget reads it through a content
            // URI, so the binder's bitmap limit does not apply and nothing is
            // downscaled on the way to the home screen.
            val target = BriefWidgetProvider.briefFile(applicationContext)
            val temp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) Log.w(TAG, "could not replace ${target.name}")
            }
            // A new revision means a new content URI, which is what makes the
            // launcher read the fresh PNG instead of its cached copy.
            BriefWidgetProvider.nextRevision(applicationContext)
            Log.d(TAG, "brief cached ${bitmap.width}x${bitmap.height} to ${target.name}")
        }.onFailure { Log.w(TAG, "cache write failed", it) }

        if (!bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        private const val TAG = "BriefWorker"
        private const val MAX_ATTEMPTS = 3

        private const val UNIQUE = "brief-refresh"

        /** A brief is rebuilt on app launch if it is older than this. */
        const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BriefWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Builds the brief when the widget has none, or the cached one is old. */
        fun refreshIfStale(context: Context, force: Boolean = false) {
            if (BriefWidgetProvider.ids(context).isEmpty()) return
            val file = BriefWidgetProvider.briefFile(context)
            val fresh = file.exists() &&
                System.currentTimeMillis() - file.lastModified() < STALE_AFTER_MS
            if (!force && fresh) return
            Log.d(TAG, if (force) "forced build" else "brief missing or stale; building")
            enqueue(context)
        }
    }
}
