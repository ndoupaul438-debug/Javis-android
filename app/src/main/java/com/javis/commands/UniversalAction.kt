package com.javis.commands

/**
 * Universal actions understood by Javis.
 *
 * These describe WHAT the user wants, while the execution layer decides
 * HOW Android or a target application can perform it.
 */
sealed class UniversalAction {

    data class OpenApp(
        val appName: String
    ) : UniversalAction()

    data class Message(
        val appName: String?,
        val contactName: String,
        val message: String
    ) : UniversalAction()

    data class Call(
        val contactName: String
    ) : UniversalAction()

    data class Search(
        val appName: String?,
        val query: String
    ) : UniversalAction()

    data class OpenContact(
        val contactName: String
    ) : UniversalAction()

    data class Navigate(
        val destination: String
    ) : UniversalAction()

    data class CreateAlarm(
        val hour: Int,
        val minute: Int
    ) : UniversalAction()

    data class CreateTimer(
        val seconds: Long
    ) : UniversalAction()

    data class Share(
        val target: String?,
        val content: String
    ) : UniversalAction()

    data class ExecuteSequence(
        val actions: List<UniversalAction>
    ) : UniversalAction()

    data class Cancel(
        val reason: String = "User cancelled the current action."
    ) : UniversalAction()

    data class Unknown(
        val request: String
    ) : UniversalAction()
}

/**
 * Describes a capability exposed by an installed application.
 *
 * The registry is deliberately data-driven so new app integrations can be
 * added without changing the core assistant architecture.
 */
data class AppCapability(
    val packageName: String,
    val displayName: String,
    val canOpen: Boolean = true,
    val canSearch: Boolean = false,
    val canMessage: Boolean = false,
    val canShare: Boolean = false,
    val canCall: Boolean = false
)

/**
 * Result returned by the universal action layer.
 */
sealed class UniversalActionResult {

    data class Success(
        val message: String
    ) : UniversalActionResult()

    data class NeedsConfirmation(
        val action: UniversalAction,
        val message: String
    ) : UniversalActionResult()

    data class Failure(
        val message: String
    ) : UniversalActionResult()

    data class Unsupported(
        val message: String
    ) : UniversalActionResult()
}
