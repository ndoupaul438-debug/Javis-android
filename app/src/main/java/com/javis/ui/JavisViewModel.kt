package com.javis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.javis.BuildConfig
import com.javis.ai.LLMPIBackend
import com.javis.ai.MockBackend
import com.javis.assistant.ConversationTurn
import com.javis.assistant.JavisAssistantEngine
import com.javis.assistant.PendingConfirmation
import com.javis.commands.JavisCommand
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
    val lastError: String? = null
)

class JavisViewModel(application: Application) : AndroidViewModel(application) {

    // Real backend uses LLMPIBackend with BuildConfig values (from local.properties).
    // Falls back to MockBackend automatically if no endpoint is configured, so the
    // app is fully usable out of the box for development/demo purposes.
    private val hasRealEndpoint = BuildConfig.LLMPI_BASE_URL.isNotBlank() &&
        !BuildConfig.LLMPI_BASE_URL.contains("example.invalid")

    private val onlineBackend = if (hasRealEndpoint) {
        LLMPIBackend(BuildConfig.LLMPI_BASE_URL, BuildConfig.LLMPI_API_KEY)
    } else {
        MockBackend()
    }

    private val engine = JavisAssistantEngine(application, onlineBackend)

    private val voiceManager = VoiceManager(application) { event -> handleVoiceEvent(event) }

    private val _uiState = MutableStateFlow(JavisUiState())
    val uiState: StateFlow<JavisUiState> = _uiState.asStateFlow()

    init {
        refreshOnlineStatus()
        pushJavisGreeting()
    }

    private fun pushJavisGreeting() {
        _uiState.update {
            it.copy(messages = it.messages + ConversationTurn(
                "javis",
                "All systems operational. Ask me anything, or try a quick command."
            ))
        }
    }

    fun refreshOnlineStatus() {
        _uiState.update { it.copy(isOnline = engine.isOnline()) }
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
