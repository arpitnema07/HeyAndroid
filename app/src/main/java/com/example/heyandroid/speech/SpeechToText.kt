package com.example.heyandroid.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * State representing the active speech recognition session.
 */
sealed interface SpeechState {
    data object Idle : SpeechState
    data object Ready : SpeechState
    data class Listening(val rmsDb: Float = 0f) : SpeechState
    data class PartialResult(val text: String) : SpeechState
    data class FinalResult(val text: String) : SpeechState
    data class Error(val message: String, val errorCode: Int) : SpeechState
}

/**
 * Pluggable abstraction for speech-to-text.
 * Implementations can be Android's SpeechRecognizer, whisper.cpp on-device,
 * or an external cloud API.
 */
interface SpeechToText {
    val state: StateFlow<SpeechState>

    /**
     * Starts listening for voice input.
     */
    fun startListening()

    /**
     * Stops listening and waits for final recognition results.
     */
    fun stopListening()

    /**
     * Cancels the active speech recognition session without emitting results.
     */
    fun cancel()

    /**
     * Releases all resources associated with the recognizer.
     */
    fun destroy()
}
