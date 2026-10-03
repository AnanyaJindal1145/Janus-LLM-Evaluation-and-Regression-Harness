package com.janus.compare

import com.janus.run.Run
import com.janus.score.Metric

/**
 * The noise floor is measured, not guessed (M5 §8.1). You run the identical target against
 * itself ~20 times; each adjacent pair is a no-op comparison whose "delta" is pure pipeline
 * noise (sampling nondeterminism). The 95th percentile of the absolute point deltas across
 * those no-op comparisons is the floor: a regression must exceed the pipeline's own observed
 * noise to count as practically real.
 *
 * "I measured my own pipeline's noise and required regressions to exceed it" — this is what
 * makes the two-bar rule defensible rather than an arbitrary threshold.
 */
object NoiseFloor {

    /**
     * @param controlRuns runs of the SAME target against itself (≈20 recommended)
     * @param percentile  which percentile of |delta| to take as the floor (0.95 = 95th)
     */
    fun measure(
        controlRuns: List<Run>,
        percentile: Double = 0.95,
        iterations: Int = 2_000,
    ): Map<Metric, Double> {
        require(controlRuns.size >= 2) { "need at least 2 control runs to form no-op pairs" }
        val metrics = controlRuns.flatMap { it.metrics() }.distinct()

        return metrics.associateWith { metric ->
            // Every unordered adjacent pair of control runs is one no-op comparison.
            val absDeltas = controlRuns.zipWithNext().mapNotNull { (a, b) ->
                val aByCase = a.results.associateBy { it.caseId }
                val bByCase = b.results.associateBy { it.caseId }
                val common = aByCase.keys.intersect(bByCase.keys).sorted()
                val oldS = common.mapNotNull { aByCase.getValue(it).perCaseScore(metric) }
                val newS = common.mapNotNull { bByCase.getValue(it).perCaseScore(metric) }
                if (oldS.size != newS.size || oldS.isEmpty()) null
                else kotlin.math.abs(newS.average() - oldS.average())
            }.sorted()

            if (absDeltas.isEmpty()) 0.0
            else {
                val idx = (percentile * (absDeltas.size - 1)).toInt().coerceIn(0, absDeltas.size - 1)
                absDeltas[idx]
            }
        }
    }
}
