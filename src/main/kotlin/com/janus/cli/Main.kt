package com.janus.cli

import com.janus.model.EchoModel
import com.janus.model.Model
import com.janus.model.OllamaModel
import com.janus.run.Runner
import com.janus.score.ScorerRegistry
import com.janus.store.RunStore
import com.janus.suite.SuiteLoader
import com.janus.suite.Target
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.UUID
import kotlin.system.exitProcess

/**
 * `eval` — the Janus CLI. Subcommands: run (M1-M3,M5,M6), calibrate (M4),
 * compare (M5), report (M7).
 */
@Command(
    name = "eval",
    mixinStandardHelpOptions = true,
    version = ["janus 0.1.0"],
    subcommands = [RunCommand::class, CalibrateCommand::class, CompareCommand::class, ReportCommand::class, DemoDashboardCommand::class],
    description = ["Janus — LLM evaluation & regression harness"],
)
class EvalCommand

/**
 * Resolves a subject-under-test model id. `echo` is the offline stub; any other id is an
 * Ollama-served local model (the $0 subject in Janus's provider stack).
 */
internal fun subjectModelFor(id: String): Model = when (id) {
    "echo" -> EchoModel()
    else -> OllamaModel(id = id)
}

@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = ["Run a suite against a target with N samples/case, score, and persist."],
)
class RunCommand : Runnable {

    @Option(names = ["--suite"], required = true, description = ["Path to suite YAML."])
    lateinit var suitePath: String

    @Option(names = ["--target"], required = true, description = ["Path to target YAML."])
    lateinit var targetPath: String

    @Option(names = ["--db"], description = ["SQLite file to persist the run to (default: janus.db)."])
    var dbPath: String = "janus.db"

    @Option(names = ["--samples"], description = ["Samples per case (default: 3)."])
    var samples: Int = 3

    @Option(names = ["--concurrency"], description = ["Max in-flight model calls (default: 4)."])
    var concurrency: Int = 4

    override fun run() {
        val loader = SuiteLoader()
        val suite = loader.loadSuite(suitePath)
        val target: Target = loader.loadTarget(targetPath)
        val model = subjectModelFor(target.modelId)
        val scorers = ScorerRegistry.resolve(target)
        val runId = UUID.randomUUID().toString()

        println("Suite:  ${suite.name} v${suite.version} (${suite.cases.size} cases)")
        println("Target: ${target.name} promptVersion=${target.promptVersion} model=${target.modelId}")
        println("Scorers: ${target.scorers.joinToString()}  |  samples/case=$samples  concurrency=$concurrency")
        println("-".repeat(72))

        val runner = Runner(model, scorers, maxConcurrency = concurrency)
        val startedAt = System.currentTimeMillis() // clock read stays at the CLI boundary
        val run = runBlocking {
            runner.run(suite, target, samplesPerCase = samples, runId = runId, createdAtEpochMs = startedAt)
        }
        val wallMs = System.currentTimeMillis() - startedAt

        // Per-case mean score per metric + M6 cost/latency axes.
        for (metric in run.metrics()) {
            println("metric $metric:")
            for (cr in run.results) {
                val score = cr.perCaseScore(metric)
                println("  [${cr.caseId}] mean=${score?.let { "%.2f".format(it) } ?: "-"}")
            }
        }
        val totalTokens = run.results.sumOf { it.totalTokens() }
        val meanLatency = run.results.flatMap { it.samples }.mapNotNull { it.latencyMs }
            .ifEmpty { null }?.map { it.toDouble() }?.average()

        RunStore(dbPath).use { it.save(run) }

        println("-".repeat(72))
        println("Run $runId saved to $dbPath")
        println("Wall-clock: ${wallMs}ms  |  total tokens: $totalTokens" +
            (meanLatency?.let { "  |  mean call latency: %.0fms".format(it) } ?: ""))
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(EvalCommand()).execute(*args))
}
