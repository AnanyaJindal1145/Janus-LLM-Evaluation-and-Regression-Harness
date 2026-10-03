package com.janus.cli

import com.janus.compare.Comparator
import com.janus.compare.NoiseFloor
import com.janus.compare.Verdict
import com.janus.report.Reporter
import com.janus.score.Metric
import com.janus.store.RunStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import kotlin.system.exitProcess

/**
 * `eval compare <oldRun> <newRun>` — M5. Loads two runs, applies the case-level bootstrap +
 * two-bar verdict, and prints the per-metric comparison. Exits non-zero if any metric
 * regressed, so a shell/CI step can gate on it directly. The noise floor is read from a set
 * of control runs (same target vs itself) if provided; otherwise it defaults to 0 and the
 * second bar is effectively disabled (CI-only becomes the gate).
 */
@Command(
    name = "compare",
    mixinStandardHelpOptions = true,
    description = ["Compare two runs and emit a regression verdict (exit 1 on regression)."],
)
class CompareCommand : Runnable {

    @Option(names = ["--db"], description = ["SQLite file (default: janus.db)."])
    var dbPath: String = "janus.db"

    @Option(names = ["--old"], required = true, description = ["Baseline run id."])
    lateinit var oldRunId: String

    @Option(names = ["--new"], required = true, description = ["Candidate run id."])
    lateinit var newRunId: String

    @Option(names = ["--control"], description = ["Control run ids (same target vs itself) for the noise floor; comma-separated."])
    var controlIds: String = ""

    @Option(names = ["--junit"], description = ["Write JUnit XML to this path (for CI)."])
    var junitPath: String? = null

    @Option(names = ["--html"], description = ["Write an HTML report to this path."])
    var htmlPath: String? = null

    override fun run() {
        RunStore(dbPath).use { store ->
            val oldRun = store.loadRun(oldRunId)
            val newRun = store.loadRun(newRunId)

            val floors: Map<Metric, Double> = controlIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .let { ids ->
                    if (ids.size >= 2) NoiseFloor.measure(ids.map { store.loadRun(it) }) else emptyMap()
                }

            val comparisons = Comparator().compare(oldRun, newRun, floors)
            println(Reporter.cliTable(comparisons))

            junitPath?.let { Reporter.writeFile(it, Reporter.junitXml(comparisons)); println("JUnit XML -> $it") }
            htmlPath?.let { Reporter.writeFile(it, Reporter.html(comparisons)); println("HTML -> $it") }

            val regressed = comparisons.count { it.verdict == Verdict.REGRESSION }
            if (regressed > 0) {
                println("REGRESSION: $regressed metric(s) regressed.")
                exitProcess(1)
            }
            println("OK: no regressions.")
        }
    }
}
