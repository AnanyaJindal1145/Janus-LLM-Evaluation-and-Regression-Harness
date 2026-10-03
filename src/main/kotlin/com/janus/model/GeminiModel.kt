package com.janus.model

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/**
 * Google Gemini via AI Studio — the $0 judge in Janus's provider stack (genuine free tier,
 * no card). Implements the same [Model] interface as everything else; the judge scorer neither
 * knows nor cares that this is Gemini rather than the offline FakeJudgeModel.
 *
 * The API key is read from the GEMINI_API_KEY env var — never hardcoded, never logged.
 * Retryable failures (429, 5xx) surface as [ProviderException] so the M5 runner's backoff
 * wrapper can decide whether to retry.
 */
class GeminiModel(
    override val id: String = "gemini-flash",
    private val modelName: String = "gemini-2.0-flash",
    private val apiKey: String = System.getenv("GEMINI_API_KEY")
        ?: error("GEMINI_API_KEY is not set"),
    private val client: OkHttpClient = defaultClient(),
) : Model {
    private val mapper = ObjectMapper()
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
        val body = mapper.writeValueAsString(
            mapOf(
                "contents" to listOf(
                    mapOf("parts" to listOf(mapOf("text" to request.prompt))),
                ),
                "generationConfig" to mapOf(
                    "temperature" to request.params.temperature,
                    "maxOutputTokens" to request.params.maxTokens,
                ),
            ),
        )
        val httpReq = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody(jsonMedia))
            .build()

        val started = System.nanoTime()
        client.newCall(httpReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ProviderException(resp.code, "Gemini ${resp.code}: ${text.take(200)}")
            }
            val root = mapper.readTree(text)
            val out = root.path("candidates").firstOrNull()
                ?.path("content")?.path("parts")?.firstOrNull()
                ?.path("text")?.asText().orEmpty()
            val usage = root.path("usageMetadata")
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            return ModelResponse(
                text = out,
                inputTokens = usage.path("promptTokenCount").asInt(0).takeIf { it > 0 },
                outputTokens = usage.path("candidatesTokenCount").asInt(0).takeIf { it > 0 },
                latencyMs = latencyMs,
            )
        }
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(60))
            .build()
    }
}
