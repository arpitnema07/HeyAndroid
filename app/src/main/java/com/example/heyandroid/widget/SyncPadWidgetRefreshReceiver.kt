package com.example.heyandroid.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The widget's refresh button. Not exported, so only this app (through the
 * widget's PendingIntent) can trigger a SyncPad request.
 */
class SyncPadWidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncPadWidgetProvider.ACTION_REFRESH) return
        SyncPadSyncWorker.enqueue(context)
    }
}
