package com.janus.score

import com.janus.model.FakeJudgeModel
import com.janus.model.GeminiModel
import com.janus.model.Model
import com.janus.suite.Target

/**
 * Maps scorer names (from Target YAML) to Scorer instances. Deterministic scorers need nothing;
 * the JUDGE scorer needs the target's `judge` config and a resolved judge Model.
 */
object ScorerRegistry {
    fun resolve(target: Target): List<Scorer> = target.scorers.map { name ->
        when (Metric.valueOf(name)) {
            Metric.EXACT_MATCH -> ExactMatchScorer()
            Metric.REGEX_MATCH -> RegexMatchScorer()
            Metric.CONTAINS -> ContainsScorer()
            Metric.JSON_VALID -> JsonValidScorer()
            Metric.NUMERIC_TOLERANCE -> NumericToleranceScorer()
            Metric.JUDGE -> {
                val cfg = target.judge
                    ?: error("Target '${target.name}' lists JUDGE scorer but has no `judge:` config")
                LlmJudgeScorer(judgeModelFor(cfg.model), cfg)
            }
        }
    }

    /** Resolves the judge model id. `fake-judge` runs offline at $0; `gemini*` hits the free tier. */
    private fun judgeModelFor(id: String): Model = when {
        id == "fake-judge" -> FakeJudgeModel()
        id.startsWith("gemini") -> GeminiModel(id = id, modelName = id)
        else -> error("Unknown judge model '$id'. Use 'fake-judge' or a 'gemini*' model.")
    }
}
