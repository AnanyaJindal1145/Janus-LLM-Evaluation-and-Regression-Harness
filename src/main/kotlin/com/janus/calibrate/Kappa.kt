package com.janus.calibrate

/**
 * Cohen's κ, the confusion matrix, and raw agreement — the M4 credibility metrics.
 *
 * Hand-rolled deliberately (no library call): κ is the number you have to defend on a whiteboard,
 * and the derivation is short. Raw agreement is a liar under class imbalance — a judge that blindly
 * says PASS scores 85% "accuracy" when 85% of cases are PASS while being useless. κ corrects for the
 * agreement expected by chance: κ = (Pₒ − Pₑ) / (1 − Pₑ), where Pₒ is observed agreement and Pₑ is
 * the agreement you'd expect if both raters labeled independently at their observed marginal rates.
 */
data class CalibrationResult(
    val labels: List<String>,
    val n: Int,
    val observedAgreement: Double,   // Pₒ — fraction of cases where judge == human
    val expectedAgreement: Double,   // Pₑ — chance agreement from the marginals
    val kappa: Double,
    /** confusion[i][j] = count where human=labels[i], judge=labels[j]. Diagonal = agreement. */
    val confusion: Array<IntArray>,
) {
    /** Landis–Koch interpretation bands, for a human-readable verdict. */
    val interpretation: String
        get() = when {
            kappa < 0.0 -> "worse than chance"
            kappa < 0.2 -> "slight"
            kappa < 0.4 -> "fair"
            kappa < 0.6 -> "moderate"
            kappa < 0.8 -> "substantial"
            else -> "near-perfect"
        }

    fun renderConfusion(): String = buildString {
        val w = (labels.maxOf { it.length }).coerceAtLeast(6)
        append("human\\judge".padEnd(w + 2))
        labels.forEach { append(it.padStart(w + 2)) }
        appendLine()
        labels.forEachIndexed { i, human ->
            append(human.padEnd(w + 2))
            confusion[i].forEach { append(it.toString().padStart(w + 2)) }
            appendLine()
        }
    }
}

object Kappa {
    /**
     * @param labels    the label space (defines matrix order; must cover all observed labels)
     * @param human     human gold labels, aligned by index with [judge]
     * @param judge     judge labels, aligned by index with [human]
     */
    fun compute(labels: List<String>, human: List<String>, judge: List<String>): CalibrationResult {
        require(human.size == judge.size) { "human and judge label lists must be the same length" }
        require(human.isNotEmpty()) { "cannot compute kappa over zero cases" }
        val idx = labels.withIndex().associate { (i, l) -> l to i }
        val n = human.size

        val confusion = Array(labels.size) { IntArray(labels.size) }
        for (k in 0 until n) {
            val i = idx[human[k]] ?: error("human label '${human[k]}' not in label space")
            val j = idx[judge[k]] ?: error("judge label '${judge[k]}' not in label space")
            confusion[i][j]++
        }

        // Pₒ = fraction on the diagonal.
        val agree = labels.indices.sumOf { confusion[it][it] }
        val po = agree.toDouble() / n

        // Pₑ = Σ_k (humanFrac_k × judgeFrac_k) over labels k.
        val pe = labels.indices.sumOf { k ->
            val humanFrac = confusion[k].sum().toDouble() / n          // row total = human marginal
            val judgeFrac = labels.indices.sumOf { confusion[it][k] }.toDouble() / n // col total = judge marginal
            humanFrac * judgeFrac
        }

        // κ. When Pₑ == 1 (both raters used a single label for everything) κ is undefined;
        // report 1.0 if they also fully agree, else 0.0 — the conventional degenerate-case handling.
        val kappa = if (pe >= 1.0) { if (po >= 1.0) 1.0 else 0.0 } else (po - pe) / (1.0 - pe)

        return CalibrationResult(labels, n, po, pe, kappa, confusion)
    }
}
