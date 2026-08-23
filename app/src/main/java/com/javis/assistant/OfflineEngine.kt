package com.javis.assistant

import com.javis.ai.AIBackend
import com.javis.ai.AIRequest
import com.javis.ai.AIResponse
import kotlinx.coroutines.delay

/**
 * Handles requests locally when there's no network — a small, honest
 * subset of capabilities. Never pretends the cloud LLMPI is reachable.
 * Anything outside this subset gets a clear "needs internet" response
 * instead of a wrong or hallucinated answer.
 */
class OfflineEngine : AIBackend {

    override suspend fun process(request: AIRequest): Result<AIResponse> {
        delay(150)
        val text = request.message.trim().lowercase()

        val response = when {
            text.startsWith("open ") -> AIResponse(
                type = "action",
                action = "open_app",
                target = request.message.substring(5).trim(),
                message = "Opening that app."
            )
            text.contains("time") || text.contains("date") -> AIResponse(
                type = "action", action = "get_time", message = "Checking the time."
            )
            text.matches(Regex(".*\\d+\\s*[*+/-]\\s*\\d+.*")) || text.startsWith("calculate") -> AIResponse(
                type = "action", action = "calculator", target = request.message, message = "Calculating."
            )
            text.contains("note") && (text.contains("create") || text.contains("save")) -> AIResponse(
                type = "action", action = "create_note", target = request.message, message = "Saving your note."
            )
            text.contains("read") && text.contains("note") -> AIResponse(
                type = "action", action = "read_note", message = "Reading your note."
            )
            text.contains("wifi") || text.contains("wi-fi") || text.contains("bluetooth") || text.contains("settings") -> {
                val section = when {
                    text.contains("bluetooth") -> "bluetooth"
                    text.contains("wifi") || text.contains("wi-fi") -> "wifi"
                    else -> "general"
                }
                AIResponse(type = "action", action = "open_settings", target = section, message = "Opening settings.")
            }
            text.contains("search") -> AIResponse(
                type = "response",
                message = "Web search needs an internet connection, which isn't available right now."
            )
            else -> AIResponse(
                type = "response",
                message = "JAVIS is offline right now, so I can only help with a few things: " +
                    "opening apps, the time, calculator, notes, and quick settings shortcuts. " +
                    "Conversational answers need an internet connection."
            )
        }
        return Result.success(response)
    }
}
