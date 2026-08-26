package com.javis.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
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

class GroqBackend(private val apiKey: String) : AIBackend {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    private val messages = mutableListOf<JsonObject>().apply {
        add(buildJsonObject {
            put("role", "system")
            put("content", SYSTEM_PROMPT)
        })
    }

    override suspend fun process(request: AIRequest): Result<AIResponse> {
        if (apiKey.isBlank()) {
            return Result.failure(
                IllegalStateException("No Groq API key is set. Add one in JAVIS settings.")
            )
        }

        messages.add(buildJsonObject {
            put("role", "user")
            put("content", request.message)
        })

        return try {
            val response: HttpResponse = client.post("https://api.groq.com/openai/v1/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("model", "openai/gpt-oss-20b")
                    put("messages", JsonArray(messages.toList()))
                    put("tools", TOOLS)
                    put("tool_choice", "auto")
                }.toString())
            }

            if (!response.status.isSuccess()) {
                val bodyText = response.bodyAsText()
                messages.removeLastOrNull()
                return if (response.status.value == 401) {
                    Result.failure(IllegalStateException("That Groq API key was rejected — check it in settings."))
                } else {
                    Result.failure(IllegalStateException("Groq API error: $bodyText"))
                }
            }

            val bodyJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val choice = bodyJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val messageObj = choice?.get("message")?.jsonObject

            val textReply = messageObj?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val toolCalls = messageObj?.get("tool_calls")?.jsonArray

            var toolResult: AIResponse? = null
            if (toolCalls != null && toolCalls.isNotEmpty()) {
                val firstCall = toolCalls[0].jsonObject
                val fnObj = firstCall["function"]?.jsonObject
                val fnName = fnObj?.get("name")?.jsonPrimitive?.contentOrNull
                val fnArgsRaw = fnObj?.get("arguments")?.jsonPrimitive?.contentOrNull
                if (fnName != null) {
                    val fnArgs = try {
                        if (fnArgsRaw != null) json.parseToJsonElement(fnArgsRaw).jsonObject
                        else JsonObject(emptyMap())
                    } catch (e: Exception) { JsonObject(emptyMap()) }
                    toolResult = mapToolCallToAction(fnName, fnArgs)
                }
            }

            if (messageObj != null) {
                messages.add(messageObj)
            }

            Result.success(
                toolResult ?: AIResponse(
                    type = "response",
                    message = textReply.ifBlank { "I'm not sure how to respond to that." }
                )
            )
        } catch (e: Exception) {
            messages.removeLastOrNull()
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
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put(argName, buildJsonObject {
                                put("type", "string")
                                put("description", argDescription)
                            })
                        })
                        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(argName)) })
                    })
                })
            }

        private fun noArgFn(name: String, description: String) = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                })
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
