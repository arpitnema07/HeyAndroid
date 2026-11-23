package com.example.heyandroid.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The widget's refresh button. Not exported, so only this app (and the
 * PendingIntent the widget points at) can spend a model request.
 */
class BriefRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BriefWidgetProvider.ACTION_REFRESH) return

        BriefWidgetProvider.showBuilding(context)
        BriefWorker.enqueue(context)
    }
}
