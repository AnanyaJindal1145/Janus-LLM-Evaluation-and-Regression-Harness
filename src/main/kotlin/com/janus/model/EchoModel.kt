package com.janus.model

/**
 * A zero-cost, offline, deterministic Model for M1 — lets the whole pipeline run end to
 * end with no API keys and no spend, matching Janus's $0-dev principle. Real providers
 * (Anthropic/OpenAI/Ollama) implement the same interface at M3/M5 with no pipeline change.
 *
 * It simply echoes the last non-empty line of the prompt, which by convention is the
 * case input — enough to exercise loading, running, and (later) deterministic scoring.
 */
class EchoModel : Model {
    override val id: String = "echo"

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val echoed = request.prompt.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        return ModelResponse(
            text = echoed,
            inputTokens = request.prompt.length,
            outputTokens = echoed.length,
            latencyMs = 0,
        )
    }
}
