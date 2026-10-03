package com.janus

import com.janus.calibrate.Kappa
import com.janus.calibrate.Split
import com.janus.calibrate.SplitAssignments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KappaTest {
    private val labels = listOf("PASS", "FAIL")

    @Test
    fun `perfect agreement is kappa 1`() {
        val human = listOf("PASS", "FAIL", "PASS", "FAIL")
        val judge = listOf("PASS", "FAIL", "PASS", "FAIL")
        val r = Kappa.compute(labels, human, judge)
        assertEquals(1.0, r.kappa, 1e-9)
        assertEquals(1.0, r.observedAgreement, 1e-9)
    }

    @Test
    fun `chance-level agreement is kappa near zero`() {
        // Judge says PASS every time; human is balanced. High raw agreement is a lie here.
        val human = listOf("PASS", "PASS", "FAIL", "FAIL")
        val judge = listOf("PASS", "PASS", "PASS", "PASS")
        val r = Kappa.compute(labels, human, judge)
        assertEquals(0.5, r.observedAgreement, 1e-9) // 2/4 agree — "looks" ok
        assertEquals(0.0, r.kappa, 1e-9)             // but kappa exposes it as chance-level
    }

    @Test
    fun `known kappa value from a worked 2x2 table`() {
        // human PASS x10 / FAIL x10; judge agrees on 8 PASS and 7 FAIL.
        // confusion: PASS/PASS=8, PASS/FAIL=2, FAIL/PASS=3, FAIL/FAIL=7
        val human = MutableList(10) { "PASS" } + MutableList(10) { "FAIL" }
        val judge = (List(8) { "PASS" } + List(2) { "FAIL" }) + (List(3) { "PASS" } + List(7) { "FAIL" })
        val r = Kappa.compute(labels, human, judge)
        // Po = 15/20 = 0.75; Pe = (10/20)(11/20) + (10/20)(9/20) = 0.275+0.225 = 0.5
        // kappa = (0.75-0.5)/(1-0.5) = 0.5
        assertEquals(0.75, r.observedAgreement, 1e-9)
        assertEquals(0.50, r.expectedAgreement, 1e-9)
        assertEquals(0.50, r.kappa, 1e-9)
        assertEquals("moderate", r.interpretation)
    }

    @Test
    fun `confusion matrix diagonal counts agreement`() {
        val human = listOf("PASS", "FAIL", "PASS")
        val judge = listOf("PASS", "PASS", "PASS")
        val r = Kappa.compute(labels, human, judge)
        assertEquals(2, r.confusion[0][0]) // human PASS, judge PASS
        assertEquals(1, r.confusion[1][0]) // human FAIL, judge PASS (a disagreement)
    }

    @Test
    fun `split is deterministic and respects holdout fraction`() {
        val ids = (1..10).map { "case-$it" }
        val a = SplitAssignments.deterministic(ids, holdoutFraction = 0.4)
        val b = SplitAssignments.deterministic(ids, holdoutFraction = 0.4)
        val holdoutA = ids.filter { a.splitOf(it) == Split.HOLDOUT }
        val holdoutB = ids.filter { b.splitOf(it) == Split.HOLDOUT }
        assertEquals(holdoutA, holdoutB)                  // deterministic across runs
        assertEquals(4, holdoutA.size)                    // 40% of 10
        assertTrue(ids.all { a.splitOf(it) == Split.DEV || a.splitOf(it) == Split.HOLDOUT })
    }
}
