package com.javis.tools

import android.content.Context
import com.javis.commands.JavisCommand
import com.javis.data.NotesStore

class ToolRegistry(context: Context) {

    private val notesStore = NotesStore(context)

    private val tools: Map<String, JavisTool> = listOf(
        OpenAppTool(context),
        SearchWebTool(context),
        GetTimeTool(),
        CalculatorTool(),
        CreateNoteTool(notesStore),
        ReadNoteTool(notesStore),
        ShowNotificationTool(context),
        OpenSettingsTool(context),
    ).associateBy { it.name }

    fun toolFor(name: String): JavisTool? = tools[name]

    fun allTools(): Collection<JavisTool> = tools.values

    /**
     * Executes a validated JavisCommand by dispatching it to the matching tool.
     * PlainResponse / Unsupported / Speak don't hit a device tool — they're
     * handled directly by the assistant engine / UI layer instead.
     */
    suspend fun execute(command: JavisCommand): ToolResult {
        val (toolName, args) = when (command) {
            is JavisCommand.OpenApp -> "open_app" to mapOf("target" to command.appNameOrPackage)
            is JavisCommand.SearchWeb -> "search_web" to mapOf("target" to command.query)
            JavisCommand.GetTime -> "get_time" to emptyMap()
            is JavisCommand.Calculate -> "calculator" to mapOf("target" to command.expression)
            is JavisCommand.CreateNote -> "create_note" to mapOf("target" to command.text)
            JavisCommand.ReadNote -> "read_note" to emptyMap()
            is JavisCommand.ShowNotification -> "show_notification" to mapOf(
                "title" to command.title, "message" to command.message
            )
            is JavisCommand.OpenSettings -> "open_settings" to mapOf("target" to command.section)
            else -> return ToolResult.Failure("This command type isn't handled by a device tool.")
        }

        val tool = toolFor(toolName)
            ?: return ToolResult.Failure("No tool registered for \"$toolName\".")
        return tool.execute(args)
    }
}
