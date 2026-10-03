package com.janus.model

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/**
 * A local model served by Ollama (http://localhost:11434) — the $0-forever subject-under-test.
 * We deliberately use a small local model here: for regression detection we *want* a subject
 * that sometimes produces wrong or regressed output, so the detector has something to catch.
 *
 * Same [Model] interface as the cloud providers; only the transport differs.
 */
class OllamaModel(
    override val id: String,
    private val modelName: String = id,
    private val baseUrl: String = System.getenv("OLLAMA_HOST") ?: "http://localhost:11434",
    private val client: OkHttpClient = defaultClient(),
) : Model {
    private val mapper = ObjectMapper()
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to modelName,
                "prompt" to request.prompt,
                "stream" to false,
                "options" to mapOf(
                    "temperature" to request.params.temperature,
                    "num_predict" to request.params.maxTokens,
                ),
            ),
        )
        val httpReq = Request.Builder()
            .url("$baseUrl/api/generate")
            .post(body.toRequestBody(jsonMedia))
            .build()

        val started = System.nanoTime()
        client.newCall(httpReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ProviderException(resp.code, "Ollama ${resp.code}: ${text.take(200)}")
            }
            val root = mapper.readTree(text)
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            return ModelResponse(
                text = root.path("response").asText().orEmpty(),
                inputTokens = root.path("prompt_eval_count").asInt(0).takeIf { it > 0 },
                outputTokens = root.path("eval_count").asInt(0).takeIf { it > 0 },
                latencyMs = latencyMs,
            )
        }
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(120)) // local generation can be slow on CPU
            .build()
    }
}
