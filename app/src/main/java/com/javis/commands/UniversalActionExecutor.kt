package com.javis.commands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import java.util.Locale

class UniversalActionExecutor(
    private val context: Context
) {

    private val contactResolver = ContactResolver(context)

    fun execute(action: UniversalAction): UniversalActionResult {
        return try {
            when (action) {
                is UniversalAction.OpenApp ->
                    openApp(action.appName)

                is UniversalAction.OpenContact ->
                    openContact(action.contactName)

                is UniversalAction.Call ->
                    prepareCall(action)

                is UniversalAction.Message ->
                    prepareMessage(action)

                is UniversalAction.Search ->
                    search(action)

                is UniversalAction.Navigate ->
                    navigate(action.destination)

                is UniversalAction.CreateAlarm ->
                    createAlarm(action.hour, action.minute)

                is UniversalAction.CreateTimer ->
                    createTimer(action.seconds)

                is UniversalAction.Share ->
                    prepareShare(action)

                is UniversalAction.ExecuteSequence ->
                    executeSequence(action.actions)

                is UniversalAction.Cancel ->
                    UniversalActionResult.Success(action.reason)

                is UniversalAction.Unknown ->
                    UniversalActionResult.Unsupported(
                        "I don't know how to perform that action yet."
                    )
            }
        } catch (e: Exception) {
            UniversalActionResult.Failure(
                e.message ?: "The action could not be completed."
            )
        }
    }

    private fun openApp(appName: String): UniversalActionResult {
        val packageName = resolvePackage(appName)
            ?: return UniversalActionResult.Failure(
                "I couldn't find $appName on this phone."
            )

        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return UniversalActionResult.Failure(
                    "$appName cannot be launched."
                )

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        return UniversalActionResult.Success(
            "Opened $appName."
        )
    }

    private fun openContact(
        contactName: String
    ): UniversalActionResult {
        val contact = contactResolver.resolve(contactName)
            ?: return UniversalActionResult.Failure(
                "I couldn't find $contactName in your contacts."
            )

        return try {
            val lookupUri = Uri.withAppendedPath(
                android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI,
                Uri.encode(contact.displayName)
            )

            val intent = Intent(
                Intent.ACTION_VIEW,
                lookupUri
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            UniversalActionResult.Success(
                "Opened ${contact.displayName}."
            )
        } catch (e: Exception) {
            UniversalActionResult.Failure(
                "I found ${contact.displayName}, but couldn't open the contact."
            )
        }
    }

    private fun prepareCall(
        action: UniversalAction.Call
    ): UniversalActionResult {
        val contact = contactResolver.resolve(action.contactName)
            ?: return UniversalActionResult.Failure(
                "I couldn't find ${action.contactName} in your contacts."
            )

        return UniversalActionResult.NeedsConfirmation(
            UniversalAction.Call(contact.displayName),
            "Call ${contact.displayName} at ${contact.phoneNumber}?"
        )
    }

    fun confirmCall(
        action: UniversalAction.Call
    ): UniversalActionResult {
        val contact = contactResolver.resolve(action.contactName)
            ?: return UniversalActionResult.Failure(
                "I couldn't find ${action.contactName} in your contacts."
            )

        return try {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(contact.phoneNumber)}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            UniversalActionResult.Success(
                "Opening the call to ${contact.displayName}."
            )
        } catch (e: Exception) {
            UniversalActionResult.Failure(
                "I couldn't start the call."
            )
        }
    }

    private fun prepareMessage(
        action: UniversalAction.Message
    ): UniversalActionResult {
        val contact = contactResolver.resolve(action.contactName)
            ?: return UniversalActionResult.Failure(
                "I couldn't find ${action.contactName} in your contacts."
            )

        val app = action.appName
            ?.lowercase(Locale.getDefault())

        if (app == "whatsapp") {
            return UniversalActionResult.NeedsConfirmation(
                action,
                "Send this WhatsApp message to ${contact.displayName}: \"${action.message}\"?"
            )
        }

        return UniversalActionResult.NeedsConfirmation(
            action,
            "Send this message to ${contact.displayName}: \"${action.message}\"?"
        )
    }

    fun confirmMessage(
        action: UniversalAction.Message
    ): UniversalActionResult {
        val contact = contactResolver.resolve(action.contactName)
            ?: return UniversalActionResult.Failure(
                "I couldn't find ${action.contactName} in your contacts."
            )

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse(
                    "smsto:${Uri.encode(contact.phoneNumber)}"
                )
                putExtra("sms_body", action.message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return UniversalActionResult.Failure(
                    "No SMS application is available."
                )
            }

            context.startActivity(intent)

            UniversalActionResult.Success(
                "Opening the message to ${contact.displayName}."
            )
        } catch (e: Exception) {
            UniversalActionResult.Failure(
                "I couldn't open the messaging application."
            )
        }
    }

    private fun search(
        action: UniversalAction.Search
    ): UniversalActionResult {
        val query = Uri.encode(action.query)

        if (!action.appName.isNullOrBlank()) {
            val packageName = resolvePackage(action.appName)

            if (packageName != null) {
                val launchIntent =
                    context.packageManager
                        .getLaunchIntentForPackage(packageName)

                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

                    context.startActivity(launchIntent)

                    return UniversalActionResult.Success(
                        "Opened ${action.appName}."
                    )
                }
            }
        }

        if (!isOnline()) {
            return UniversalActionResult.Failure(
                "I'm offline, so I can't search the web right now."
            )
        }

        val browser = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "https://www.google.com/search?q=$query"
            )
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(browser)

        return UniversalActionResult.Success(
            "Searching for ${action.query}."
        )
    }

    private fun navigate(
        destination: String
    ): UniversalActionResult {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "google.navigation:q=${Uri.encode(destination)}"
            )
        ).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)

            return UniversalActionResult.Success(
                "Opening navigation to $destination."
            )
        }

        return UniversalActionResult.Failure(
            "Google Maps isn't installed."
        )
    }

    private fun createAlarm(
        hour: Int,
        minute: Int
    ): UniversalActionResult {
        val intent = Intent(
            AlarmClock.ACTION_SET_ALARM
        ).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return if (
            intent.resolveActivity(context.packageManager) != null
        ) {
            context.startActivity(intent)

            UniversalActionResult.Success(
                "Opening the alarm for %02d:%02d."
                    .format(hour, minute)
            )
        } else {
            UniversalActionResult.Failure(
                "No alarm application is available."
            )
        }
    }

    private fun createTimer(
        seconds: Long
    ): UniversalActionResult {
        val intent = Intent(
            AlarmClock.ACTION_SET_TIMER
        ).apply {
            putExtra(
                AlarmClock.EXTRA_LENGTH,
                seconds.coerceAtLeast(1L).toInt()
            )
            putExtra(
                AlarmClock.EXTRA_SKIP_UI,
                false
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return if (
            intent.resolveActivity(context.packageManager) != null
        ) {
            context.startActivity(intent)

            UniversalActionResult.Success(
                "Opening a timer for $seconds seconds."
            )
        } else {
            UniversalActionResult.Failure(
                "No timer application is available."
            )
        }
    }

    private fun prepareShare(
        action: UniversalAction.Share
    ): UniversalActionResult {
        return UniversalActionResult.NeedsConfirmation(
            action,
            if (action.target.isNullOrBlank()) {
                "Ready to share: ${action.content}"
            } else {
                "Ready to share with ${action.target}: ${action.content}"
            }
        )
    }

    fun confirmShare(
        action: UniversalAction.Share
    ): UniversalActionResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, action.content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(
                intent,
                "Share with"
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)

            UniversalActionResult.Success(
                "Opening the share menu."
            )
        } catch (e: Exception) {
            UniversalActionResult.Failure(
                "I couldn't open the share menu."
            )
        }
    }

    private fun executeSequence(
        actions: List<UniversalAction>
    ): UniversalActionResult {
        if (actions.isEmpty()) {
            return UniversalActionResult.Failure(
                "The action sequence is empty."
            )
        }

        var completed = 0

        for (action in actions) {
            when (val result = execute(action)) {
                is UniversalActionResult.Success -> completed++

                is UniversalActionResult.NeedsConfirmation ->
                    return result

                is UniversalActionResult.Failure ->
                    return result

                is UniversalActionResult.Unsupported ->
                    return result
            }
        }

        return UniversalActionResult.Success(
            "Completed $completed actions."
        )
    }

    private fun resolvePackage(
        appName: String
    ): String? {
        val normalized = appName
            .lowercase(Locale.getDefault())
            .trim()

        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "telegram" to "org.telegram.messenger"
        )

        knownPackages[normalized]?.let { packageName ->
            if (isInstalled(packageName)) {
                return packageName
            }
        }

        return context.packageManager
            .getInstalledApplications(0)
            .firstOrNull {
                it.loadLabel(context.packageManager)
                    .toString()
                    .lowercase(Locale.getDefault())
                    .trim() == normalized
            }
            ?.packageName
    }

    private fun isInstalled(
        packageName: String
    ): Boolean {
        return try {
            context.packageManager.getApplicationInfo(
                packageName,
                0
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as android.net.ConnectivityManager

        val network = manager.activeNetwork ?: return false
        val capabilities =
            manager.getNetworkCapabilities(network)
                ?: return false

        return capabilities.hasCapability(
            android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }
}
