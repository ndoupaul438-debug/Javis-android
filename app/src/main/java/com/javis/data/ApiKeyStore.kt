package com.javis.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "javis_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(): String? = prefs.getString(KEY_ANTHROPIC_API_KEY, null)

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_ANTHROPIC_API_KEY, key.trim()).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_ANTHROPIC_API_KEY).apply()
    }

    fun getGeminiKey(): String? = prefs.getString(KEY_GEMINI_API_KEY, null)

    fun saveGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun clearGeminiKey() {
        prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    fun getGroqKey(): String? = prefs.getString(KEY_GROQ_API_KEY, null)

    fun saveGroqKey(key: String) {
        prefs.edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()
    }

    fun clearGroqKey() {
        prefs.edit().remove(KEY_GROQ_API_KEY).apply()
    }

    companion object {
        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
    }
}
