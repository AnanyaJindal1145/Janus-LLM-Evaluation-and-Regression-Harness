package com.janus.compare

import kotlin.math.abs

/** A confidence interval on the mean score delta (new - old). */
data class Interval(val lo: Double, val hi: Double, val point: Double)

/**
 * Case-level paired bootstrap on the per-case score delta.
 *
 * Why bootstrap over a t-test: scores are bounded [0,1], often skewed or bimodal (mostly 1.0
 * with occasional 0.0), and N is small — the t-test's normality assumption doesn't hold, so
 * its p-values lie. The bootstrap makes no distributional assumption and yields an interpretable
 * interval instead of a bare p-value.
 *
 * Why PAIRED (same resample index applied to old and new): we measure the delta on the SAME
 * cases, so we resample case indices once per draw and apply to both runs. Resampling the two
 * independently would inflate variance and throw away the pairing.
 *
 * Why case-level: the caller passes one score per case (already collapsed from N samples), and
 * we resample *cases*. Resampling raw samples would treat non-independent draws as independent,
 * faking a larger N and producing falsely narrow intervals.
 *
 * Determinism: the resample index is derived from a seeded (iteration, position) hash rather than
 * a global RNG, so `compare` gives identical verdicts on re-run — matching Janus's reproducibility
 * goal and keeping the CI gate stable.
 */
object Bootstrap {

    fun deltaCI(
        oldScores: List<Double>,
        newScores: List<Double>,
        iterations: Int = 10_000,
        ciLevel: Double = 0.95,
        seed: Long = 42,
    ): Interval {
        require(oldScores.size == newScores.size) { "score lists must align by case" }
        require(oldScores.isNotEmpty()) { "need at least one case" }
        val n = oldScores.size

        val deltas = DoubleArray(iterations) { iter ->
            var sumOld = 0.0
            var sumNew = 0.0
            for (pos in 0 until n) {
                val idx = resampleIndex(seed, iter, pos, n)
                sumOld += oldScores[idx]
                sumNew += newScores[idx]   // SAME idx for both — paired resample
            }
            (sumNew - sumOld) / n          // mean delta for this resample
        }
        deltas.sort()

        val loIdx = ((1 - ciLevel) / 2 * iterations).toInt().coerceIn(0, iterations - 1)
        val hiIdx = ((1 + ciLevel) / 2 * iterations).toInt().coerceIn(0, iterations - 1)
        val point = (newScores.average() - oldScores.average())
        return Interval(lo = deltas[loIdx], hi = deltas[hiIdx], point = point)
    }

    /** Deterministic resample index from (seed, iteration, position). FNV-1a-ish mix → [0, n). */
    private fun resampleIndex(seed: Long, iter: Int, pos: Int, n: Int): Int {
        var h = seed xor -0x340d631b7bdddcdbL // 64-bit FNV offset basis
        h = (h xor iter.toLong()) * 0x100000001b3L
        h = (h xor pos.toLong()) * 0x100000001b3L
        h = h xor (h ushr 32)
        val m = (h and Long.MAX_VALUE) % n
        return m.toInt()
    }

    fun abs(d: Double): Double = kotlin.math.abs(d)
}
