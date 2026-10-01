package com.javis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract

class WhatsAppContactTool(private val context: Context) : JavisTool {

    override val name = "open_whatsapp_contact"
    override val description = "Find a phone contact and open a WhatsApp chat."
    override val requiresConfirmation = false
    override val requiredPermissions =
        listOf(android.Manifest.permission.READ_CONTACTS)

    override suspend fun execute(
        arguments: Map<String, String>
    ): ToolResult {
        val requested = arguments["target"]?.trim().orEmpty()

        if (requested.isBlank()) {
            return ToolResult.Failure("No contact name was provided.")
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var bestName: String? = null
        var bestNumber: String? = null

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->

            val nameIndex =
                cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )

            val numberIndex =
                cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                val number = cursor.getString(numberIndex).orEmpty()

                if (name.equals(requested, ignoreCase = true)) {
                    bestName = name
                    bestNumber = number
                    break
                }

                if (bestName == null &&
                    name.contains(requested, ignoreCase = true)
                ) {
                    bestName = name
                    bestNumber = number
                }
            }
        }

        if (bestNumber.isNullOrBlank()) {
            return ToolResult.Failure(
                "I couldn't find a contact named $requested."
            )
        }

        val normalized = bestNumber!!.filter { it.isDigit() }

        if (normalized.isBlank()) {
            return ToolResult.Failure(
                "That contact doesn't have a usable phone number."
            )
        }

        val contactName = bestName ?: requested

        return try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/$normalized")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            ToolResult.Success(
                "Opening WhatsApp chat with $contactName."
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                "Couldn't open WhatsApp for $contactName."
            )
        }
    }
}
