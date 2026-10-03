package com.janus.compare

import com.janus.run.Run
import com.janus.score.Metric

enum class Verdict { REGRESSION, IMPROVEMENT, NO_CHANGE }

data class MetricComparison(
    val metric: Metric,
    val n: Int,
    val oldMean: Double,
    val newMean: Double,
    val ci: Interval,
    val noiseFloor: Double,
    val verdict: Verdict,
    /** Case ids where the new run scored worse than the old — the "which cases" answer. */
    val regressedCases: List<String>,
)

/**
 * Compares two runs per metric and emits the two-bar verdict.
 *
 * The two bars: a change is flagged a REGRESSION only when it is BOTH
 *   (a) statistically real — the bootstrap CI on (new - old) lies entirely below zero, AND
 *   (b) practically real — the magnitude of the delta exceeds the pipeline's own measured
 *       noise floor (see NoiseFloor).
 * Clearing only one bar is NO_CHANGE: a CI that excludes zero but is smaller than the noise
 * floor is a real-but-trivial difference; a large point delta whose CI straddles zero is noise.
 */
class Comparator(
    private val iterations: Int = 10_000,
    private val ciLevel: Double = 0.95,
) {
    fun compare(
        oldRun: Run,
        newRun: Run,
        noiseFloors: Map<Metric, Double> = emptyMap(),
    ): List<MetricComparison> {
        val metrics = (oldRun.metrics().toSet() intersect newRun.metrics().toSet()).toList()
        val oldByCase = oldRun.results.associateBy { it.caseId }
        val newByCase = newRun.results.associateBy { it.caseId }
        val commonCases = oldByCase.keys.intersect(newByCase.keys).sorted()

        return metrics.mapNotNull { metric ->
            val pairs = commonCases.mapNotNull { id ->
                val o = oldByCase.getValue(id).perCaseScore(metric)
                val nw = newByCase.getValue(id).perCaseScore(metric)
                if (o == null || nw == null) null else Triple(id, o, nw)
            }
            if (pairs.isEmpty()) return@mapNotNull null

            val oldScores = pairs.map { it.second }
            val newScores = pairs.map { it.third }
            val ci = Bootstrap.deltaCI(oldScores, newScores, iterations, ciLevel)
            val floor = noiseFloors[metric] ?: 0.0

            val verdict = when {
                ci.hi < 0 && kotlin.math.abs(ci.point) > floor -> Verdict.REGRESSION
                ci.lo > 0 && kotlin.math.abs(ci.point) > floor -> Verdict.IMPROVEMENT
                else -> Verdict.NO_CHANGE
            }
            val regressed = pairs.filter { it.third < it.second }.map { it.first }

            MetricComparison(
                metric = metric,
                n = pairs.size,
                oldMean = oldScores.average(),
                newMean = newScores.average(),
                ci = ci,
                noiseFloor = floor,
                verdict = verdict,
                regressedCases = regressed,
            )
        }
    }
}
