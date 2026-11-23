package com.example.heyandroid.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.heyandroid.R
import com.example.heyandroid.syncpad.SyncPadClipCache

/**
 * The widget's buttons. Not exported, so only this app (through the widget's
 * PendingIntents) can trigger a SyncPad request or a clipboard write.
 *
 * Copying happens here rather than in the app because it needs no screen:
 * Android only restricts *reading* the clipboard to the focused app, so a row
 * can fill it from the home screen. Putting a clip on the clipboard is how a
 * SyncPad clip gets pasted into any other app.
 */
class SyncPadWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SyncPadWidgetProvider.ACTION_REFRESH -> SyncPadSyncWorker.enqueue(context)
            SyncPadWidgetProvider.ACTION_COPY -> copy(
                context,
                intent.getStringExtra(SyncPadWidgetProvider.EXTRA_CLIP_ID)
            )
        }
    }

    private fun copy(context: Context, clipId: String?) {
        if (clipId == null) return
        val clip = SyncPadClipCache.getInstance(context)
            .load()
            .firstOrNull { it.id == clipId }
            ?: return

        val text = clip.content
        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.widget_syncpad_nothing_to_copy, Toast.LENGTH_SHORT)
                .show()
            return
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SyncPad clip", text))
        Toast.makeText(context, R.string.widget_syncpad_copied, Toast.LENGTH_SHORT).show()
    }
}
