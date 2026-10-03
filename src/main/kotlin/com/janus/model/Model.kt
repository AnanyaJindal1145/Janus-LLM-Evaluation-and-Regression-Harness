package com.janus.model

/**
 * Parameters that define *how* a model is called. Part of a Target's identity:
 * changing temperature or maxTokens is a change worth detecting a regression on.
 */
data class ModelParams(
    val temperature: Double = 0.0,
    val maxTokens: Int = 1024,
    val extras: Map<String, String> = emptyMap(),
)

/** A single request to a model: the fully-rendered prompt plus the params. */
data class ModelRequest(
    val prompt: String,
    val params: ModelParams,
)

/** A single response. `raw` is kept for auditability (judge reasoning, debugging). */
data class ModelResponse(
    val text: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val latencyMs: Long? = null,
)

/**
 * The core abstraction of Janus. Every backend — Anthropic, OpenAI, Ollama, or the
 * offline EchoModel — implements this one method. Everything else (scorers, runner,
 * judge) depends on this interface, never on a concrete provider SDK.
 *
 * `suspend` from day one: the M5 runner fans out thousands of these concurrently under
 * a bounded semaphore. Making it suspend now means that milestone adds no refactor.
 */
interface Model {
    /** Stable identifier persisted with every run (e.g. "echo", "claude-opus-5"). */
    val id: String

    suspend fun complete(request: ModelRequest): ModelResponse
}
