package com.javis.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class ShowNotificationTool(private val context: Context) : JavisTool {
    override val name = "show_notification"
    override val description = "Shows a local notification to the user."
    override val requiresConfirmation = true // always-confirm, per CommandValidator.ALWAYS_CONFIRM
    override val requiredPermissions: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    companion object {
        const val CHANNEL_ID = "javis_reminders"
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "JAVIS Reminders", NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return ToolResult.Failure("Notification permission hasn't been granted.")
            }
        }

        val title = arguments["title"]?.ifBlank { "JAVIS" } ?: "JAVIS"
        val message = arguments["message"]?.trim().orEmpty()
        if (message.isBlank()) return ToolResult.Failure("No notification message was provided.")

        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
            ToolResult.Success("Notification shown.")
        } catch (e: SecurityException) {
            ToolResult.Failure("Notification permission was denied.")
        }
    }
}
