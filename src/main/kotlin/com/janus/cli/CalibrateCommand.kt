package com.janus.cli

import com.janus.calibrate.Calibrator
import com.janus.calibrate.LabelLoader
import com.janus.calibrate.Split
import com.janus.calibrate.SplitAssignments
import com.janus.model.FakeJudgeModel
import com.janus.model.GeminiModel
import com.janus.model.Model
import com.janus.score.LlmJudgeScorer
import com.janus.suite.SuiteLoader
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * `eval calibrate` — M4. Runs the target's judge against a set of human gold labels and reports
 * Cohen's κ + the confusion matrix, separately for the dev split (which you tune the rubric
 * against) and the holdout split (reported once, never tuned against). The holdout κ is the honest,
 * un-gamed number that goes on the resume; the dev→holdout gap tells you whether you overfit.
 */
@Command(
    name = "calibrate",
    mixinStandardHelpOptions = true,
    description = ["Measure judge agreement with human labels (Cohen's kappa + confusion matrix)."],
)
class CalibrateCommand : Runnable {

    @Option(names = ["--target"], required = true, description = ["Target YAML (must have a judge config)."])
    lateinit var targetPath: String

    @Option(names = ["--labels"], required = true, description = ["Gold labels YAML."])
    lateinit var labelsPath: String

    @Option(names = ["--holdout-fraction"], description = ["Fraction of cases held out (default 0.4)."])
    var holdoutFraction: Double = 0.4

    override fun run() {
        val target = SuiteLoader().loadTarget(targetPath)
        val cfg = target.judge
            ?: error("Target '${target.name}' has no judge config; calibration needs one.")
        val set = LabelLoader.load(labelsPath)

        val judge = LlmJudgeScorer(judgeModelFor(cfg.model), cfg)
        val assignments = SplitAssignments.deterministic(set.gold.map { it.caseId }, holdoutFraction)

        println("Label set: ${set.name} v${set.version} (${set.gold.size} gold labels)")
        println("Judge model: ${cfg.model}   holdout fraction: $holdoutFraction")
        println("=".repeat(72))

        val reports = runBlocking { Calibrator(judge).calibrate(set, assignments) }
        for (r in reports) {
            val res = r.result
            println()
            println("── ${r.split} split (n=${res.n}) ".padEnd(72, '─'))
            println("Cohen's kappa : %.3f  (%s)".format(res.kappa, res.interpretation))
            println("agreement Po  : %.3f".format(res.observedAgreement))
            println("chance   Pe   : %.3f".format(res.expectedAgreement))
            println("confusion matrix:")
            print(res.renderConfusion())
        }

        val holdout = reports.firstOrNull { it.split == Split.HOLDOUT }?.result
        if (holdout != null) {
            println()
            println("=".repeat(72))
            println("HEADLINE (holdout, never tuned against): kappa=%.3f (%s)"
                .format(holdout.kappa, holdout.interpretation))
        }
    }

    /** Same resolution as ScorerRegistry: fake-judge offline at $0, gemini* on the free tier. */
    private fun judgeModelFor(id: String): Model = when {
        id == "fake-judge" -> FakeJudgeModel()
        id.startsWith("gemini") -> GeminiModel(id = id, modelName = id)
        else -> error("Unknown judge model '$id'. Use 'fake-judge' or a 'gemini*' model.")
    }
}
