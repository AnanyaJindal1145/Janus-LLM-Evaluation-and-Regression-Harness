package com.janus

import com.janus.compare.Interval
import com.janus.compare.MetricComparison
import com.janus.compare.Verdict
import com.janus.report.Reporter
import com.janus.score.Metric
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReporterTest {

    private fun comp(verdict: Verdict) = MetricComparison(
        metric = Metric.JSON_VALID, n = 20, oldMean = 0.98, newMean = 0.92,
        ci = Interval(lo = -0.09, hi = -0.03, point = -0.06),
        noiseFloor = 0.02, verdict = verdict, regressedCases = listOf("c3", "c7"),
    )

    @Test
    fun `junit emits a failure for a regression so CI goes red`() {
        val xml = Reporter.junitXml(listOf(comp(Verdict.REGRESSION)))
        assertTrue(xml.contains("failures=\"1\""))
        assertTrue(xml.contains("<failure"))
        assertTrue(xml.contains("c3, c7"))
    }

    @Test
    fun `junit has no failure when there is no regression`() {
        val xml = Reporter.junitXml(listOf(comp(Verdict.NO_CHANGE)))
        assertTrue(xml.contains("failures=\"0\""))
        assertFalse(xml.contains("<failure"))
    }

    @Test
    fun `html renders the verdict class`() {
        val html = Reporter.html(listOf(comp(Verdict.REGRESSION)))
        assertTrue(html.contains("class='REGRESSION'"))
        assertTrue(html.contains("JSON_VALID"))
    }
}
