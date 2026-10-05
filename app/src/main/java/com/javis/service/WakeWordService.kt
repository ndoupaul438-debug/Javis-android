package com.javis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import com.javis.voice.JavisVoiceController
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
    private var conversationMode = false
    private var processingCommand = false
    private var recognitionActive = false
    private var silenceRetries = 0

    private var orbView: View? = null
    private var orbWindowManager: WindowManager? = null
    private var orbRotation = 0f
    private var orbAnimator: android.animation.ValueAnimator? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false

    private lateinit var engine: JavisAssistantEngine
    private var orbVoiceController: JavisVoiceController? = null


    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS

            if (ttsReady) {
                tts?.language = Locale.getDefault()

                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            WakeWordServiceState.setStatus(
                                ListeningStatus.SPEAKING
                            )
                        }

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                releaseVoiceAudioSession()

                                if (!running) return@post

                                if (conversationMode && !processingCommand) {
                                    captureCommand()
                                } else if (!conversationMode) {
                                    scheduleWakeRestart(150)
                                }
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                releaseVoiceAudioSession()

                                if (!running) return@post

                                if (conversationMode && !processingCommand) {
                                    captureCommand()
                                } else if (!conversationMode) {
                                    scheduleWakeRestart(150)
                                }
                            }
                        }
                    }
                )
            }
        }

        orbVoiceController = JavisVoiceController(this).apply {
            onTextRecognized = { text ->
                handleCommand(text)
            }
        }

        engine = JavisAssistantEngine(
            applicationContext,
            pickBackend()
        )

        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!running) {
            running = true

            WakeWordServiceState.setRunning(true)

            showJavisOrb()

            startForeground(
                NOTIFICATION_ID,
                buildNotification("Listening for \"Hey Javis\"...")
            )

            runWakeRecognizer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false

        WakeWordServiceState.setRunning(false)

        cancelPendingCallbacks()

        wakeRecognizer?.cancel()
        wakeRecognizer?.destroy()
        wakeRecognizer = null

        commandRecognizer?.cancel()
        commandRecognizer?.destroy()
        commandRecognizer = null
        orbVoiceController?.destroy()
        orbVoiceController = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        releaseVoiceAudioSession()

        hideJavisOrb()

        super.onDestroy()
    }


    private fun showJavisOrb() {
        if (orbView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        orbWindowManager = wm

        val density = resources.displayMetrics.density
        val size = (44 * density).toInt()
        val prefs = getSharedPreferences("javis_orb", Context.MODE_PRIVATE)

        val orb = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(12, 24, 42))
                setStroke(
                    (2 * density).toInt(),
                    Color.rgb(47, 216, 255)
                )
            }
            elevation = 18f
            contentDescription = "JAVIS floating orb"
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val defaultX = (8 * density).toInt()
        val defaultY = (18 * density).toInt()

        val savedX = prefs.getInt("orb_x", defaultX)
        val savedY = prefs.getInt("orb_y", defaultY)

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, (screenWidth - size).coerceAtLeast(0))
            y = savedY.coerceIn(0, (screenHeight - size).coerceAtLeast(0))
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        orb.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    if (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6) {
                        moved = true
                    }

                    params.x = (startX + dx).coerceIn(
                        0,
                        (screenWidth - size).coerceAtLeast(0)
                    )

                    params.y = (startY + dy).coerceIn(
                        0,
                        (screenHeight - size).coerceAtLeast(0)
                    )

                    try {
                        wm.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }

                    true
                }

                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleListeningFromOrb()
                    } else {
                        val maxX = (screenWidth - size).coerceAtLeast(0)
                        val targetX = if (params.x < maxX / 2) 0 else maxX

                        val animator = android.animation.ValueAnimator.ofInt(
                            params.x,
                            targetX
                        ).apply {
                            duration = 180L
                            addUpdateListener {
                                params.x = it.animatedValue as Int
                                try {
                                    wm.updateViewLayout(view, params)
                                } catch (_: Exception) {
                                }
                            }
                        }

                        animator.start()
                        params.x = targetX
                    }

                    prefs.edit()
                        .putInt("orb_x", params.x)
                        .putInt("orb_y", params.y)
                        .apply()

                    true
                }

                android.view.MotionEvent.ACTION_CANCEL -> {
                    true
                }

                else -> true
            }
        }

        try {
            wm.addView(orb, params)
            orbView = orb
        } catch (_: Exception) {
            orbView = null
            orbWindowManager = null
            return
        }

        orbAnimator?.cancel()

        orbAnimator = android.animation.ValueAnimator.ofFloat(
            0f,
            360f
        ).apply {
            duration = 2200L
            repeatCount = android.animation.ValueAnimator.INFINITE

            addUpdateListener {
                orbRotation = it.animatedValue as Float
                orb.rotation = orbRotation
            }

            start()
        }
    }

    private fun toggleListeningFromOrb() {
        if (!running) return

        if (conversationMode) {
            endConversation()
            return
        }

        if (processingCommand || recognitionActive) {
            return
        }

        cancelWakeRestartCallbacks()
        wakeRecognizer?.cancel()
        recognitionActive = false

        conversationMode = true
        processingCommand = false
        silenceRetries = 0

        WakeWordServiceState.setStatus(
            ListeningStatus.LISTENING_FOR_COMMAND
        )

        updateNotification("JAVIS orb is listening...")

        orbVoiceController?.start()
    }

    private fun hideJavisOrb() {
        orbAnimator?.cancel()
        orbAnimator = null

        orbView?.let { view ->
            try {
                orbWindowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }

        orbView = null
        orbWindowManager = null
    }

    private fun requestVoiceAudioFocus() {
        val manager = audioManager ?: return

        if (audioFocusHeld) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .build()

            val result = manager.requestAudioFocus(
                audioFocusRequest!!
            )

            audioFocusHeld =
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = manager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            audioFocusHeld =
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseVoiceAudioSession() {
        val manager = audioManager ?: return

        if (!audioFocusHeld) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                manager.abandonAudioFocusRequest(it)
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }

        audioFocusHeld = false
    }


    private val WAKE_RESTART_TOKEN = Any()
    private val CONVERSATION_TOKEN = Any()

    private fun cancelWakeRestartCallbacks() {
        mainHandler.removeCallbacksAndMessages(
            WAKE_RESTART_TOKEN
        )
    }

    private fun cancelConversationCallbacks() {
        mainHandler.removeCallbacksAndMessages(
            CONVERSATION_TOKEN
        )
    }

    private fun cancelPendingCallbacks() {
        cancelWakeRestartCallbacks()
        cancelConversationCallbacks()
    }

    private fun scheduleWakeRestart(delayMillis: Long) {
        cancelWakeRestartCallbacks()

        mainHandler.postAtTime(
            {
                if (!running) return@postAtTime
                if (conversationMode) return@postAtTime
                if (recognitionActive) return@postAtTime

                runWakeRecognizer()
            },
            WAKE_RESTART_TOKEN,
            android.os.SystemClock.uptimeMillis() + delayMillis
        )
    }

    private fun scheduleConversationRetry(delayMillis: Long) {
        cancelConversationCallbacks()

        mainHandler.postAtTime(
            {
                if (!running) return@postAtTime
                if (!conversationMode) return@postAtTime
                if (processingCommand) return@postAtTime
                if (recognitionActive) return@postAtTime

                captureCommand()
            },
            CONVERSATION_TOKEN,
            android.os.SystemClock.uptimeMillis() + delayMillis
        )
    }


    private fun runWakeRecognizer() {
        if (!running) return
        if (conversationMode) return
        if (recognitionActive) return

        cancelWakeRestartCallbacks()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition unavailable")
            scheduleWakeRestart(2000)
            return
        }

        wakeRecognizer?.cancel()
        wakeRecognizer?.destroy()

        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        wakeRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    recognitionActive = true

                    WakeWordServiceState.setStatus(
                        ListeningStatus.LISTENING_FOR_WAKE
                    )

                    updateNotification(
                        "Listening for \"Hey Javis\"..."
                    )
                }

                override fun onBeginningOfSpeech() {
                    recognitionActive = true
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    recognitionActive = false
                }

                override fun onError(error: Int) {
                    recognitionActive = false

                    if (!running || conversationMode) return

                    when (error) {
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            scheduleWakeRestart(500)
                        }

                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            scheduleWakeRestart(250)
                        }

                        else -> {
                            scheduleWakeRestart(1000)
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    recognitionActive = false

                    if (!running || conversationMode) return

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    ) ?: return

                    for (text in matches) {
                        if (checkForWakeWord(text)) {
                            startConversation()
                            return
                        }
                    }

                    scheduleWakeRestart(150)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!running || conversationMode) return

                    val matches = partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    ) ?: return

                    for (text in matches) {
                        if (checkForWakeWord(text)) {
                            wakeRecognizer?.cancel()
                            recognitionActive = false
                            startConversation()
                            return
                        }
                    }
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {}
            }
        )

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
        }

        try {
            wakeRecognizer?.startListening(intent)
        } catch (_: Exception) {
            recognitionActive = false
            scheduleWakeRestart(700)
        }
    }

    private fun checkForWakeWord(text: String): Boolean {
        val normalized = text
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized == "hey javis" ||
            normalized.startsWith("hey javis ") ||
            normalized == "hey jarvis" ||
            normalized.startsWith("hey jarvis ") ||
            normalized == "javis" ||
            normalized.startsWith("javis ") ||
            normalized == "jarvis" ||
            normalized.startsWith("jarvis ")
    }


    private fun startConversation() {
        if (!running) return

        cancelWakeRestartCallbacks()
        cancelConversationCallbacks()

        wakeRecognizer?.cancel()
        recognitionActive = false

        conversationMode = true
        processingCommand = false
        silenceRetries = 0

        WakeWordServiceState.setStatus(
            ListeningStatus.SPEAKING
        )

        updateNotification("JAVIS is ready — talk naturally")

        speakText("Yes?")
    }

    private fun endConversation() {
        if (!running) return

        conversationMode = false
        processingCommand = false
        recognitionActive = false
        silenceRetries = 0

        cancelConversationCallbacks()

        commandRecognizer?.cancel()
        orbVoiceController?.stop()

        WakeWordServiceState.setStatus(
            ListeningStatus.LISTENING_FOR_WAKE
        )

        updateNotification(
            "Listening for \"Hey Javis\"..."
        )

        scheduleWakeRestart(250)
    }


    private fun captureCommand() {
        if (!running) return
        if (!conversationMode) return
        if (processingCommand) return
        if (recognitionActive) return

        cancelConversationCallbacks()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition unavailable")
            scheduleConversationRetry(1500)
            return
        }

        if (commandRecognizer == null) {
            commandRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            commandRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(
                        params: Bundle?
                    ) {
                        recognitionActive = true
                        silenceRetries = 0

                        WakeWordServiceState.setStatus(
                            ListeningStatus.LISTENING_FOR_COMMAND
                        )

                        updateNotification(
                            "JAVIS is listening..."
                        )
                    }

                    override fun onBeginningOfSpeech() {
                        recognitionActive = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(
                        buffer: ByteArray?
                    ) {}

                    override fun onEndOfSpeech() {
                        recognitionActive = false
                    }

                    override fun onError(error: Int) {
                        recognitionActive = false

                        if (!running || !conversationMode) return

                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                silenceRetries++

                                if (silenceRetries <= 2) {
                                    scheduleConversationRetry(250)
                                } else {
                                    endConversation()
                                }
                            }

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                scheduleConversationRetry(500)
                            }

                            else -> {
                                scheduleConversationRetry(700)
                            }
                        }
                    }

                    override fun onResults(
                        results: Bundle?
                    ) {
                        recognitionActive = false

                        if (!running || !conversationMode) return

                        val matches =
                            results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                        val text = matches
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        if (text.isBlank()) {
                            silenceRetries++

                            if (silenceRetries <= 2) {
                                scheduleConversationRetry(250)
                            } else {
                                endConversation()
                            }

                            return
                        }

                        silenceRetries = 0
                        handleCommand(text)
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {
                        if (!running || !conversationMode) return
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )
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
                1400L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1100L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                800L
            )
        }

        try {
            commandRecognizer?.startListening(intent)
        } catch (_: Exception) {
            recognitionActive = false
            scheduleConversationRetry(500)
        }
    }


    private fun handleCommand(text: String) {
        if (!running || !conversationMode) return
        if (processingCommand) return

        processingCommand = true

        WakeWordServiceState.setStatus(
            ListeningStatus.THINKING
        )

        updateNotification("JAVIS is thinking...")

        Thread {
            val outcome = try {
                kotlinx.coroutines.runBlocking {
                    engine.handleUserInput(text)
                }
            } catch (_: Exception) {
                AssistantOutcome(
                    displayText =
                        "I'm sorry, something went wrong while processing that.",
                    spoken = true
                )
            }

            mainHandler.post {
                if (!running) return@post

                processingCommand = false

                val confirmation = outcome.pendingConfirmation

                if (confirmation != null) {
                    speakText(
                        "Please confirm that action in Javis."
                    )
                    return@post
                }

                if (outcome.spoken &&
                    outcome.displayText.isNotBlank()
                ) {
                    speakText(outcome.displayText)

                    if (conversationMode) {
                        mainHandler.postDelayed({
                            if (running && conversationMode && !processingCommand) {
                                orbVoiceController?.start()
                            }
                        }, 650L)
                    }
                } else if (conversationMode) {
                    mainHandler.postDelayed({
                        if (running && conversationMode && !processingCommand) {
                            orbVoiceController?.start()
                        }
                    }, 150L)
                } else {
                    scheduleWakeRestart(150)
                }
            }
        }.start()
    }


    private fun speakText(text: String) {
        if (!running) return

        val cleanText = text.trim()

        if (cleanText.isBlank()) {
            releaseVoiceAudioSession()

            if (conversationMode && !processingCommand) {
                scheduleConversationRetry(150)
            } else if (!conversationMode) {
                scheduleWakeRestart(150)
            }

            return
        }

        WakeWordServiceState.setStatus(
            ListeningStatus.SPEAKING
        )

        updateNotification("JAVIS is speaking...")

        requestVoiceAudioFocus()

        if (!ttsReady || tts == null) {
            releaseVoiceAudioSession()

            if (conversationMode && !processingCommand) {
                scheduleConversationRetry(150)
            } else if (!conversationMode) {
                scheduleWakeRestart(150)
            }

            return
        }

        val utteranceId =
            "javis_${System.currentTimeMillis()}"

        val result = tts?.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        if (result == TextToSpeech.ERROR) {
            releaseVoiceAudioSession()

            if (conversationMode && !processingCommand) {
                scheduleConversationRetry(150)
            } else if (!conversationMode) {
                scheduleWakeRestart(150)
            }
        }
    }


    private fun pickBackend(): AIBackend {
        val store = ApiKeyStore(applicationContext)

        val groqKey = store.getGroqKey()
        if (!groqKey.isNullOrBlank()) {
            return GroqBackend(groqKey)
        }

        val geminiKey = store.getGeminiKey()
        if (!geminiKey.isNullOrBlank()) {
            return GeminiBackend(geminiKey)
        }

        val anthropicKey = store.getApiKey()
        if (!anthropicKey.isNullOrBlank()) {
            return AnthropicBackend(anthropicKey)
        }

        val baseUrl =
            BuildConfig.LLMPI_BASE_URL

        val llmpiKey =
            BuildConfig.LLMPI_API_KEY

        if (baseUrl.isNotBlank() &&
            !baseUrl.contains("example.invalid")
        ) {
            return LLMPIBackend(
                baseUrl,
                llmpiKey.ifBlank { null }
            )
        }

        return MockBackend()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JAVIS background listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "JAVIS voice assistant background status"
            }

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        statusText: String
    ): Notification {
        val openAppIntent =
            Intent(this, MainActivity::class.java)

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("JAVIS")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF2FD8FF.toInt())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun updateNotification(
        statusText: String
    ) {
        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(statusText)
        )
    }

    companion object {
        private const val CHANNEL_ID =
            "javis_wake_word_channel"

        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent =
                Intent(context, WakeWordService::class.java)

            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(
                    context,
                    WakeWordService::class.java
                )
            )
        }
    }
}
