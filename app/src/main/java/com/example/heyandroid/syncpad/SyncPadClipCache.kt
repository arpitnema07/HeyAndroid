package com.example.heyandroid.syncpad

import android.content.Context
import android.content.SharedPreferences

/**
 * The last synced page of clips, kept on disk so the widget has something to
 * draw the moment it is placed and after a reboot - before any request finishes.
 */
class SyncPadClipCache private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("syncpad_cache", Context.MODE_PRIVATE)

    fun load(): List<SyncPadClip> = SyncPadClip.decode(prefs.getString(KEY_CLIPS, null))

    fun save(clips: List<SyncPadClip>) {
        prefs.edit().putString(KEY_CLIPS, SyncPadClip.encode(clips)).apply()
    }

    fun prepend(clip: SyncPadClip) {
        val next = listOf(clip) + load().filterNot { it.id == clip.id }
        save(next)
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_SYNCED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SYNCED_AT, value).apply()

    companion object {
        private const val KEY_CLIPS = "clips"
        private const val KEY_SYNCED_AT = "synced_at"

        @Volatile
        private var INSTANCE: SyncPadClipCache? = null

        fun getInstance(context: Context): SyncPadClipCache =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncPadClipCache(context).also { INSTANCE = it }
            }
    }
}
