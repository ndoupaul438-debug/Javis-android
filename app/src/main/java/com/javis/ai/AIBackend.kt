package com.javis.ai

/**
 * Abstraction over any AI backend. JAVIS is not hard-coded to a single
 * provider — swap implementations without touching the rest of the app.
 */
interface AIBackend {
    suspend fun process(request: AIRequest): Result<AIResponse>
}
