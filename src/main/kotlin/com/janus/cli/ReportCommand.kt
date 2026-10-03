package com.janus.cli

import com.janus.compare.Comparator
import com.janus.compare.NoiseFloor
import com.janus.report.Reporter
import com.janus.score.Metric
import com.janus.store.RunStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * `eval report` — M7. Same comparison as `compare` but focused on emitting artifacts
 * (JUnit XML + HTML) without gating, for when you want the report without failing the shell.
 * CI uses `compare --junit` to gate; this is for local inspection / artifact upload.
 */
@Command(
    name = "report",
    mixinStandardHelpOptions = true,
    description = ["Emit JUnit XML and/or HTML comparison artifacts (no exit-code gating)."],
)
class ReportCommand : Runnable {

    @Option(names = ["--db"], description = ["SQLite file (default: janus.db)."])
    var dbPath: String = "janus.db"

    @Option(names = ["--old"], required = true, description = ["Baseline run id."])
    lateinit var oldRunId: String

    @Option(names = ["--new"], required = true, description = ["Candidate run id."])
    lateinit var newRunId: String

    @Option(names = ["--control"], description = ["Control run ids for the noise floor; comma-separated."])
    var controlIds: String = ""

    @Option(names = ["--junit"], description = ["JUnit XML output path (default: janus-report.xml)."])
    var junitPath: String = "janus-report.xml"

    @Option(names = ["--html"], description = ["Plain HTML output path (default: janus-report.html)."])
    var htmlPath: String = "janus-report.html"

    @Option(names = ["--dashboard"], description = ["Minimalist dashboard HTML output path (default: janus-dashboard.html)."])
    var dashboardPath: String = "janus-dashboard.html"

    override fun run() {
        RunStore(dbPath).use { store ->
            val oldRun = store.loadRun(oldRunId)
            val newRun = store.loadRun(newRunId)
            val floors: Map<Metric, Double> = controlIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .let { ids -> if (ids.size >= 2) NoiseFloor.measure(ids.map { store.loadRun(it) }) else emptyMap() }

            val comparisons = Comparator().compare(oldRun, newRun, floors)
            Reporter.writeFile(junitPath, Reporter.junitXml(comparisons))
            Reporter.writeFile(htmlPath, Reporter.html(comparisons))
            Reporter.writeFile(dashboardPath, com.janus.report.Dashboard.render(comparisons, oldRunId = oldRunId, newRunId = newRunId))
            println(Reporter.cliTable(comparisons))
            println("Wrote $junitPath, $htmlPath, $dashboardPath")
        }
    }
}
