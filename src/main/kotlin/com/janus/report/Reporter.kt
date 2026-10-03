package com.janus.report

import com.janus.compare.MetricComparison
import com.janus.compare.Verdict
import java.io.File

/**
 * Renders a comparison in three formats. CLI table for humans; JUnit XML so CI treats a
 * regression like a failing test (the M7 gating mechanism — GitHub Actions understands JUnit
 * out of the box); HTML as a browsable artifact.
 */
object Reporter {

    fun cliTable(comparisons: List<MetricComparison>): String = buildString {
        appendLine("%-16s %6s %8s %8s %-26s %-12s".format("metric", "n", "old", "new", "delta [95% CI]", "verdict"))
        appendLine("-".repeat(84))
        for (c in comparisons) {
            val ci = "%+.3f [%+.3f, %+.3f]".format(c.ci.point, c.ci.lo, c.ci.hi)
            appendLine("%-16s %6d %8.3f %8.3f %-26s %-12s".format(
                c.metric.name, c.n, c.oldMean, c.newMean, ci, c.verdict.name))
            if (c.verdict == Verdict.REGRESSION && c.regressedCases.isNotEmpty()) {
                appendLine("    regressed cases: ${c.regressedCases.joinToString(", ")}")
            }
        }
    }

    /** JUnit XML: one <testcase> per metric; a REGRESSION becomes a <failure> → CI goes red. */
    fun junitXml(comparisons: List<MetricComparison>, suiteName: String = "janus.regression"): String {
        val failures = comparisons.count { it.verdict == Verdict.REGRESSION }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<testsuite name="${esc(suiteName)}" tests="${comparisons.size}" failures="$failures">""").append('\n')
        for (c in comparisons) {
            sb.append("""  <testcase classname="janus" name="${esc(c.metric.name)}">""").append('\n')
            if (c.verdict == Verdict.REGRESSION) {
                val msg = "regression: ${"%.3f".format(c.oldMean)} -> ${"%.3f".format(c.newMean)} " +
                    "(delta ${"%+.3f".format(c.ci.point)}, CI [${"%+.3f".format(c.ci.lo)}, ${"%+.3f".format(c.ci.hi)}], " +
                    "noiseFloor ${"%.3f".format(c.noiseFloor)}); cases: ${c.regressedCases.joinToString(", ")}"
                sb.append("""    <failure message="${esc(msg)}">${esc(msg)}</failure>""").append('\n')
            }
            sb.append("  </testcase>").append('\n')
        }
        sb.append("</testsuite>").append('\n')
        return sb.toString()
    }

    fun html(comparisons: List<MetricComparison>): String = buildString {
        appendLine("<!doctype html><html><head><meta charset='utf-8'><title>Janus Comparison</title>")
        appendLine("<style>body{font-family:sans-serif;margin:2rem}table{border-collapse:collapse}" +
            "td,th{border:1px solid #ccc;padding:6px 10px;text-align:left}" +
            ".REGRESSION{color:#b00;font-weight:bold}.IMPROVEMENT{color:#070}.NO_CHANGE{color:#666}</style></head><body>")
        appendLine("<h1>Janus Regression Report</h1><table>")
        appendLine("<tr><th>Metric</th><th>n</th><th>Old</th><th>New</th><th>Delta [95% CI]</th><th>Noise floor</th><th>Verdict</th><th>Regressed cases</th></tr>")
        for (c in comparisons) {
            appendLine("<tr><td>${c.metric}</td><td>${c.n}</td>" +
                "<td>${"%.3f".format(c.oldMean)}</td><td>${"%.3f".format(c.newMean)}</td>" +
                "<td>${"%+.3f".format(c.ci.point)} [${"%+.3f".format(c.ci.lo)}, ${"%+.3f".format(c.ci.hi)}]</td>" +
                "<td>${"%.3f".format(c.noiseFloor)}</td>" +
                "<td class='${c.verdict}'>${c.verdict}</td>" +
                "<td>${c.regressedCases.joinToString(", ")}</td></tr>")
        }
        appendLine("</table></body></html>")
    }

    fun writeFile(path: String, content: String) = File(path).writeText(content)

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
