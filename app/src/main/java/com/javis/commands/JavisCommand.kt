package com.javis.commands

/**
 * Every action JAVIS can execute on the device. This is a closed set —
 * the AI backend can only ever trigger one of these, never arbitrary
 * code or shell commands. Adding a new capability means adding a new
 * case here AND a new tool in the tools/ package; nothing "generic"
 * is ever executed based on raw AI output.
 */
sealed class JavisCommand {

    data class OpenApp(val appNameOrPackage: String) : JavisCommand()

    data class Speak(val text: String) : JavisCommand()

    data class SearchWeb(val query: String) : JavisCommand()

    data class ShowNotification(val title: String, val message: String) : JavisCommand()

    object GetTime : JavisCommand()

    data class Calculate(val expression: String) : JavisCommand()

    data class CreateNote(val text: String) : JavisCommand()

    object ReadNote : JavisCommand()

    data class OpenSettings(val section: String) : JavisCommand()

    data class PlainResponse(val message: String) : JavisCommand()

    data class Unsupported(val reason: String) : JavisCommand()
}
