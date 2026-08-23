package com.javis.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "javis_notes")
private val NOTE_KEY = stringPreferencesKey("last_note")

/**
 * Deliberately simple single-note store for the "create_note" / "read_note"
 * example tools. Swap for a full notes list + Room database if needed —
 * the JavisTool interface doesn't change either way.
 */
class NotesStore(private val context: Context) {

    suspend fun saveNote(text: String) {
        context.dataStore.edit { prefs -> prefs[NOTE_KEY] = text }
    }

    suspend fun readNote(): String? {
        val prefs = context.dataStore.data.first()
        return prefs[NOTE_KEY]
    }
}
