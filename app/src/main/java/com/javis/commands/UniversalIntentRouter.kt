package com.javis.commands

import java.util.Locale
import java.util.regex.Pattern

/**
 * Fast local intent router.
 *
 * Handles common commands without sending them to an AI provider.
 * Complex or ambiguous requests can fall through to the normal AI pipeline.
 */
object UniversalIntentRouter {

    fun route(input: String): UniversalAction? {
        val text = input.trim()
        if (text.isBlank()) return null

        val normalized = text
            .lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), " ")
            .trim()

        if (isCancellation(normalized)) {
            return UniversalAction.Cancel()
        }

        parseTimer(normalized)?.let { return it }
        parseAlarm(normalized)?.let { return it }

        parseMessage(text)?.let { return it }
        parseCall(text)?.let { return it }
        parseNavigation(text)?.let { return it }
        parseOpenApp(text)?.let { return it }
        parseSearch(text)?.let { return it }

        return null
    }

    private fun isCancellation(text: String): Boolean {
        return text == "cancel" ||
            text == "cancel that" ||
            text == "stop" ||
            text == "stop that" ||
            text == "never mind" ||
            text == "nevermind"
    }

    private fun parseMessage(input: String): UniversalAction.Message? {
        val patterns = listOf(
            Pattern.compile(
                """^(?:send|message|text|whatsapp)\s+(?:a\s+message\s+)?(?:to\s+)?(.+?)\s+(?:saying|that says|and say|say)\s+(.+)$""",
                Pattern.CASE_INSENSITIVE
            ),
            Pattern.compile(
                """^(?:send|message|text|whatsapp)\s+(?:a\s+message\s+)?(?:to\s+)?(.+?)\s*:\s*(.+)$""",
                Pattern.CASE_INSENSITIVE
            )
        )

        for (pattern in patterns) {
            val match = pattern.matcher(input.trim())
            if (match.matches()) {
                val contact = match.group(1)?.trim().orEmpty()
                val message = match.group(2)?.trim().orEmpty()

                if (contact.isNotBlank() && message.isNotBlank()) {
                    val app = if (input.lowercase(Locale.getDefault()).contains("whatsapp")) {
                        "WhatsApp"
                    } else {
                        null
                    }

                    return UniversalAction.Message(
                        appName = app,
                        contactName = contact,
                        message = message
                    )
                }
            }
        }

        return null
    }

    private fun parseCall(input: String): UniversalAction.Call? {
        val match = Regex(
            """^(?:call|phone|ring)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(input.trim()) ?: return null

        val contact = match.groupValues[1].trim()
        return if (contact.isBlank()) null else UniversalAction.Call(contact)
    }

    private fun parseNavigation(input: String): UniversalAction.Navigate? {
        val match = Regex(
            """^(?:take me to|navigate to|directions to|go to)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(input.trim()) ?: return null

        val destination = match.groupValues[1].trim()
        return if (destination.isBlank()) null else UniversalAction.Navigate(destination)
    }

    private fun parseOpenApp(input: String): UniversalAction.OpenApp? {
        val match = Regex(
            """^(?:open|launch|start|go into)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(input.trim()) ?: return null

        val app = match.groupValues[1]
            .removeSuffix(" app")
            .trim()

        return if (app.isBlank()) null else UniversalAction.OpenApp(app)
    }

    private fun parseSearch(input: String): UniversalAction.Search? {
        val match = Regex(
            """^(?:search|look up|find)\s+(?:on\s+(.+?)\s+for\s+|for\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(input.trim()) ?: return null

        val app = match.groupValues[1]?.trim()?.takeIf { it.isNotBlank() }
        val query = match.groupValues[2].trim()

        if (query.isBlank()) return null

        return UniversalAction.Search(
            appName = app,
            query = query
        )
    }

    private fun parseTimer(text: String): UniversalAction.CreateTimer? {
        val match = Regex(
            """^(?:set\s+)?(?:a\s+)?timer\s+(?:for\s+)?(\d+(?:\.\d+)?)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null

        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase(Locale.getDefault())

        val seconds = when {
            unit.startsWith("second") || unit.startsWith("sec") -> value
            unit.startsWith("minute") || unit.startsWith("min") -> value * 60
            unit.startsWith("hour") || unit.startsWith("hr") -> value * 3600
            else -> return null
        }

        return UniversalAction.CreateTimer(seconds.toLong())
    }

    private fun parseAlarm(text: String): UniversalAction.CreateAlarm? {
        val match = Regex(
            """^(?:set\s+)?(?:an\s+)?alarm\s+(?:for\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null

        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].lowercase(Locale.getDefault())

        if (minute !in 0..59) return null

        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0

        if (hour !in 0..23) return null

        return UniversalAction.CreateAlarm(hour, minute)
    }
}
