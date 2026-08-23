package com.javis.ai

import kotlinx.coroutines.delay

/**
 * A local, offline-capable "AI" so the project compiles, installs, and
 * demonstrates full app behavior without any real LLMPI credentials.
 * Understands a small set of patterns and otherwise responds honestly
 * that it's running in mock mode.
 *
 * Swap AIBackend implementations in JavisAssistantEngine to use
 * LLMPIBackend once you have real endpoint credentials.
 */
class MockBackend : AIBackend {

    override suspend fun process(request: AIRequest): Result<AIResponse> {
        delay(300) // simulate latency so the UI's "thinking" state is visible
        val text = request.message.trim().lowercase()

        val response = when {
            text.startsWith("open ") -> {
                val target = request.message.substring(5).trim()
                AIResponse(
                    type = "action",
                    action = "open_app",
                    target = target,
                    message = "Opening $target.",
                    requiresConfirmation = false
                )
            }
            text.contains("time") -> AIResponse(
                type = "action",
                action = "get_time",
                message = "Let me check the time for you."
            )
            text.startsWith("calculate") || text.matches(Regex(".*\\d+\\s*[*+/-]\\s*\\d+.*")) -> AIResponse(
                type = "action",
                action = "calculator",
                target = request.message,
                message = "Let me calculate that."
            )
            text.startsWith("search") -> {
                val query = request.message.removePrefix("search the web for")
                    .removePrefix("search for").removePrefix("search").trim()
                AIResponse(
                    type = "action",
                    action = "search_web",
                    target = query.ifBlank { request.message },
                    message = "Searching the web for \"${query.ifBlank { request.message }}\"."
                )
            }
            text.contains("note") && (text.contains("create") || text.contains("save")) -> AIResponse(
                type = "action",
                action = "create_note",
                target = request.message,
                message = "Saved that as a note."
            )
            text.contains("read") && text.contains("note") -> AIResponse(
                type = "action",
                action = "read_note",
                message = "Here's your saved note."
            )
            text.contains("wifi") || text.contains("wi-fi") -> AIResponse(
                type = "action",
                action = "open_settings",
                target = "wifi",
                message = "Opening Wi-Fi settings."
            )
            text.isBlank() -> AIResponse(
                type = "response",
                message = "I didn't catch that — could you repeat it?"
            )
            else -> AIResponse(
                type = "response",
                message = "I'm running in local mock mode right now, so I can only handle " +
                    "a few built-in commands (open apps, time, calculator, web search, notes, " +
                    "Wi-Fi settings). Connect a real LLMPI endpoint in local.properties for " +
                    "full conversational ability."
            )
        }
        return Result.success(response)
    }
}
