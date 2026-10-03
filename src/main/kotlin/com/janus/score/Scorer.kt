package com.janus.score

import com.janus.model.ModelResponse
import com.janus.suite.Case

/**
 * The metric a score belongs to. Deterministic metrics in M2; JUDGE arrives in M3.
 * A run may attach several scores to one output (e.g. EXACT_MATCH + JSON_VALID).
 */
enum class Metric {
    EXACT_MATCH,
    REGEX_MATCH,
    CONTAINS,
    JSON_VALID,
    NUMERIC_TOLERANCE,
    JUDGE,
}

/**
 * A single scored result: a numeric value in [0.0, 1.0] plus an optional human-readable
 * detail (why it scored that way). Keeping score continuous even for pass/fail checks
 * (0.0 / 1.0) lets the M5 statistics treat every metric uniformly.
 */
data class Score(
    val metric: Metric,
    val value: Double,
    val detail: String? = null,
) {
    init {
        require(value in 0.0..1.0) { "Score value must be in [0,1], was $value" }
    }
}

/**
 * Scores one model output for one case. `suspend` even though deterministic scorers are
 * synchronous: the M3 LLM-as-judge scorer makes a network call and must sit behind this
 * SAME interface. That uniformity — deterministic and judge as one type — is the point.
 */
interface Scorer {
    val metric: Metric
    suspend fun score(case: Case, response: ModelResponse): Score
}
