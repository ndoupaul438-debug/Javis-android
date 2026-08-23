package com.javis.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GeminiBackend(private val apiKey: String) : AIBackend {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    private val contents = mutableListOf<JsonObject>()

    override suspend fun process(request: AIRequest): Result<AIResponse> {
        if (apiKey.isBlank()) {
            return Result.failure(
                IllegalStateException("No Gemini API key is set. Add one in JAVIS settings.")
            )
        }

        contents.add(buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray { add(buildJsonObject { put("text", request.message) }) })
        })

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "gemini-2.5-flash:generateContent?key=$apiKey"

        return try {
            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("system_instruction", buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", SYSTEM_PROMPT) }) })
                    })
                    put("tools", buildJsonArray { add(buildJsonObject { put("function_declarations", TOOLS) }) })
                    put("contents", JsonArray(contents.toList()))
                }.toString())
            }

            if (!response.status.isSuccess()) {
                val bodyText = response.bodyAsText()
                contents.removeLastOrNull()
                return if (response.status.value == 400 || response.status.value == 403) {
                    Result.failure(IllegalStateException("That Gemini API key was rejected — check it in settings."))
                } else {
                    Result.failure(IllegalStateException("Gemini API error: $bodyText"))
                }
            }

            val bodyJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: JsonArray(emptyList())

            var textReply = ""
            var toolResult: AIResponse? = null
            var modelContentForHistory: JsonObject? = null

            for (part in parts) {
                val partObj = part.jsonObject
                partObj["text"]?.jsonPrimitive?.contentOrNull?.let { textReply += it }
                partObj["functionCall"]?.jsonObject?.let { fnCall ->
                    val fnName = fnCall["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                    val fnArgs = fnCall["args"]?.jsonObject ?: JsonObject(emptyMap())
                    toolResult = mapToolCallToAction(fnName, fnArgs)
                }
            }

            modelContentForHistory = candidate?.get("content")?.jsonObject
            if (modelContentForHistory != null) {
                contents.add(modelContentForHistory)
            }

            Result.success(
                toolResult ?: AIResponse(
                    type = "response",
                    message = textReply.ifBlank { "I'm not sure how to respond to that." }
                )
            )
        } catch (e: Exception) {
            contents.removeLastOrNull()
            Result.failure(e)
        }
    }

    private fun mapToolCallToAction(toolName: String, args: JsonObject): AIResponse {
        fun arg(name: String): String = args[name]?.jsonPrimitive?.contentOrNull ?: ""
        return when (toolName) {
            "open_app" -> AIResponse(type = "action", action = "open_app", target = arg("app_name"))
            "search_web" -> AIResponse(type = "action", action = "search_web", target = arg("query"))
            "show_notification" -> AIResponse(
                type = "action", action = "show_notification",
                message = arg("message"), requiresConfirmation = true
            )
            "get_time" -> AIResponse(type = "action", action = "get_time")
            "calculator" -> AIResponse(type = "action", action = "calculator", target = arg("expression"))
            "create_note" -> AIResponse(type = "action", action = "create_note", target = arg("text"))
            "read_note" -> AIResponse(type = "action", action = "read_note")
            "open_settings" -> AIResponse(type = "action", action = "open_settings", target = arg("section"))
            else -> AIResponse(
                type = "response",
                message = "I tried to do something I'm not allowed to do on this device."
            )
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are JAVIS, a helpful voice assistant running natively on the person's " +
                "Android phone. Keep spoken replies short and conversational, 1 to 3 " +
                "sentences unless asked for more detail. When the person asks you to do " +
                "something the device can actually do (open an app, search the web, check " +
                "the time, do quick math, save or read a note, open a settings screen, or " +
                "show a notification), use the matching function instead of just describing it."

        private fun stringParamFn(name: String, description: String, argName: String, argDescription: String) =
            buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put(argName, buildJsonObject {
                            put("type", "STRING")
                            put("description", argDescription)
                        })
                    })
                    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(argName)) })
                })
            }

        private fun noArgFn(name: String, description: String) = buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {})
            })
        }

        private val TOOLS = buildJsonArray {
            add(stringParamFn("open_app", "Open an installed app by name.", "app_name", "The app's name, e.g. 'Spotify' or 'Camera'."))
            add(stringParamFn("search_web", "Search the web for a query.", "query", "What to search for."))
            add(stringParamFn(
                "show_notification",
                "Show an on-screen notification. Always requires the person's confirmation before it appears.",
                "message", "The notification text."
            ))
            add(noArgFn("get_time", "Get the current device time."))
            add(stringParamFn("calculator", "Evaluate a simple math expression.", "expression", "e.g. '12 * 8' or '45 + 30'."))
            add(stringParamFn("create_note", "Save a note on the device, replacing any previous note.", "text", "The note's content."))
            add(noArgFn("read_note", "Read back the currently saved note."))
            add(stringParamFn("open_settings", "Open a specific Android settings screen.", "section", "e.g. 'wifi', 'bluetooth', 'display'."))
        }
    }
}
