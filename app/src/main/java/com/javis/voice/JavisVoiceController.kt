package com.javis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class JavisVoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

class JavisVoiceController(
    private val context: Context
) {

    private val _state = MutableStateFlow(JavisVoiceState.IDLE)
    val state: StateFlow<JavisVoiceState> = _state

    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start() {
        if (active) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = JavisVoiceState.ERROR
            return
        }

        active = true

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(listener)
        }

        listen()
    }

    fun stop() {
        active = false
        recognizer?.cancel()
        _state.value = JavisVoiceState.IDLE
    }

    fun destroy() {
        active = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = JavisVoiceState.IDLE
    }

    private fun listen() {
        if (!active) return

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
            )
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            )
            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1200L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                900L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                600L
            )
        }

        try {
            _state.value = JavisVoiceState.LISTENING
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            _state.value = JavisVoiceState.ERROR

            if (active) {
                listen()
            }
        }
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (active) {
                _state.value = JavisVoiceState.LISTENING
            }
        }

        override fun onBeginningOfSpeech() {
            if (active) {
                _state.value = JavisVoiceState.LISTENING
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (active) {
                _state.value = JavisVoiceState.PROCESSING
            }
        }

        override fun onError(error: Int) {
            if (!active) return

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    listen()
                }

                else -> {
                    _state.value = JavisVoiceState.ERROR
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!active) return

            val matches = results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

            val text = matches
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (text.isNotBlank()) {
                _state.value = JavisVoiceState.PROCESSING
                onTextRecognized?.invoke(text)
            } else {
                listen()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(
            eventType: Int,
            params: Bundle?
        ) {}
    }

    var onTextRecognized: ((String) -> Unit)? = null
}
