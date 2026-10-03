package com.janus.cli

import com.janus.compare.Interval
import com.janus.compare.MetricComparison
import com.janus.compare.Verdict
import com.janus.report.Dashboard
import com.janus.report.Reporter
import com.janus.score.Metric
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Generates a sample dashboard with representative data (one regression, one improvement,
 * one no-change) purely for showcase/README screenshots — no DB, no models. Lets you see the
 * UI populated with realistic numbers before you have real runs.
 */
@Command(name = "demo-dashboard", hidden = true, description = ["Emit a sample dashboard HTML with representative data."])
class DemoDashboardCommand : Runnable {
    @Option(names = ["--out"], description = ["Output path (default: janus-dashboard-sample.html)."])
    var out: String = "janus-dashboard-sample.html"

    override fun run() {
        val comparisons = listOf(
            MetricComparison(
                metric = Metric.JSON_VALID, n = 50, oldMean = 0.980, newMean = 0.920,
                ci = Interval(lo = -0.091, hi = -0.030, point = -0.060),
                noiseFloor = 0.018, verdict = Verdict.REGRESSION,
                regressedCases = listOf("invoice-07", "order-22", "refund-05", "shipment-13"),
            ),
            MetricComparison(
                metric = Metric.EXACT_MATCH, n = 50, oldMean = 0.840, newMean = 0.902,
                ci = Interval(lo = 0.021, hi = 0.104, point = 0.062),
                noiseFloor = 0.018, verdict = Verdict.IMPROVEMENT, regressedCases = emptyList(),
            ),
            MetricComparison(
                metric = Metric.JUDGE, n = 50, oldMean = 0.760, newMean = 0.772,
                ci = Interval(lo = -0.014, hi = 0.038, point = 0.012),
                noiseFloor = 0.018, verdict = Verdict.NO_CHANGE, regressedCases = emptyList(),
            ),
        )
        Reporter.writeFile(out, Dashboard.render(comparisons, oldRunId = "a1b2c3d4", newRunId = "e5f6a7b8"))
        println("Wrote sample dashboard -> $out")
    }
}
