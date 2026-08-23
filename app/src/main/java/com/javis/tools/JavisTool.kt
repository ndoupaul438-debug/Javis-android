package com.javis.tools

sealed class ToolResult {
    data class Success(val message: String) : ToolResult()
    data class Failure(val reason: String) : ToolResult()
    data class NeedsConfirmation(val message: String) : ToolResult()
}

interface JavisTool {
    val name: String
    val description: String
    val requiresConfirmation: Boolean
    /** Android permissions this tool needs, if any (checked before execute()). */
    val requiredPermissions: List<String>

    suspend fun execute(arguments: Map<String, String>): ToolResult
}
