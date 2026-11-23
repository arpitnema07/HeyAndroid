package com.example.heyandroid.syncpad

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.heyandroid.widget.SyncPadWidgetProvider

/**
 * The one place the SyncPad integration is used from: the screen, the widget
 * worker and the app itself all come through here, so every successful write
 * refreshes the widget and the on-disk cache together.
 */
class SyncPadRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    val settings: SyncPadSettings = SyncPadSettings.getInstance(appContext)
    private val client = SyncPadClient(settings)
    private val cache = SyncPadClipCache.getInstance(appContext)

    val isConfigured: Boolean get() = settings.isConfigured

    fun cached(): List<SyncPadClip> = cache.load()

    suspend fun ping(): Result<String> = client.ping()

    /** Newest page of clips; also what the widget draws. */
    suspend fun refresh(limit: Int = 40): Result<List<SyncPadClip>> {
        val result = client.listClips(limit)
        if (result.isSuccess) {
            cache.save(result.getOrDefault(emptyList()))
            cache.lastSyncAt = System.currentTimeMillis()
            SyncPadWidgetProvider.updateAll(appContext)
        }
        return result
    }

    suspend fun addText(content: String): Result<SyncPadClip> {
        val result = client.addText(content)
        result.getOrNull()?.let { cache.prepend(it) }
        if (result.isSuccess) SyncPadWidgetProvider.updateAll(appContext)
        return result
    }

    suspend fun upload(fileName: String, mimeType: String, bytes: ByteArray): Result<SyncPadClip> {
        val result = client.upload(fileName, mimeType, bytes)
        result.getOrNull()?.let { cache.prepend(it) }
        if (result.isSuccess) SyncPadWidgetProvider.updateAll(appContext)
        return result
    }

    suspend fun delete(id: String): Result<Unit> {
        val result = client.deleteClip(id)
        if (result.isSuccess) {
            cache.remove(id)
            SyncPadWidgetProvider.updateAll(appContext)
        }
        return result
    }

    suspend fun downloadBytes(id: String): Result<ByteArray> = client.downloadBytes(id)

    /**
     * Sends an attachment to the system download manager, which shows progress
     * and lands the file in Downloads without the app holding any permission.
     */
    fun download(context: Context, clip: SyncPadClip) {
        val url = client.downloadUrl(clip.id) ?: return
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(clip.fileName ?: clip.title ?: "SyncPad file")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("Authorization", "Bearer ${client.apiKey()}")
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                clip.fileName ?: "syncpad-${clip.id.take(8)}"
            )
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    companion object {
        @Volatile
        private var INSTANCE: SyncPadRepository? = null

        fun getInstance(context: Context): SyncPadRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncPadRepository(context).also { INSTANCE = it }
            }
    }
}
