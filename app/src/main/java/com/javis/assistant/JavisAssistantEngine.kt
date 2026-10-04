package com.javis.assistant

import android.content.Context
import com.javis.ai.AIBackend
import com.javis.ai.AIRequest
import com.javis.ai.DeviceContext
import com.javis.commands.CommandRouter
import com.javis.commands.JavisCommand
import com.javis.commands.UniversalAction
import com.javis.commands.UniversalActionExecutor
import com.javis.commands.UniversalActionResult
import com.javis.commands.UniversalIntentRouter
import com.javis.tools.ToolRegistry
import com.javis.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

data class ConversationTurn(
    val role: String,
    val text: String
)

data class AssistantOutcome(
    val displayText: String,
    val spoken: Boolean = true,
    val pendingConfirmation: PendingConfirmation? = null
)

data class PendingConfirmation(
    val command: JavisCommand,
    val summary: String
)

class JavisAssistantEngine(
    context: Context,
    private val onlineBackend: AIBackend,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    private val toolRegistry: ToolRegistry = ToolRegistry(context)
) {
    private val appContext = context.applicationContext

    private val offlineEngine = OfflineEngine()
    private val conversationId = UUID.randomUUID().toString()

    private val memory = ConversationMemory(maxTurns = 24)
    private val contextEngine = ContextEngine()

    private val universalExecutor =
        UniversalActionExecutor(appContext)

    private var pendingUniversalAction: UniversalAction? = null
    private var pendingUniversalSummary: String? = null

    val history: List<ConversationTurn>
        get() = memory.recentTurns()

    fun clearConversation() {
        memory.clear()
        contextEngine.clear()

        pendingUniversalAction = null
        pendingUniversalSummary = null
    }

    fun isOnline(): Boolean =
        networkMonitor.isOnline()

    /**
     * Main JAVIS pipeline:
     *
     * 1. Normalize input.
     * 2. Handle local confirmation/cancellation.
     * 3. Try UniversalIntentRouter first.
     * 4. Execute local Android action when recognized.
     * 5. Fall back to the existing offline/online AI pipeline.
     */
    suspend fun handleUserInput(
        rawInput: String
    ): AssistantOutcome {

        val normalized = rawInput.trim()

        if (normalized.isBlank()) {
            return AssistantOutcome(
                "I didn't catch that — could you say that again?"
            )
        }

        /*
         * LOCAL CONFIRMATION LAYER
         *
         * This runs before AI, allowing spoken commands such as:
         * "yes"
         * "confirm"
         * "do it"
         * "cancel"
         * "no"
         * "stop"
         */
        if (pendingUniversalAction != null) {
            val confirmation = parseConfirmation(normalized)

            if (confirmation == true) {
                val action = pendingUniversalAction
                    ?: return AssistantOutcome(
                        "There is no pending action."
                    )

                val summary =
                    pendingUniversalSummary
                        ?: "the requested action"

                pendingUniversalAction = null
                pendingUniversalSummary = null

                val result =
                    confirmUniversalAction(action)

                return recordUniversalResult(
                    result,
                    summary
                )
            }

            if (confirmation == false) {
                pendingUniversalAction = null
                pendingUniversalSummary = null

                val text = "Okay, cancelled."

                memory.addUser(normalized)
                memory.addJavis(text)
                contextEngine.recordUser(normalized)
                contextEngine.recordJavis(text)

                return AssistantOutcome(text)
            }

            /*
             * A new unrelated command while confirmation is pending
             * cancels the old confirmation first.
             */
            pendingUniversalAction = null
            pendingUniversalSummary = null
        }

        memory.addUser(normalized)
        contextEngine.recordUser(normalized)

        /*
         * UNIVERSAL LOCAL ACTION ROUTER
         *
         * This is intentionally BEFORE network detection and BEFORE
         * the AI backend. Device actions should not require the cloud.
         */
        val universalAction =
            UniversalIntentRouter.route(normalized)

        if (universalAction != null) {
            return handleUniversalAction(universalAction)
        }

        /*
         * EXISTING AI PIPELINE
         */
        val online = networkMonitor.isOnline()

        val backend =
            if (online) {
                onlineBackend
            } else {
                offlineEngine
            }

        val contextPrefix =
            if (online) {
                contextEngine.buildPromptPrefix(
                    memory.recentTurns()
                )
            } else {
                ""
            }

        val effectiveMessage =
            if (contextPrefix.isBlank()) {
                normalized
            } else {
                "$contextPrefix\n\nCurrent user message: $normalized"
            }

        val aiResult = backend.process(
            AIRequest(
                message = effectiveMessage,
                conversationId = conversationId,
                deviceContext = buildDeviceContext(online)
            )
        )

        val aiResponse =
            aiResult.getOrElse { error ->

                val message =
                    "I couldn't reach the AI backend " +
                        "(${error.message ?: "unknown error"})." +
                        if (!online) {
                            " You're currently offline."
                        } else {
                            ""
                        }

                memory.addJavis(message)
                contextEngine.recordJavis(message)

                return AssistantOutcome(message)
            }

        val command = CommandRouter.route(aiResponse)

        return when (command) {

            is JavisCommand.PlainResponse -> {
                memory.addJavis(command.message)
                contextEngine.recordJavis(command.message)

                AssistantOutcome(command.message)
            }

            is JavisCommand.Unsupported -> {
                memory.addJavis(command.reason)
                contextEngine.recordJavis(command.reason)

                AssistantOutcome(command.reason)
            }

            is JavisCommand.Speak -> {
                memory.addJavis(command.text)
                contextEngine.recordJavis(command.text)

                AssistantOutcome(command.text)
            }

            else -> {
                if (aiResponse.requiresConfirmation) {

                    val summary =
                        aiResponse.message
                            ?: "JAVIS wants to perform an action."

                    AssistantOutcome(
                        displayText =
                            "$summary\n\nConfirm this action?",
                        spoken = false,
                        pendingConfirmation =
                            PendingConfirmation(
                                command,
                                summary
                            )
                    )
                } else {
                    executeCommand(command)
                }
            }
        }
    }

    /**
     * Handles a UniversalAction returned by the local router.
     */
    private suspend fun handleUniversalAction(
        action: UniversalAction
    ): AssistantOutcome {

        val result = universalExecutor.execute(action)

        return when (result) {

            is UniversalActionResult.Success -> {
                recordUniversalResult(
                    result,
                    null
                )
            }

            is UniversalActionResult.Failure -> {
                recordUniversalResult(
                    result,
                    null
                )
            }

            is UniversalActionResult.Unsupported -> {
                recordUniversalResult(
                    result,
                    null
                )
            }

            is UniversalActionResult.NeedsConfirmation -> {

                pendingUniversalAction =
                    result.action

                pendingUniversalSummary =
                    result.message

                memory.addJavis(result.message)
                contextEngine.recordJavis(result.message)

                AssistantOutcome(
                    displayText =
                        "${result.message}\n\nConfirm this action?",
                    spoken = false
                )
            }
        }
    }

    /**
     * Executes a confirmed UniversalAction.
     *
     * The executor exposes specialized confirmation methods for
     * sensitive actions, while ordinary actions can be executed
     * directly.
     */
    private fun confirmUniversalAction(
        action: UniversalAction
    ): UniversalActionResult {

        return when (action) {

            is UniversalAction.Call ->
                universalExecutor.confirmCall(action)

            is UniversalAction.Message ->
                universalExecutor.confirmMessage(action)

            is UniversalAction.Share ->
                universalExecutor.confirmShare(action)

            else ->
                universalExecutor.execute(action)
        }
    }

    /**
     * Existing UI confirmation path for AI-generated JavisCommand.
     */
    suspend fun confirmAndExecute(
        command: JavisCommand
    ): AssistantOutcome =
        executeCommand(command)

    /**
     * Records and returns the result of a UniversalAction.
     */
    private fun recordUniversalResult(
        result: UniversalActionResult,
        pendingSummary: String?
    ): AssistantOutcome {

        val text = when (result) {

            is UniversalActionResult.Success ->
                result.message

            is UniversalActionResult.Failure ->
                result.message

            is UniversalActionResult.Unsupported ->
                result.message

            is UniversalActionResult.NeedsConfirmation ->
                result.message
        }

        memory.addJavis(text)
        contextEngine.recordJavis(text)

        return AssistantOutcome(
            displayText = text,
            spoken = true
        )
    }

    /**
     * yes / no detection for UniversalAction confirmation.
     */
    private fun parseConfirmation(
        input: String
    ): Boolean? {

        val normalized =
            input
                .trim()
                .lowercase(Locale.getDefault())
                .replace(Regex("[.!?,]+$"), "")
                .trim()

        val yes = setOf(
            "yes",
            "yeah",
            "yep",
            "yup",
            "confirm",
            "confirmed",
            "do it",
            "go ahead",
            "proceed",
            "okay",
            "ok",
            "sure",
            "send it",
            "call them",
            "continue"
        )

        val no = setOf(
            "no",
            "nope",
            "nah",
            "cancel",
            "cancel it",
            "stop",
            "stop it",
            "don't",
            "do not",
            "never mind",
            "never mind",
            "forget it"
        )

        return when {
            normalized in yes -> true
            normalized in no -> false
            else -> null
        }
    }

    /**
     * Executes an existing JavisCommand through the established
     * ToolRegistry security boundary.
     */
    private suspend fun executeCommand(
        command: JavisCommand
    ): AssistantOutcome {

        val result =
            toolRegistry.execute(command)

        val text =
            when (result) {

                is ToolResult.Success ->
                    result.message

                is ToolResult.Failure ->
                    result.reason

                is ToolResult.NeedsConfirmation ->
                    result.message
            }

        memory.addJavis(text)
        contextEngine.recordJavis(text)

        return AssistantOutcome(text)
    }

    private fun buildDeviceContext(
        online: Boolean
    ): DeviceContext {

        val fmt =
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                Locale.getDefault()
            )

        return DeviceContext(
            networkAvailable = online,
            currentTimeIso =
                fmt.format(java.util.Date())
        )
    }
}
