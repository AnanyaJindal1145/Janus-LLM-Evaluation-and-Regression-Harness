package com.janus

import com.janus.compare.Comparator
import com.janus.compare.NoiseFloor
import com.janus.compare.Verdict
import com.janus.run.CaseResult
import com.janus.run.Run
import com.janus.run.Sample
import com.janus.score.Metric
import com.janus.score.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparatorTest {

    private fun run(id: String, caseScores: Map<String, Double>): Run =
        Run(
            runId = id, suiteName = "s", suiteVersion = "1", targetName = "t",
            promptVersion = "1", modelId = "echo", samplesPerCase = 1, createdAtEpochMs = 0L,
            results = caseScores.map { (cid, v) ->
                CaseResult(cid, listOf(Sample("out", listOf(Score(Metric.EXACT_MATCH, v)))))
            },
        )

    private val cases = (1..20).map { "c$it" }

    @Test
    fun `uniform drop is flagged REGRESSION`() {
        val old = run("old", cases.associateWith { 1.0 })
        val new = run("new", cases.associateWith { 0.6 })
        val c = Comparator().compare(old, new).first { it.metric == Metric.EXACT_MATCH }
        assertEquals(Verdict.REGRESSION, c.verdict)
        assertEquals(20, c.regressedCases.size)
    }

    @Test
    fun `identical runs are NO_CHANGE`() {
        val scores = cases.associateWith { if (it.hashCode() % 2 == 0) 1.0 else 0.0 }
        val old = run("old", scores)
        val new = run("new", scores)
        val c = Comparator().compare(old, new).first { it.metric == Metric.EXACT_MATCH }
        assertEquals(Verdict.NO_CHANGE, c.verdict)
    }

    @Test
    fun `real-but-tiny drop below the noise floor is NOT a regression`() {
        // Statistically detectable (uniform -0.05) but below a 0.1 noise floor → NO_CHANGE.
        val old = run("old", cases.associateWith { 1.0 })
        val new = run("new", cases.associateWith { 0.95 })
        val floors = mapOf(Metric.EXACT_MATCH to 0.10)
        val c = Comparator().compare(old, new, floors).first { it.metric == Metric.EXACT_MATCH }
        assertTrue(c.ci.hi < 0.0, "CI should exclude zero (statistically real)")
        assertEquals(Verdict.NO_CHANGE, c.verdict, "but below noise floor → not flagged")
    }

    @Test
    fun `noise floor from identical control runs is near zero`() {
        val controls = (1..5).map { run("ctrl$it", cases.associateWith { 1.0 }) }
        val floors = NoiseFloor.measure(controls)
        assertEquals(0.0, floors[Metric.EXACT_MATCH]!!, 1e-9)
    }
}
