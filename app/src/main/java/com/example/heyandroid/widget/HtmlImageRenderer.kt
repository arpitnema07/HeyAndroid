package com.example.heyandroid.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Renders the AI-authored brief page into a bitmap at the widget's own size.
 *
 * A WebView is measured at exactly [width]x[height], laid out, loaded, drawn once
 * onto a Canvas and destroyed. Scripts, files and network access are off: the
 * page is self-contained and must never make a request.
 *
 * Three traps are handled here:
 *  - An unattached WebView only paints reliably through a software layer.
 *  - `View.postDelayed` queues work until the view is attached, and this view is
 *    never attached, so timers go through the main looper instead.
 *  - A page whose content is taller than the canvas is silently clipped. [measure]
 *    reports the height the page actually wants so the model can be told.
 */
object HtmlImageRenderer {

    private const val TAG = "BriefRender"
    private const val TIMEOUT_MS = 10_000L
    private const val SETTLE_MS = 320L
    private const val FALLBACK_MS = 2_200L
    private const val BACKGROUND = "#0B0B12"

    /** Height the page needs with the canvas constraint lifted, in px. */
    suspend fun measure(context: Context, html: String, width: Int, height: Int): Int? =
        withContext(Dispatchers.Main) {
            val relaxed = html.replace(
                "</body>",
                "<style>html,body{height:auto !important;min-height:0 !important;" +
                    "overflow:visible !important;display:block !important}</style></body>"
            )
            openPage(context.applicationContext, relaxed, width, height) { web, done ->
                val needed = web.contentHeight
                Log.d(TAG, "measured ${needed}px for ${width}x$height")
                done(needed)
            }
        }

    suspend fun render(context: Context, html: String, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.Main) {
            openPage(context.applicationContext, html, width, height) { web, done ->
                val bitmap = runCatching { drawToBitmap(web, width, height) }
                    .onFailure { Log.w(TAG, "draw failed", it) }
                    .getOrNull()
                Log.d(TAG, "captured ${width}x$height (page ${web.contentHeight}px)")
                done(bitmap)
            }
        }

    /**
     * Loads [html] into an off-screen WebView and calls [onReady] on the main
     * thread once it has painted, or after a timeout so the caller never hangs.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun <T> openPage(
        context: Context,
        html: String,
        width: Int,
        height: Int,
        onReady: (WebView, (T?) -> Unit) -> Unit
    ): T? {
        val deferred = CompletableDeferred<T?>()
        val handler = Handler(Looper.getMainLooper())
        var web: WebView? = null

        return try {
            val view = WebView(context)
            web = view
            view.setBackgroundColor(Color.parseColor(BACKGROUND))
            view.settings.apply {
                javaScriptEnabled = false
                loadsImagesAutomatically = false
                blockNetworkImage = true
                blockNetworkLoads = true
                setSupportZoom(false)
                builtInZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
            }
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, width, height)

            fun fire(reason: String) {
                if (deferred.isCompleted) return
                Log.d(TAG, "ready ($reason) progress=${view.progress} height=${view.contentHeight}")
                onReady(view) { value -> deferred.complete(value) }
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(webView: WebView?, url: String?, favicon: Bitmap?) {
                    Log.d(TAG, "page started")
                }

                override fun onPageFinished(webView: WebView?, url: String?) {
                    handler.postDelayed({ fire("finished") }, SETTLE_MS)
                }

                override fun onReceivedError(
                    webView: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.w(TAG, "page error ${error?.errorCode}: ${error?.description}")
                }
            }

            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            handler.postDelayed({ fire("timer") }, FALLBACK_MS)

            withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            Log.w(TAG, "render crashed", t)
            null
        } finally {
            handler.removeCallbacksAndMessages(null)
            web?.let { view ->
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            }
        }
    }

    private fun drawToBitmap(view: View, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor(BACKGROUND))
        view.draw(canvas)
        return bitmap
    }
}
