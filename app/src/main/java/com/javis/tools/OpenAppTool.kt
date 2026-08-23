package com.javis.tools

import android.content.Context
import android.content.Intent

/**
 * Launches an installed app by matching a spoken/typed name against the
 * label of currently installed launchable apps. Never touches app
 * internals, never bypasses any Android restriction — this is exactly
 * what tapping the app's icon does.
 */
class OpenAppTool(private val context: Context) : JavisTool {
    override val name = "open_app"
    override val description = "Launches an installed application by name."
    override val requiresConfirmation = false
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val requested = arguments["target"]?.trim().orEmpty()
        if (requested.isBlank()) {
            return ToolResult.Failure("No app name was provided.")
        }

        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolvedApps = pm.queryIntentActivities(launchIntent, 0)

        val match = resolvedApps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.equals(requested, ignoreCase = true) || label.contains(requested, ignoreCase = true)
        }

        if (match == null) {
            return ToolResult.Failure("\"$requested\" isn't installed on this device.")
        }

        val packageName = match.activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return ToolResult.Failure("Found \"$requested\" but couldn't get a launch intent for it.")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult.Success("Opening ${match.loadLabel(pm)}.")
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't open \"$requested\": ${e.message}")
        }
    }
}
