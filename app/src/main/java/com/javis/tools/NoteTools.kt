package com.javis.tools

import com.javis.data.NotesStore

class CreateNoteTool(private val notesStore: NotesStore) : JavisTool {
    override val name = "create_note"
    override val description = "Saves a short text note locally on the device."
    override val requiresConfirmation = false
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val text = arguments["target"]?.trim().orEmpty()
        if (text.isBlank()) return ToolResult.Failure("There's no note text to save.")
        notesStore.saveNote(text)
        return ToolResult.Success("Saved your note.")
    }
}

class ReadNoteTool(private val notesStore: NotesStore) : JavisTool {
    override val name = "read_note"
    override val description = "Reads back the most recently saved note."
    override val requiresConfirmation = false
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val note = notesStore.readNote()
        return if (note.isNullOrBlank()) {
            ToolResult.Failure("You don't have a saved note yet.")
        } else {
            ToolResult.Success(note)
        }
    }
}
