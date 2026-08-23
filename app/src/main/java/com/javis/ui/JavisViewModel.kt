package com.javis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.javis.BuildConfig
import com.javis.ai.AIBackend
import com.javis.ai.AnthropicBackend
import com.javis.ai.GeminiBackend
import com.javis.ai.LLMPIBackend
import com.javis.ai.MockBackend
import com.javis.assistant.ConversationTurn
import com.javis.assistant.JavisAssistantEngine
import com.javis.assistant.PendingConfirmation
import com.javis.data.ApiKeyStore
import com.javis.voice.VoiceEvent
import com.javis.voice.VoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JavisUiState(
    val messages: List<ConversationTurn> = emptyList(),
    val isThinking: Boolean = false,
    val isListening: Boolean = false,
    val isOnline: Boolean = true,
    val statusText: String = "Listening...",
    val pendingConfirmation: PendingConfirmation? = null,
    val lastError: String? = null,
    val hasApiKey: Boolean = false
)

class JavisViewModel(application: Application) : AndroidViewModel(application) {

    private val apiKeyStore = ApiKeyStore(application)

    private fun pickBackend(): AIBackend {
        val geminiKey = apiKeyStore.getGeminiKey()
        if (!geminiKey.isNullOrBlank()) {
            return GeminiBackend(geminiKey)
        }
        val anthropicKey = apiKeyStore.getApiKey()
        if (!anthropicKey.isNullOrBlank()) {
            return AnthropicBackend(anthropicKey)
        }
        val hasRealEndpoint = BuildConfig.LLMPI_BASE_URL.isNotBlank() &&
            !BuildConfig.LLMPI_BASE_URL.contains("example.invalid")
        return if (hasRealEndpoint) {
            LLMPIBackend(BuildConfig.LLMPI_BASE_URL, BuildConfig.LLMPI_API_KEY)
        } else {
            MockBackend()
        }
    }

    private var engine = JavisAssistantEngine(application, pickBackend())

    private val voiceManager = VoiceManager(application) { event -> handleVoiceEvent(event) }

    private val _uiState = MutableStateFlow(JavisUiState())
    val uiState: StateFlow<JavisUiState> = _uiState.asStateFlow()

    private fun anyKeySet(): Boolean =
        !apiKeyStore.getGeminiKey().isNullOrBlank() || !apiKeyStore.getApiKey().isNullOrBlank()

    init {
        refreshOnlineStatus()
        _uiState.update { it.copy(hasApiKey = anyKeySet()) }
        pushJavisGreeting()
    }

    private fun pushJavisGreeting() {
        val greeting = if (_uiState.value.hasApiKey) {
            "Hi — I'm connected and ready to talk. Ask me anything, or tell me what to do."
        } else {
            "I'm running in local mock mode right now. Add a free Gemini key or a paid " +
                "Anthropic key in settings to talk with me for real."
        }
        _uiState.update {
            it.copy(messages = it.messages + ConversationTurn("javis", greeting))
        }
    }

    fun refreshOnlineStatus() {
        _uiState.update { it.copy(isOnline = engine.isOnline()) }
    }

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return
        apiKeyStore.saveApiKey(trimmed)
        engine = JavisAssistantEngine(getApplication(), pickBackend())
        _uiState.update {
            it.copy(
                hasApiKey = anyKeySet(),
                messages = it.messages + ConversationTurn(
                    "javis", "Connected. I can talk properly now — try asking me something."
                )
            )
        }
    }

    fun clearApiKey() {
        apiKeyStore.clearApiKey()
        engine = JavisAssistantEngine(getApplication(), pickBackend())
        _uiState.update {
            it.copy(
                hasApiKey = anyKeySet(),
                messages = it.messages + ConversationTurn(
                    "javis", "Anthropic key removed."
                )
            )
        }
    }

    fun saveGeminiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return
        apiKeyStore.saveGeminiKey(trimmed)
        engine = JavisAssistantEngine(getApplication(), pickBackend())
        _uiState.update {
            it.copy(
                hasApiKey = anyKeySet(),
                messages = it.messages + ConversationTurn(
                    "javis", "Connected via Gemini (free). I can talk properly now — try asking me something."
                )
            )
        }
    }

    fun clearGeminiKey() {
        apiKeyStore.clearGeminiKey()
        engine = JavisAssistantEngine(getApplication(), pickBackend())
        _uiState.update {
            it.copy(
                hasApiKey = anyKeySet(),
                messages = it.messages + ConversationTurn(
                    "javis", "Gemini key removed."
                )
            )
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isThinking = true, statusText = "Thinking...", lastError = null) }
            val outcome = engine.handleUserInput(text)
            _uiState.update {
                it.copy(
                    messages = engine.history,
                    isThinking = false,
                    statusText = "Listening...",
                    pendingConfirmation = outcome.pendingConfirmation
                )
            }
            if (outcome.pendingConfirmation == null && outcome.spoken) {
                voiceManager.speak(outcome.displayText)
            }
        }
    }

    fun confirmPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingConfirmation = null, isThinking = true) }
            val outcome = engine.confirmAndExecute(pending.command)
            _uiState.update {
                it.copy(messages = engine.history, isThinking = false, statusText = "Listening...")
            }
            voiceManager.speak(outcome.displayText)
        }
    }

    fun cancelPendingAction() {
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                messages = it.messages + ConversationTurn("javis", "Okay, cancelled.")
            )
        }
    }

    fun clearConversation() {
        engine.clearConversation()
        _uiState.update { it.copy(messages = emptyList()) }
        pushJavisGreeting()
    }

    fun startListening() {
        if (!voiceManager.isRecognitionAvailable()) {
            _uiState.update { it.copy(lastError = "Voice recognition isn't available on this device.") }
            return
        }
        voiceManager.startListening()
    }

    fun stopListening() {
        voiceManager.stopListening()
    }

    private fun handleVoiceEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.ListeningStarted -> _uiState.update { it.copy(isListening = true, statusText = "Listening...") }
            is VoiceEvent.ListeningEnded -> _uiState.update { it.copy(isListening = false) }
            is VoiceEvent.RecognizedText -> sendText(event.text)
            is VoiceEvent.Error -> _uiState.update {
                it.copy(isListening = false, lastError = event.message)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.release()
    }
}
