package com.javis.assistant

/**
 * Lightweight context layer shared by every AI backend.
 *
 * Keeps recent dialogue compact so Javis can understand follow-ups
 * such as "that one", "tell me more", and "what about them?"
 */
class ContextEngine(
    private val maxTurns: Int = 10,
    private val maxCharacters: Int = 2600
) {
    private var activeTopic: String? = null
    private var lastUserMessage: String? = null
    private var lastJavisMessage: String? = null

    fun recordUser(text: String) {
        lastUserMessage = text
        activeTopic = inferTopic(text) ?: activeTopic
    }

    fun recordJavis(text: String) {
        lastJavisMessage = text
    }

    fun clear() {
        activeTopic = null
        lastUserMessage = null
        lastJavisMessage = null
    }

    fun buildPromptPrefix(history: List<ConversationTurn>): String {
        val turns = history
            .takeLast(maxTurns)
            .dropLastWhile { it.role == "user" && it.text == lastUserMessage }
            .joinToString("\n") { turn ->
                val role = if (turn.role == "user") "User" else "Javis"
                "$role: ${turn.text.trim()}"
            }
            .takeLast(maxCharacters)

        val parts = mutableListOf<String>()

        if (!activeTopic.isNullOrBlank()) {
            parts += "Active topic: $activeTopic"
        }

        if (!lastJavisMessage.isNullOrBlank()) {
            parts += "Previous Javis reply: ${lastJavisMessage!!.take(500)}"
        }

        if (turns.isNotBlank()) {
            parts += "Recent conversation:\n$turns"
        }

        return if (parts.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine("Use the following conversation context to understand follow-up questions.")
                appendLine("Do not mention this context block unless the user asks about it.")
                append(parts.joinToString("\n"))
            }
        }
    }

    private fun inferTopic(text: String): String? {
        val normalized = text
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

        val candidates = listOf(
            "football" to listOf(
                "football", "soccer", "premier league",
                "champions league", "manchester",
                "arsenal", "chelsea", "liverpool"
            ),
            "weather" to listOf(
                "weather", "temperature", "rain", "forecast"
            ),
            "music" to listOf(
                "song", "music", "artist", "album", "playlist"
            ),
            "school" to listOf(
                "school", "homework", "exam", "mathematics",
                "maths", "physics", "chemistry", "biology"
            ),
            "coding" to listOf(
                "code", "coding", "program", "programming",
                "github", "android", "kotlin", "python"
            ),
            "shopping" to listOf(
                "buy", "price", "cost", "shop", "shopping", "product"
            ),
            "travel" to listOf(
                "travel", "flight", "hotel", "airport", "trip", "map"
            ),
            "news" to listOf(
                "news", "latest", "today", "breaking"
            )
        )

        return candidates.firstOrNull { (_, keywords) ->
            keywords.any { keyword -> normalized.contains(keyword) }
        }?.first
    }
}
