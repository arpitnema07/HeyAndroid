package com.example.heyandroid.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.example.heyandroid.MainActivity
import com.example.heyandroid.R
import com.example.heyandroid.syncpad.SyncPadClip
import com.example.heyandroid.syncpad.SyncPadClipCache

/**
 * Home-screen widget with the newest clips from the user's SyncPad clipboard.
 *
 * It draws from the on-disk cache immediately, so it never sits empty while a
 * request is in flight. Tapping the refresh button asks the sync worker for a
 * fresh page; the in-app SyncPad screen refreshes it as it polls, and the worker
 * also runs on a periodic schedule. A row opens the SyncPad screen.
 */
class SyncPadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    override fun onEnabled(context: Context) {
        SyncPadSyncWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        SyncPadSyncWorker.cancel(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.example.heyandroid.action.SYNCPAD_REFRESH"
        private const val MAX_ROWS = 5

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, SyncPadWidgetProvider::class.java))

        /** Redraws every placed widget from the cache. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val clips = SyncPadClipCache.getInstance(context).load().take(MAX_ROWS)
            ids(context).forEach { id -> render(context, manager, id, clips) }
        }

        private fun typeLabel(type: String): String = when (type) {
            "url" -> "LINK"
            "image" -> "IMAGE"
            "file" -> "FILE"
            "code" -> "CODE"
            else -> "TEXT"
        }

        private fun openIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                10,
                Intent(context, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCPAD)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** One row's tap target, unique per clip so the row opens the right screen. */
        private fun rowIntent(context: Context, clipId: String, index: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                100 + index,
                Intent(context, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCPAD)
                    data = Uri.parse("heyandroid://syncpad/$clipId")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            clips: List<SyncPadClip> = SyncPadClipCache.getInstance(context).load().take(MAX_ROWS)
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_syncpad)

            views.setOnClickPendingIntent(R.id.widget_syncpad_refresh, refreshIntent(context))
            views.setOnClickPendingIntent(R.id.widget_syncpad_title, openIntent(context))
            views.setOnClickPendingIntent(R.id.widget_syncpad_empty, openIntent(context))
            views.removeAllViews(R.id.widget_syncpad_clips)

            if (clips.isEmpty()) {
                views.setViewVisibility(R.id.widget_syncpad_empty, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_syncpad_empty, View.GONE)
                clips.forEachIndexed { index, clip ->
                    val row = RemoteViews(context.packageName, R.layout.widget_syncpad_row)
                    row.setTextViewText(R.id.syncpad_row_type, typeLabel(clip.type))
                    row.setTextViewText(
                        R.id.syncpad_row_text,
                        clip.preview.ifBlank { clip.headline }
                    )
                    row.setOnClickPendingIntent(
                        R.id.syncpad_row,
                        rowIntent(context, clip.id, index)
                    )
                    views.addView(R.id.widget_syncpad_clips, row)
                }
            }

            manager.updateAppWidget(id, views)
        }

        private fun refreshIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                11,
                Intent(context, SyncPadWidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
