package com.janus

import com.janus.calibrate.Calibrator
import com.janus.calibrate.GoldLabel
import com.janus.calibrate.LabelSet
import com.janus.calibrate.Split
import com.janus.calibrate.SplitAssignments
import com.janus.model.FakeJudgeModel
import com.janus.score.LlmJudgeScorer
import com.janus.suite.JudgeConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibratorTest {

    private val cfg = JudgeConfig(
        model = "fake-judge",
        rubric = "PASS if the response contains the expected answer.",
    )

    @Test
    fun `fake judge agrees perfectly with gold when it tracks the expected answer`() = runTest {
        // FakeJudgeModel passes iff output contains expected. Craft gold labels that match that rule
        // exactly, so a correct judge yields kappa=1 on both splits.
        val gold = listOf(
            GoldLabel("a", "Q1", "Paris", "PASS", expected = "Paris"),
            GoldLabel("b", "Q2", "Tokyo", "PASS", expected = "Tokyo"),
            GoldLabel("c", "Q3", "London", "FAIL", expected = "Paris"),
            GoldLabel("d", "Q4", "5", "FAIL", expected = "4"),
            GoldLabel("e", "Q5", "Rome", "PASS", expected = "Rome"),
        )
        val set = LabelSet("t", "1", listOf("PASS", "FAIL"), gold)
        val assignments = SplitAssignments.deterministic(gold.map { it.caseId }, holdoutFraction = 0.4)

        val reports = Calibrator(LlmJudgeScorer(FakeJudgeModel(), cfg)).calibrate(set, assignments)

        assertTrue(reports.isNotEmpty())
        for (r in reports) {
            assertEquals(1.0, r.result.kappa, 1e-9)
        }
        // Both splits present with 40% holdout of 5 = 2.
        assertEquals(2, reports.first { it.split == Split.HOLDOUT }.result.n)
        assertEquals(3, reports.first { it.split == Split.DEV }.result.n)
    }

    @Test
    fun `judge-human disagreement lowers kappa below 1`() = runTest {
        // Gold label 'c' PASS but the fake judge will FAIL it (output doesn't contain expected):
        // a deliberate human/judge disagreement, so kappa < 1.
        val gold = listOf(
            GoldLabel("a", "Q1", "Paris", "PASS", expected = "Paris"),
            GoldLabel("b", "Q2", "Tokyo", "PASS", expected = "Tokyo"),
            GoldLabel("c", "Q3", "London", "PASS", expected = "Paris"), // human says PASS, judge says FAIL
            GoldLabel("d", "Q4", "5", "FAIL", expected = "4"),
        )
        val set = LabelSet("t", "1", listOf("PASS", "FAIL"), gold)
        val assignments = SplitAssignments.deterministic(gold.map { it.caseId }, holdoutFraction = 0.0)

        val reports = Calibrator(LlmJudgeScorer(FakeJudgeModel(), cfg)).calibrate(set, assignments)
        val dev = reports.first { it.split == Split.DEV }.result
        assertTrue(dev.kappa < 1.0, "expected disagreement to pull kappa below 1, got ${dev.kappa}")
    }
}
