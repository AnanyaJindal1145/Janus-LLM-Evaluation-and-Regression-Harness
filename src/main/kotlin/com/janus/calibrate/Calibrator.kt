package com.janus.calibrate

import com.janus.model.ModelResponse
import com.janus.score.LlmJudgeScorer
import com.janus.suite.Case
import com.janus.suite.JudgeConfig

/**
 * Runs the LLM judge against a set of human-labeled outputs and reports agreement.
 *
 * The judge is asked to grade the SAME `output` text the human labeled, so the two verdicts are
 * comparable. We report κ separately on the dev split (which you iterate the rubric against) and
 * the holdout split (reported once, never tuned against) — see LabelSet docs for why the split
 * matters. Reporting only holdout κ, with the dev→holdout gap visible, is the honest number.
 */
class Calibrator(private val judge: LlmJudgeScorer) {

    data class SplitReport(val split: Split, val result: CalibrationResult)

    /** Full calibration: judge every gold label, then score dev and holdout separately. */
    suspend fun calibrate(set: LabelSet, assignments: SplitAssignments): List<SplitReport> {
        val judged = set.gold.associateWith { runJudge(set, it) }
        // Matrix label space must include the UNPARSED sentinel — the judge can emit it, and
        // Kappa requires every observed label to be in the space. Human labels never use it.
        val labelSpace = set.labels + UNPARSED
        return listOf(Split.DEV, Split.HOLDOUT).mapNotNull { split ->
            val subset = set.gold.filter { assignments.splitOf(it.caseId) == split }
            if (subset.isEmpty()) return@mapNotNull null
            val human = subset.map { it.label }
            val judgeLabels = subset.map { judged.getValue(it) }
            SplitReport(split, Kappa.compute(labelSpace, human, judgeLabels))
        }
    }

    /**
     * Reconstruct the label the judge assigned to one gold output. The LlmJudgeScorer maps the
     * label to a score+detail; the detail is "LABEL: reasoning", so we recover the label from the
     * detail prefix. An unparseable/unknown verdict is recorded as the sentinel "__UNPARSED__"
     * so it counts as a disagreement rather than crashing the run.
     */
    private suspend fun runJudge(set: LabelSet, gold: GoldLabel): String {
        val case = Case(id = gold.caseId, input = gold.input, expected = gold.expected)
        val score = judge.score(case, ModelResponse(text = gold.output))
        val detail = score.detail ?: return UNPARSED
        val label = detail.substringBefore(":").trim()
        return if (label in set.labels) label else UNPARSED
    }

    companion object {
        const val UNPARSED = "__UNPARSED__"
    }
}

/** Convenience: judge config-agnostic label space includes the UNPARSED sentinel for the matrix. */
fun JudgeConfig.labelSpaceWithSentinel(): List<String> = labels + Calibrator.UNPARSED
