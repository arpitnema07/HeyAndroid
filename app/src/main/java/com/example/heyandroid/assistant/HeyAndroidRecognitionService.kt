package com.example.heyandroid.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Recognition service paired with [HeyAndroidVoiceInteractionService].
 * Android's VoiceInteractionServiceInfo strictly requires a valid recognitionService
 * in metadata, otherwise the assistant is rejected by the system.
 */
class HeyAndroidRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Will be wired up in Phase 2
    }

    override fun onCancel(listener: Callback?) {
    }

    override fun onStopListening(listener: Callback?) {
    }
}
