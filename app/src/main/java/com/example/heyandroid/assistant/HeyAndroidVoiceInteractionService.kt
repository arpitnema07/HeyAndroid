package com.example.heyandroid.assistant

import android.service.voice.VoiceInteractionService

/**
 * Top-level service registered as the device's digital assistant.
 * The system keeps this alive; it must stay lightweight.
 * Heavy work happens in [HeyAndroidSession] via the session service.
 */
class HeyAndroidVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Future: initialise hotword detector here if needed.
    }
}
