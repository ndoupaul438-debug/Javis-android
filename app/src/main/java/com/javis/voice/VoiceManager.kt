package com.javis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

sealed class VoiceEvent {
    data class RecognizedText(val text: String) : VoiceEvent()
    object ListeningStarted : VoiceEvent()
    object ListeningEnded : VoiceEvent()
    data class Error(val message: String) : VoiceEvent()
    object WakeWordDetected : VoiceEvent()
}

class VoiceManager(
    private val context: Context,
    private val onEvent: (VoiceEvent) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeWordActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun isWakeWordActive(): Boolean = wakeWordActive

    fun startListening() {
        if (!isRecognitionAvailable()) {
            onEvent(VoiceEvent.Error("Speech recognition isn't available on this device."))
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onEvent(VoiceEvent.ListeningStarted)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (text.isNullOrBlank()) {
                        onEvent(VoiceEvent.Error("I didn't hear anything."))
                    } else {
                        onEvent(VoiceEvent.RecognizedText(text))
                    }
                    onEvent(VoiceEvent.ListeningEnded)
                }

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that — could you repeat it?"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech recognition needs a network connection right now."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is required for voice input."
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone couldn't be accessed."
                        else -> "Voice recognition failed. Try again or type instead."
                    }
                    onEvent(VoiceEvent.Error(message))
                    onEvent(VoiceEvent.ListeningEnded)
                }

                override fun onEndOfSpeech() { onEvent(VoiceEvent.ListeningEnded) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            onEvent(VoiceEvent.Error("Couldn't start listening: ${e.message}"))
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "javis_utterance")
    }

    fun startWakeWordListening() {
        if (!isRecognitionAvailable()) {
            onEvent(VoiceEvent.Error("Speech recognition isn't available on this device."))
            return
        }
        wakeWordActive = true
        runWakeRecognizer()
    }

    fun stopWakeWordListening(keepFlag: Boolean = false) {
        if (!keepFlag) wakeWordActive = false
        wakeRecognizer?.setRecognitionListener(null)
        wakeRecognizer?.destroy()
        wakeRecognizer = null
    }

    private fun runWakeRecognizer() {
        if (!wakeWordActive) return

        wakeRecognizer?.destroy()
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    checkForWakeWord(partialResults, restartOnMiss = false)
                }

                override fun onResults(results: Bundle?) {
                    checkForWakeWord(results, restartOnMiss = true)
                }

                override fun onError(error: Int) {
                    if (wakeWordActive) {
                        mainHandler.postDelayed({ runWakeRecognizer() }, 300)
                    }
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            wakeRecognizer?.startListening(intent)
        } catch (e: Exception) {
            if (wakeWordActive) {
                mainHandler.postDelayed({ runWakeRecognizer() }, 500)
            }
        }
    }

    private fun checkForWakeWord(bundle: Bundle?, restartOnMiss: Boolean) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
        if (text.contains("javis") || text.contains("jarvis")) {
            stopWakeWordListening(keepFlag = true)
            onEvent(VoiceEvent.WakeWordDetected)
        } else if (restartOnMiss && wakeWordActive) {
            runWakeRecognizer()
        }
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopWakeWordListening(keepFlag = false)
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
