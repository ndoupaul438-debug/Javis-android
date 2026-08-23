package com.javis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

class SearchWebTool(private val context: Context) : JavisTool {
    override val name = "search_web"
    override val description = "Opens a web search for the given query in the default browser."
    override val requiresConfirmation = false
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments["target"]?.trim().orEmpty()
        if (query.isBlank()) return ToolResult.Failure("No search query was provided.")

        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult.Success("Searching the web for \"$query\".")
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't open a browser to search: ${e.message}")
        }
    }
}
