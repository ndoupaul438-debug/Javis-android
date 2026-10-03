package com.javis.assistant

/**
 * Lightweight in-process conversation memory for JAVIS.
 *
 * This is deliberately separate from the individual AI providers so the
 * assistant has one consistent conversation context regardless of whether
 * Groq, Gemini, Anthropic, or another backend is selected.
 */
class ConversationMemory(
    private val maxTurns: Int = 24
) {
    private val turns = ArrayDeque<ConversationTurn>()

    fun addUser(text: String) {
        add(ConversationTurn("user", text))
    }

    fun addJavis(text: String) {
        add(ConversationTurn("javis", text))
    }

    fun recentTurns(): List<ConversationTurn> = turns.toList()

    fun clear() {
        turns.clear()
    }

    fun isEmpty(): Boolean = turns.isEmpty()

    private fun add(turn: ConversationTurn) {
        turns.addLast(turn)

        while (turns.size > maxTurns) {
            turns.removeFirst()
        }
    }
}
