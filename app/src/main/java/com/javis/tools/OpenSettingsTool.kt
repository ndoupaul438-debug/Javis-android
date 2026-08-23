package com.javis.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens well-known system settings screens using standard, documented
 * Settings intents — never anything undocumented or restriction-bypassing.
 */
class OpenSettingsTool(private val context: Context) : JavisTool {
    override val name = "open_settings"
    override val description = "Opens a specific Android settings screen (wifi, bluetooth, etc)."
    override val requiresConfirmation = false
    override val requiredPermissions: List<String> = emptyList()

    private val supportedSections = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "general" to Settings.ACTION_SETTINGS
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val section = arguments["target"]?.trim()?.lowercase().orEmpty()
        val action = supportedSections[section] ?: Settings.ACTION_SETTINGS

        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(intent)
            ToolResult.Success(
                if (supportedSections.containsKey(section)) "Opening $section settings."
                else "Opening settings."
            )
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't open that settings screen: ${e.message}")
        }
    }
}
