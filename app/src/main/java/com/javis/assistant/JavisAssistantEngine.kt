package com.javis.assistant

import android.content.Context
import com.javis.ai.AIBackend
import com.javis.ai.AIRequest
import com.javis.ai.DeviceContext
import com.javis.commands.CommandRouter
import com.javis.commands.JavisCommand
import com.javis.tools.ToolRegistry
import com.javis.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

data class ConversationTurn(val role: String, val text: String) // role: "user" | "javis"

data class AssistantOutcome(
    val displayText: String,
    val spoken: Boolean = true,
    val pendingConfirmation: PendingConfirmation? = null
)

data class PendingConfirmation(val command: JavisCommand, val summary: String)

/**
 * The full pipeline described in the spec:
 * Input -> normalize -> offline check -> AI request -> validate ->
 * route -> permission/confirmation check -> execute -> response.
 *
 * onlineBackend is used when the device has connectivity; otherwise
 * offlineEngine handles the (smaller) supported command set.
 */
class JavisAssistantEngine(
    context: Context,
    private val onlineBackend: AIBackend,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    private val toolRegistry: ToolRegistry = ToolRegistry(context)
) {
    private val offlineEngine = OfflineEngine()
    private val conversationId: String = UUID.randomUUID().toString()

    private val _history = mutableListOf<ConversationTurn>()
    val history: List<ConversationTurn> get() = _history.toList()

    fun clearConversation() {
        _history.clear()
    }

    fun isOnline(): Boolean = networkMonitor.isOnline()

    /**
     * Main entry point. Returns the final AssistantOutcome, or a
     * PendingConfirmation if the requested action needs explicit user
     * approval before it executes (call confirmAndExecute to proceed).
     */
    suspend fun handleUserInput(rawInput: String): AssistantOutcome {
        val normalized = rawInput.trim()
        if (normalized.isBlank()) {
            return AssistantOutcome("I didn't catch that — could you say that again?")
        }

        _history.add(ConversationTurn("user", normalized))

        val online = networkMonitor.isOnline()
        val backend = if (online) onlineBackend else offlineEngine

        val aiResult = backend.process(
            AIRequest(
                message = normalized,
                conversationId = conversationId,
                deviceContext = buildDeviceContext(online)
            )
        )

        val aiResponse = aiResult.getOrElse { error ->
            val message = "I couldn't reach the AI backend (${error.message ?: "unknown error"})." +
                if (!online) " You're currently offline." else ""
            _history.add(ConversationTurn("javis", message))
            return AssistantOutcome(message)
        }

        val command = CommandRouter.route(aiResponse)

        return when (command) {
            is JavisCommand.PlainResponse -> {
                _history.add(ConversationTurn("javis", command.message))
                AssistantOutcome(command.message)
            }
            is JavisCommand.Unsupported -> {
                _history.add(ConversationTurn("javis", command.reason))
                AssistantOutcome(command.reason)
            }
            is JavisCommand.Speak -> {
                _history.add(ConversationTurn("javis", command.text))
                AssistantOutcome(command.text)
            }
            else -> {
                if (aiResponse.requiresConfirmation) {
                    val summary = aiResponse.message ?: "JAVIS wants to perform an action."
                    AssistantOutcome(
                        displayText = "$summary\n\nConfirm this action?",
                        spoken = false,
                        pendingConfirmation = PendingConfirmation(command, summary)
                    )
                } else {
                    executeCommand(command)
                }
            }
        }
    }

    /** Called after the user taps Confirm on a PendingConfirmation. */
    suspend fun confirmAndExecute(command: JavisCommand): AssistantOutcome = executeCommand(command)

    private suspend fun executeCommand(command: JavisCommand): AssistantOutcome {
        val result = toolRegistry.execute(command)
        val text = when (result) {
            is ToolResult.Success -> result.message
            is ToolResult.Failure -> result.reason
            is ToolResult.NeedsConfirmation -> result.message
        }
        _history.add(ConversationTurn("javis", text))
        return AssistantOutcome(text)
    }

    private fun buildDeviceContext(online: Boolean): DeviceContext {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        return DeviceContext(
            networkAvailable = online,
            currentTimeIso = fmt.format(java.util.Date())
        )
    }
}
