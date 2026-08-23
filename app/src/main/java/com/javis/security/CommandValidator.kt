package com.javis.security

/**
 * Central allowlist of action names the AI backend is permitted to request.
 * Anything not in this set is rejected before it ever reaches a tool.
 * This is the single choke point that prevents "arbitrary AI command
 * execution" — even a compromised or misbehaving backend can only ever
 * name one of these strings, never inject code.
 */
object CommandValidator {

    val ALLOWED_ACTIONS = setOf(
        "open_app",
        "speak",
        "search_web",
        "show_notification",
        "get_time",
        "calculator",
        "create_note",
        "read_note",
        "open_settings"
    )

    /** Actions that must be confirmed by the user before executing, regardless
     *  of what the AI response says, because they have a visible side effect. */
    val ALWAYS_CONFIRM = setOf(
        "show_notification"
    )

    fun isActionAllowed(action: String?): Boolean =
        action != null && ALLOWED_ACTIONS.contains(action)

    fun requiresConfirmation(action: String, aiRequested: Boolean): Boolean =
        aiRequested || ALWAYS_CONFIRM.contains(action)

    /** Very small validators to reject obviously malformed/dangerous target strings.
     *  Package names and settings sections are validated more strictly downstream too. */
    fun isSafeFreeText(value: String, maxLength: Int = 500): Boolean {
        if (value.isBlank() || value.length > maxLength) return false
        // Reject control characters and shell-metacharacter-heavy payloads.
        val suspicious = Regex("[`$;&|<>\\n\\r]")
        return !suspicious.containsMatchIn(value)
    }
}
