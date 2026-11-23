package com.example.heyandroid.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates a new [HeyAndroidSession] each time the assistant is invoked.
 */
class HeyAndroidSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return HeyAndroidSession(this)
    }
}
