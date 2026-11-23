package com.example.heyandroid.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android platform [SpeechRecognizer] implementation of [SpeechToText].
 * Configured with EXTRA_PREFER_OFFLINE = true so dictation prioritizes
 * on-device capture without failing when offline.
 */
class AndroidSpeechToText(private val context: Context) : SpeechToText {

    companion object {
        private const val TAG = "AndroidSpeechToText"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SpeechState.Ready
        }

        override fun onBeginningOfSpeech() {
            _state.value = SpeechState.Listening(0f)
        }

        override fun onRmsChanged(rmsdB: Float) {
            val current = _state.value
            if (current is SpeechState.Listening || current is SpeechState.PartialResult) {
                _state.value = SpeechState.Listening(rmsdB)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Waiting for final results from recognition service
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                else -> "Recognition error ($error)"
            }
            Log.w(TAG, "SpeechRecognizer error: $message (code $error)")
            _state.value = SpeechState.Error(message, error)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull()?.trim().orEmpty()
            if (recognizedText.isNotBlank()) {
                _state.value = SpeechState.FinalResult(recognizedText)
            } else {
                _state.value = SpeechState.Idle
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialText = matches?.firstOrNull()?.trim().orEmpty()
            if (partialText.isNotBlank()) {
                _state.value = SpeechState.PartialResult(partialText)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun startListening() {
        runOnMainThread {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _state.value = SpeechState.Error("Speech recognition not available on device", -1)
                return@runOnMainThread
            }

            ensureRecognizer()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Prefer on-device / offline recognition for reliability
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            try {
                recognizer?.startListening(intent)
                _state.value = SpeechState.Ready
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                _state.value = SpeechState.Error(e.localizedMessage ?: "Failed to start listening", -1)
            }
        }
    }

    override fun stopListening() {
        runOnMainThread {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping recognizer", e)
            }
        }
    }

    override fun cancel() {
        runOnMainThread {
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling recognizer", e)
            }
            _state.value = SpeechState.Idle
        }
    }

    override fun destroy() {
        runOnMainThread {
            try {
                recognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying recognizer", e)
            }
            recognizer = null
            _state.value = SpeechState.Idle
        }
    }

    private fun ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
