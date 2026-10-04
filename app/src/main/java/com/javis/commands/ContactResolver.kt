package com.javis.commands

import android.content.Context
import android.provider.ContactsContract
import java.util.Locale

data class ResolvedContact(
    val displayName: String,
    val phoneNumber: String
)

class ContactResolver(
    private val context: Context
) {

    fun resolve(name: String): ResolvedContact? {
        val target = name.trim().lowercase(Locale.getDefault())
        if (target.isBlank()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"

        val selectionArgs = arrayOf("%$target%")

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->

            val nameIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )

            val numberIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            while (cursor.moveToNext()) {
                if (nameIndex < 0 || numberIndex < 0) continue

                val displayName =
                    cursor.getString(nameIndex)?.trim().orEmpty()

                val phoneNumber =
                    cursor.getString(numberIndex)?.trim().orEmpty()

                if (displayName.isNotBlank() &&
                    phoneNumber.isNotBlank()
                ) {
                    return ResolvedContact(
                        displayName = displayName,
                        phoneNumber = phoneNumber
                    )
                }
            }
        }

        return null
    }
}
