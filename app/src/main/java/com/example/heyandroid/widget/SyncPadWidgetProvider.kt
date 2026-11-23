package com.example.heyandroid.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.heyandroid.MainActivity
import com.example.heyandroid.R
import com.example.heyandroid.syncpad.SyncPadClip
import com.example.heyandroid.syncpad.SyncPadClipCache

/**
 * Home-screen SyncPad, written in Compose for widgets (Glance).
 *
 * The whole list scrolls, so the widget shows as many clips as its height allows
 * instead of stopping at five. It draws from the on-disk cache, so it has content
 * the moment it is placed and after a reboot, before any request finishes.
 *
 * Tapping a text row copies that clip to the system clipboard, which needs no
 * screen (Android only restricts *reading* the clipboard to the focused app). The
 * paste button goes the other way: it opens the SyncPad composer with whatever is
 * on the clipboard, because that read does need the app in front.
 */
class SyncPadGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val clips = SyncPadClipCache.getInstance(context).load()
        provideContent { WidgetContent(clips) }
    }
}

/** The widget's broadcast receiver; Glance re-renders through it. */
class SyncPadWidgetProvider : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = SyncPadGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SyncPadSyncWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        SyncPadSyncWorker.cancel(context)
    }

    companion object {
        /** Redraws every placed widget from the cache. */
        suspend fun updateAll(context: Context) {
            SyncPadGlanceWidget().updateAll(context)
        }
    }
}

private val TextPrimary = ColorProvider(Color(0xFFEDF0F6), Color(0xFFEDF0F6))
private val TextSecondary = ColorProvider(Color(0xFFB0AEBD), Color(0xFFB0AEBD))
private val TextTertiary = ColorProvider(Color(0xFF8B94A3), Color(0xFF8B94A3))
private val Accent = ColorProvider(Color(0xFF8FA8FF), Color(0xFF8FA8FF))
private val RowSurface = ColorProvider(Color(0x1AFFFFFF), Color(0x1AFFFFFF))
private val Surface = ColorProvider(Color(0xF20B0B12), Color(0xF20B0B12))

private val CLIP_ID = ActionParameters.Key<String>("clip_id")

@Composable
private fun WidgetContent(all: List<SyncPadClip>) {
    val context = LocalContext.current
    val clips = all.take(MAX_CLIPS)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_brief_bg)))
    {
        Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
            Header()

            if (clips.isEmpty()) {
                Text(
                    text = context.getString(R.string.widget_syncpad_empty),
                    style = TextStyle(color = TextSecondary, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(top = 12.dp)
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    text = context.getString(R.string.widget_syncpad_hint),
                    style = TextStyle(color = TextTertiary, fontSize = 12.sp)
                )
            } else {
                Spacer(modifier = GlanceModifier.height(8.dp))
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(clips, itemId = { it.id.hashCode().toLong() }) { clip ->
                        ClipRow(clip)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    val context = LocalContext.current
    // Widgets get no swipe gestures, so the header itself is the pull-to-refresh
    // target: the largest area that is not already a clip row.
    val refresh = actionRunCallback<RefreshClipsAction>()

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight().clickable(refresh),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = context.getString(R.string.widget_syncpad_title),
                style = TextStyle(
                    color = Accent,
                    // Sized like body text on purpose: Glance scales sp with the
                    // user's system font size, so the widget follows that setting
                    // instead of carrying its own tiny scale.
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = context.getString(R.string.widget_syncpad_subtitle),
                style = TextStyle(color = TextTertiary, fontSize = 11.sp),
                modifier = GlanceModifier.padding(top = 2.dp)
            )
        }

        IconButton(
            icon = R.drawable.ic_paste,
            description = context.getString(R.string.widget_syncpad_paste),
            onClick = actionStartActivity(
                Intent(context, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCPAD)
                    putExtra(MainActivity.EXTRA_PASTE_CLIPBOARD, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        )
        Spacer(modifier = GlanceModifier.size(6.dp))
        IconButton(
            icon = R.drawable.ic_refresh,
            description = context.getString(R.string.widget_syncpad_refresh),
            onClick = refresh
        )
    }
}

@Composable
private fun IconButton(
    icon: Int,
    description: String,
    onClick: androidx.glance.action.Action
) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        modifier = GlanceModifier
            .size(30.dp)
            .background(ImageProvider(R.drawable.widget_refresh_bg))
            .padding(7.dp)
            .clickable(onClick)
    )
}

@Composable
private fun ClipRow(clip: SyncPadClip) {
    val context = LocalContext.current
    val openApp = actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCPAD)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    )
    // A text clip can be copied where it stands; an image or a file has nothing
    // to put on the clipboard, so it opens the clip in the app instead.
    val action = if (clip.isText) {
        actionRunCallback<CopyClipAction>(actionParametersOf(CLIP_ID to clip.id))
    } else {
        openApp
    }

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(RowSurface)
            .clickable(action)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = typeLabel(clip.type),
                style = TextStyle(color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            )
            if (clip.isText) {
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = context.getString(R.string.widget_syncpad_copy),
                    style = TextStyle(color = Accent, fontSize = 10.sp)
                )
            }
        }
        Text(
            text = clip.preview.ifBlank { clip.headline },
            style = TextStyle(color = TextPrimary, fontSize = 14.sp),
            maxLines = 2,
            modifier = GlanceModifier.padding(top = 2.dp)
        )
    }
}

private const val MAX_CLIPS = 60

private fun typeLabel(type: String): String = when (type) {
    "url" -> "LINK"
    "image" -> "IMAGE"
    "file" -> "FILE"
    "code" -> "CODE"
    else -> "TEXT"
}

/** Puts one clip on the system clipboard, from the home screen. */
class CopyClipAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val id = parameters[CLIP_ID] ?: return
        val clip = SyncPadClipCache.getInstance(context).load().firstOrNull { it.id == id } ?: return
        val text = clip.content
        if (text.isNullOrBlank()) {
            toast(context, R.string.widget_syncpad_nothing_to_copy)
            return
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SyncPad clip", text))
        toast(context, R.string.widget_syncpad_copied)
    }
}

/**
 * A widget action runs on a background coroutine, and Toast needs a Looper: calling
 * Toast.makeText there threw "Can't toast on a thread that has not called
 * Looper.prepare()", which killed the whole action callback. Always post to main.
 */
private fun toast(context: Context, message: Int) {
    Handler(Looper.getMainLooper()).post {
        runCatching {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** The refresh button: pull a fresh page and redraw. */
class RefreshClipsAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        SyncPadSyncWorker.enqueue(context)
    }
}
