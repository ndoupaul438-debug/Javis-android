package com.javis.commands

import com.javis.ai.AIResponse
import com.javis.security.CommandValidator

/**
 * Converts a (trusted-but-still-verified) AIResponse into a concrete,
 * closed-set JavisCommand. This is the only place AI output is translated
 * into something executable — every field is checked against the
 * CommandValidator allowlist first.
 */
object CommandRouter {

    fun route(response: AIResponse): JavisCommand {
        if (response.type == "response") {
            val msg = response.message?.takeIf { it.isNotBlank() }
                ?: "I don't have anything to say about that."
            return JavisCommand.PlainResponse(msg)
        }

        if (response.type != "action") {
            return JavisCommand.Unsupported("Received an unrecognized response type from the AI backend.")
        }

        val action = response.action
        if (!CommandValidator.isActionAllowed(action)) {
            return JavisCommand.Unsupported(
                "The AI requested an action (\"${action ?: "unknown"}\") that isn't in JAVIS's allowed command set."
            )
        }

        val target = response.target ?: ""
        if (target.isNotBlank() && !CommandValidator.isSafeFreeText(target)) {
            return JavisCommand.Unsupported("The requested command target failed safety validation.")
        }

        return when (action) {
            "open_app" -> JavisCommand.OpenApp(target)
            "speak" -> JavisCommand.Speak(response.message ?: target)
            "search_web" -> JavisCommand.SearchWeb(target.ifBlank { response.message ?: "" })
            "show_notification" -> JavisCommand.ShowNotification(
                title = "JAVIS",
                message = response.message ?: target
            )
            "get_time" -> JavisCommand.GetTime
            "calculator" -> JavisCommand.Calculate(target.ifBlank { response.message ?: "" })
            "create_note" -> JavisCommand.CreateNote(target.ifBlank { response.message ?: "" })
            "read_note" -> JavisCommand.ReadNote
            "open_settings" -> JavisCommand.OpenSettings(target)
            else -> JavisCommand.Unsupported("Unhandled action: $action")
        }
    }
}
