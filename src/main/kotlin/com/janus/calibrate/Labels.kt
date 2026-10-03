package com.janus.calibrate

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/**
 * One hand-assigned gold label for a case: the human's own judgment against the SAME rubric the
 * judge sees, assigned BEFORE looking at any judge output (seeing the judge first anchors you and
 * inflates agreement). `output` is the exact response text that was labeled — the judge must grade
 * this same text, or the comparison is meaningless.
 */
data class GoldLabel(
    val caseId: String,
    val input: String,
    val output: String,
    val label: String,
    val expected: String? = null,
)

/**
 * A versioned set of gold labels plus the label space. `split` assigns each label to "dev" or
 * "holdout": you iterate the rubric against dev only, and report the final Cohen's κ on holdout —
 * a set the rubric was never tuned against. This is the same discipline as a train/test split for
 * a model; without it, rubric iteration overfits the labels and the reported κ is meaningless.
 */
data class LabelSet(
    val name: String,
    val version: String,
    val labels: List<String>,
    val gold: List<GoldLabel>,
) {
    fun dev(assignments: SplitAssignments): List<GoldLabel> =
        gold.filter { assignments.splitOf(it.caseId) == Split.DEV }

    fun holdout(assignments: SplitAssignments): List<GoldLabel> =
        gold.filter { assignments.splitOf(it.caseId) == Split.HOLDOUT }
}

enum class Split { DEV, HOLDOUT }

/**
 * Deterministic dev/holdout assignment. Case ids are sorted and hashed to a stable bucket so the
 * split never changes between runs (reproducibility, §6.5) and doesn't need a stored RNG seed.
 * `holdoutFraction` of the cases (by count, deterministically) go to holdout.
 */
class SplitAssignments private constructor(private val split: Map<String, Split>) {
    fun splitOf(caseId: String): Split =
        split[caseId] ?: error("case '$caseId' has no split assignment")

    companion object {
        fun deterministic(caseIds: List<String>, holdoutFraction: Double = 0.4): SplitAssignments {
            require(holdoutFraction in 0.0..1.0) { "holdoutFraction must be in [0,1]" }
            val sorted = caseIds.distinct().sorted()
            val holdoutCount = (sorted.size * holdoutFraction).toInt()
            // Rank by a stable hash so which cases land in holdout doesn't correlate with id order.
            val holdout = sorted
                .sortedBy { stableHash(it) }
                .take(holdoutCount)
                .toSet()
            return SplitAssignments(sorted.associateWith { if (it in holdout) Split.HOLDOUT else Split.DEV })
        }

        private fun stableHash(s: String): Int {
            // FNV-1a — stable across JVMs, unlike String.hashCode() guarantees for our purposes.
            var h = -0x7ee3623b // 2166136261 as Int
            for (c in s) {
                h = h xor c.code
                h *= 0x01000193
            }
            return h
        }
    }
}

object LabelLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule().apply {
        enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    fun load(path: String): LabelSet {
        val file = File(path)
        require(file.exists()) { "Labels file not found: $path" }
        val set: LabelSet = mapper.readValue(file)
        require(set.gold.isNotEmpty()) { "Label set '${set.name}' has no gold labels" }
        val unknown = set.gold.map { it.label }.toSet() - set.labels.toSet()
        require(unknown.isEmpty()) { "Gold labels use labels not in the label space: $unknown" }
        val dupes = set.gold.groupingBy { it.caseId }.eachCount().filter { it.value > 1 }.keys
        require(dupes.isEmpty()) { "Duplicate case ids in gold labels: $dupes" }
        return set
    }
}
