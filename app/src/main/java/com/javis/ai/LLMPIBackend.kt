package com.javis.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Real backend adapter. Talks to an LLMPI-compatible HTTPS endpoint.
 *
 * IMPORTANT: baseUrl and apiKey must come from BuildConfig, which is
 * generated from local.properties at build time. Never hard-code real
 * credentials here — see local.properties.example in the project root.
 *
 * The exact request/response shape below is a reasonable, documented
 * default (POST { message, conversationId, deviceContext } -> AIResponse
 * JSON). If your specific LLMPI provider uses a different contract,
 * adjust the request body / parsing in this file only — nothing else
 * in the app needs to change, since everything else talks to the
 * AIBackend interface.
 */
class LLMPIBackend(
    private val baseUrl: String,
    private val apiKey: String?
) : AIBackend {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    override suspend fun process(request: AIRequest): Result<AIResponse> {
        return try {
            if (baseUrl.isBlank() || baseUrl.contains("example.invalid")) {
                return Result.failure(IllegalStateException(
                    "LLMPI_BASE_URL is not configured. Set it in local.properties."
                ))
            }
            val response = client.post("$baseUrl/v1/assistant/process") {
                contentType(ContentType.Application.Json)
                if (!apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
                setBody(request)
            }
            Result.success(response.body<AIResponse>())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
