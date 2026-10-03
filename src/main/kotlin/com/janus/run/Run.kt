package com.janus.run

import com.janus.score.Metric
import com.janus.score.Score

/**
 * One sample = one draw from the (stochastic) subject model for a case, with the scores
 * assigned to that draw. M5 takes N of these per case because a single sample from a
 * stochastic process isn't a measurement — you need the distribution.
 */
data class Sample(
    val output: String,
    val scores: List<Score>,
    val latencyMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

/**
 * All N samples for one case within a run. `perCaseScore` collapses the N samples to a
 * single number per metric (the mean) — this is the M5 case-level step: samples within a
 * case are NOT independent, so you reduce to one score per case before the bootstrap
 * resamples *cases*. Resampling raw samples would fake a larger N and give falsely narrow CIs.
 */
data class CaseResult(
    val caseId: String,
    val samples: List<Sample>,
) {
    fun perCaseScore(metric: Metric): Double? {
        val vals = samples.mapNotNull { s -> s.scores.firstOrNull { it.metric == metric }?.value }
        return if (vals.isEmpty()) null else vals.average()
    }

    /** Mean latency across this case's samples — feeds the M6 cost/latency axes. */
    fun meanLatencyMs(): Double? {
        val vals = samples.mapNotNull { it.latencyMs }
        return if (vals.isEmpty()) null else vals.map { it.toDouble() }.average()
    }

    fun totalTokens(): Int =
        samples.sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
}

/**
 * A single execution of a Suite against a Target, with its results. The identity fields
 * (suite+version, target+promptVersion+model) are what `eval compare` keys on to decide
 * which two runs are comparable. `createdAtEpochMs` is passed in by the caller — the harness
 * never reads the clock in library code, keeping runs reproducible/testable.
 */
data class Run(
    val runId: String,
    val suiteName: String,
    val suiteVersion: String,
    val targetName: String,
    val promptVersion: String,
    val modelId: String,
    val samplesPerCase: Int,
    val createdAtEpochMs: Long,
    val results: List<CaseResult>,
) {
    /** Which metrics were scored in this run (union across cases). */
    fun metrics(): List<Metric> =
        results.flatMap { it.samples }.flatMap { it.scores }.map { it.metric }.distinct()
}
