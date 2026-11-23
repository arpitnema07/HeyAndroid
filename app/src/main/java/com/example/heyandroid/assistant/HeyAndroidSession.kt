package com.example.heyandroid.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * Handles a single assistant invocation.
 *
 * The Compose overlay is started as an ordinary activity on purpose.
 *
 * `startVoiceActivity()` would put it inside a *voice task*, and Android then
 * refuses every activity start from that task into another app unless the target
 * declares CATEGORY_VOICE (see ActivityStarter: START_NOT_VOICE_COMPATIBLE). That
 * covers the permission dialog, the photo picker, the document picker and the
 * MediaProjection consent screen, so requesting the microphone permission there
 * crashed the app with:
 *
 *   SecurityException: Starting under voice control not allowed for
 *   Intent { act=android.content.pm.action.REQUEST_PERMISSIONS ... }
 *
 * Starting it normally keeps the voice-session entry point (assist gesture) while
 * giving the window a regular task, where those system screens are allowed. The
 * activity already has its own task affinity, so it stays out of the app's task.
 */
class HeyAndroidSession(private val context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "HeyAndroidSession"
    }

    override fun onCreate() {
        super.onCreate()
        // No session window: AssistantActivity draws the whole UI.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, AssistantActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the assistant overlay", e)
        }
        // Do not finish() here: the session owns the assist gesture lifecycle and
        // is finished from onTaskFinished / by the system.
    }

    override fun onTaskFinished(intent: Intent?, taskId: Int) {
        super.onTaskFinished(intent, taskId)
        finish()
    }
}
