package com.javis.ai

import kotlinx.serialization.Serializable

/**
 * Minimal, non-sensitive context about the device, sent to the AI backend
 * only when it helps the assistant reason about a request.
 */
@Serializable
data class DeviceContext(
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val networkAvailable: Boolean? = null,
    val currentTimeIso: String? = null,
    val installedSupportedApps: List<String> = emptyList()
)

@Serializable
data class AIRequest(
    val message: String,
    val conversationId: String? = null,
    val deviceContext: DeviceContext? = null
)

/**
 * type is one of: "response" (plain conversational reply)
 *              or "action"   (structured command JAVIS should execute)
 */
@Serializable
data class AIResponse(
    val type: String,
    val message: String? = null,
    val action: String? = null,
    val target: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false
)
