package com.javis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.javis.BuildConfig
import com.javis.MainActivity
import com.javis.R
import com.javis.ai.AIBackend
import com.javis.ai.AnthropicBackend
import com.javis.ai.GeminiBackend
import com.javis.ai.GroqBackend
import com.javis.ai.LLMPIBackend
import com.javis.ai.MockBackend
import com.javis.assistant.JavisAssistantEngine
import com.javis.assistant.AssistantOutcome
import com.javis.data.ApiKeyStore
import java.util.Locale

class WakeWordService : Service() {

    private var wakeRecognizer: SpeechRecognizer? = null
    private var commandRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    // After the wake word, Javis stays in a natural conversation
    // until the user stops responding.
    private var conversationMode = false
    private var processingCommand = false

    private lateinit var engine: JavisAssistantEngine

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
        engine = JavisAssistantEngine(applicationContext, pickBackend())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            WakeWordServiceState.setRunning(true)
            startForeground(NOTIFICATION_ID, buildNotification("Listening for \"Hey Javis\"..."))
            runWakeRecognizer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        WakeWordServiceState.setRunning(false)
        wakeRecognizer?.destroy()
        commandRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun pickBackend(): AIBackend {
        val store = ApiKeyStore(applicationContext)
        val groqKey = store.getGroqKey()
        if (!groqKey.isNullOrBlank()) return GroqBackend(groqKey)
        val geminiKey = store.getGeminiKey()
        if (!geminiKey.isNullOrBlank()) return GeminiBackend(geminiKey)
        val anthropicKey = store.getApiKey()
        if (!anthropicKey.isNullOrBlank()) return AnthropicBackend(anthropicKey)
        val hasRealEndpoint = BuildConfig.LLMPI_BASE_URL.isNotBlank() &&
            !BuildConfig.LLMPI_BASE_URL.contains("example.invalid")
        return if (hasRealEndpoint) {
            LLMPIBackend(BuildConfig.LLMPI_BASE_URL, BuildConfig.LLMPI_API_KEY)
        } else {
            MockBackend()
        }
    }

    private fun runWakeRecognizer() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        WakeWordServiceState.setStatus(ListeningStatus.LISTENING_FOR_WAKE)

        wakeRecognizer?.destroy()
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
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
                    if (running) mainHandler.postDelayed({ runWakeRecognizer() }, 300)
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
            if (running) mainHandler.postDelayed({ runWakeRecognizer() }, 500)
        }
    }

    private fun checkForWakeWord(bundle: Bundle?, restartOnMiss: Boolean) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
        val heard = text.contains("hey javis") || text.contains("hey jarvis") ||
            text.contains("javis") || text.contains("jarvis")
        if (heard) {
            wakeRecognizer?.destroy()
            wakeRecognizer = null

            conversationMode = true
            processingCommand = false

            WakeWordServiceState.setStatus(ListeningStatus.SPEAKING)
            updateNotification("JAVIS is listening...")
            speakAndContinue("Yes?")
        } else if (restartOnMiss && running) {
            runWakeRecognizer()
        }
    }

    private fun captureCommand() {
        if (!running || !conversationMode || processingCommand) return

        commandRecognizer?.destroy()
        commandRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    WakeWordServiceState.setStatus(
                        ListeningStatus.LISTENING_FOR_COMMAND
                    )
                    updateNotification("JAVIS is listening...")
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {}

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {}

                override fun onResults(results: Bundle?) {
                    if (!running || !conversationMode) return

                    val matches =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    val text = matches?.firstOrNull()?.trim().orEmpty()

                    if (text.isBlank()) {
                        endConversation()
                    } else {
                        handleCommand(text)
                    }
                }

                override fun onError(error: Int) {
                    if (!running) return

                    // Normal silence means the user has finished
                    // the conversation. Other recognition errors
                    // get a short retry.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        endConversation()
                    } else {
                        mainHandler.postDelayed({
                            if (running && conversationMode &&
                                !processingCommand
                            ) {
                                captureCommand()
                            }
                        }, 350)
                    }
                }
            })
        }

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
                900L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                700L
            )
        }

        try {
            commandRecognizer?.startListening(intent)
        } catch (_: Exception) {
            if (running && conversationMode) {
                mainHandler.postDelayed({
                    captureCommand()
                }, 500)
            }
        }
    }

    private fun endConversation() {
        if (!running) return

        conversationMode = false
        processingCommand = false

        commandRecognizer?.cancel()
        commandRecognizer?.destroy()
        commandRecognizer = null

        WakeWordServiceState.setStatus(
            ListeningStatus.LISTENING_FOR_WAKE
        )
        updateNotification("Listening for \"Hey Javis\"...")
        mainHandler.postDelayed({
            if (running && !conversationMode) {
                runWakeRecognizer()
            }
        }, 250)
    }

    private fun speakAndContinue(text: String) {
        if (!running) return

        WakeWordServiceState.setStatus(ListeningStatus.SPEAKING)

        if (!ttsReady || text.isBlank()) {
            mainHandler.postDelayed({
                if (running && conversationMode && !processingCommand) {
                    captureCommand()
                }
            }, 150)
            return
        }

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "javis_conversation"
        )

        mainHandler.postDelayed({
            if (running && conversationMode && !processingCommand) {
                captureCommand()
            }
        }, 900)
    }

    private fun handleCommand(text: String) {
        if (!running || processingCommand) return

        processingCommand = true

        WakeWordServiceState.setStatus(ListeningStatus.THINKING)
        updateNotification("JAVIS is thinking...")

        Thread {
            val outcome = try {
                kotlinx.coroutines.runBlocking {
                    engine.handleUserInput(text)
                }
            } catch (e: Exception) {
                AssistantOutcome(
                    displayText = "I'm sorry, something went wrong while processing that.",
                    spoken = true
                )
            }

            mainHandler.post {
                processingCommand = false

                if (!running) return@post

                if (outcome.pendingConfirmation != null) {
                    speakThenResumeWake(
                        "Please confirm that action in Javis."
                    )
                } else if (outcome.spoken && outcome.displayText.isNotBlank()) {
                    speakThenResumeWake(outcome.displayText)
                } else {
                    // Some actions deliberately have no spoken response.
                    // Continue the conversation without saying anything.
                    if (conversationMode) {
                        captureCommand()
                    } else {
                        runWakeRecognizer()
                    }
                }
            }
        }.start()
    }

    private fun speakThenResumeWake(text: String) {
        if (!running) return

        WakeWordServiceState.setStatus(ListeningStatus.SPEAKING)
        updateNotification("JAVIS is speaking...")

        if (!ttsReady || text.isBlank()) {
            if (conversationMode) {
                mainHandler.postDelayed({
                    if (running && conversationMode) {
                        captureCommand()
                    }
                }, 150)
            } else {
                mainHandler.postDelayed({
                    if (running) runWakeRecognizer()
                }, 150)
            }
            return
        }

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "javis_bg_utterance"
        )

        // The microphone is opened after Javis finishes speaking.
        // This prevents Javis from hearing its own voice.
        mainHandler.postDelayed({
            if (!running) return@postDelayed

            if (conversationMode) {
                captureCommand()
            } else {
                runWakeRecognizer()
            }
        }, 1200)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JAVIS background listening", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JAVIS")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF2FD8FF.toInt())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    companion object {
        private const val CHANNEL_ID = "javis_wake_word_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
