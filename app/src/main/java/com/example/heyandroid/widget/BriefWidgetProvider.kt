package com.example.heyandroid.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.RemoteViews
import com.example.heyandroid.R
import com.example.heyandroid.brief.BriefActivity
import java.io.File

/**
 * Home-screen widget showing the AI-written brief.
 *
 * The widget is a fixed square that spans the launcher width, so the page is
 * designed and rendered at the phone's portrait width and served to the launcher
 * as a full-resolution PNG over a content URI (a bitmap over RemoteViews would be
 * capped at the binder limit and come out soft).
 *
 * The image is cached on disk, so the widget is free to redraw after a reboot or
 * a launcher restart without spending a model request.
 */
class BriefWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    /**
     * The brief canvas.
     *
     * [widthDp]/[heightDp] are the CSS pixels the page is authored in - WebView
     * lays out CSS pixels at display density, so the model is told the canvas in
     * dp while the bitmap is rendered at the phone's real pixel width.
     */
    data class Size(
        val widthDp: Int,
        val heightDp: Int,
        val widthPx: Int,
        val heightPx: Int,
        val deviceWidthDp: Int,
        val deviceHeightDp: Int
    )

    companion object {
        const val ACTION_REFRESH = "com.example.heyandroid.action.BRIEF_REFRESH"
        private const val FILE_NAME = "brief.png"
        private const val PREFS = "brief_widget"
        private const val KEY_REVISION = "revision"

        fun briefFile(context: Context): File = File(context.filesDir, FILE_NAME)

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, BriefWidgetProvider::class.java))

        /** Redraws every placed widget from the cached image. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            ids(context).forEach { id -> render(context, manager, id) }
        }

        /**
         * Placeholder shown while a rebuild runs. It goes through the same builder
         * as the real render, so the refresh and open taps stay wired - the old
         * hand-built placeholder dropped its click intents and left the widget dead.
         */
        fun showBuilding(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            ids(context).forEach { id ->
                val views = base(context)
                views.setViewVisibility(R.id.widget_image, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.setTextViewText(R.id.widget_empty, "Building your brief…")
                manager.updateAppWidget(id, views)
            }
        }

        /**
         * Bumped after every render and baked into the image URI. Launchers cache a
         * widget image by URI, so reusing one URI kept the old PNG on screen after
         * a refresh.
         */
        private fun revision(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_REVISION, 0L)

        fun nextRevision(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = prefs.getLong(KEY_REVISION, 0L) + 1
            prefs.edit().putLong(KEY_REVISION, next).apply()
            return next
        }

        /**
         * Full portrait width, on both axes: the widget is a square spanning the
         * launcher width, and the page is rendered at the phone's own pixel width
         * so nothing is scaled on the home screen.
         */
        fun size(context: Context): Size {
            val metrics = context.resources.displayMetrics
            val portraitPx = minOf(metrics.widthPixels, metrics.heightPixels)
            val tallPx = maxOf(metrics.widthPixels, metrics.heightPixels)
            val density = metrics.density

            val widthDp = (portraitPx / density).toInt().coerceIn(240, 640)
            return Size(
                widthDp = widthDp,
                heightDp = widthDp,
                widthPx = portraitPx,
                heightPx = portraitPx,
                deviceWidthDp = (portraitPx / density).toInt(),
                deviceHeightDp = (tallPx / density).toInt()
            )
        }

        /** A widget view with its tap targets attached, ready to be filled in. */
        private fun base(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_brief)

            val refresh = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, BriefRefreshReceiver::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh)

            // Tapping the brief opens the in-app page that renders the same HTML.
            val open = PendingIntent.getActivity(
                context,
                2,
                Intent(context, BriefActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_image, open)
            views.setOnClickPendingIntent(R.id.widget_empty, open)
            return views
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = base(context)
            val file = briefFile(context)

            if (file.exists()) {
                val uri = BriefImageProvider.uri(revision(context))
                grantReadToHome(context, uri)
                views.setImageViewUri(R.id.widget_image, uri)
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
                views.setViewVisibility(R.id.widget_empty, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_image, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_empty,
                    "Your brief is empty.\nTap refresh to build it."
                )
            }

            manager.updateAppWidget(id, views)
        }

        /** The provider is not exported; the launcher gets this one URI only. */
        private fun grantReadToHome(context: Context, uri: android.net.Uri) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val packageName = context.packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?: return
            runCatching {
                context.grantUriPermission(
                    packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }
}
