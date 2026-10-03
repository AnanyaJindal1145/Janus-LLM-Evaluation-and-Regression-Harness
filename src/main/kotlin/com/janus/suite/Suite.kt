package com.janus.suite

import com.janus.model.ModelParams

/**
 * One test input plus metadata. `expected` and `rubric` are optional because different
 * scorer families need different things: deterministic scorers use `expected`, the
 * LLM judge (M3+) uses `rubric`. `tags` allow slicing results by category later.
 */
data class Case(
    val id: String,
    val input: String,
    val expected: String? = null,
    val rubric: String? = null,
    val tags: List<String> = emptyList(),
)

/** A named, versioned set of Cases. The version is persisted with every run. */
data class Suite(
    val name: String,
    val version: String,
    val cases: List<Case>,
)

/**
 * A Target is *what you are evaluating*: a specific prompt version × model × params.
 * Two Targets differing in any of these are different things to compare — that identity
 * is the unit the regression detector (M5) reasons about.
 *
 * `promptTemplate` contains a `{{input}}` placeholder that the runner fills per-case.
 */
/**
 * Configures the LLM-as-judge scorer (M3). Kept separate from the subject `Target.params`:
 * the judge has its own model and its own temperature (0 — a judge that flip-flops on re-run
 * cannot be calibrated). `labelScores` maps each discrete label to a value in [0,1] so the M5
 * statistics treat the judge like any other metric; binary PASS/FAIL is the default.
 */
data class JudgeConfig(
    val model: String,
    val rubric: String,
    val labels: List<String> = listOf("PASS", "FAIL"),
    val labelScores: Map<String, Double> = mapOf("PASS" to 1.0, "FAIL" to 0.0),
    val temperature: Double = 0.0,
)

data class Target(
    val name: String,
    val promptVersion: String,
    val modelId: String,
    val promptTemplate: String,
    val params: ModelParams = ModelParams(),
    /** Which scorers to apply, by metric name (e.g. ["EXACT_MATCH", "JUDGE"]). */
    val scorers: List<String> = listOf("EXACT_MATCH"),
    /** Required when `scorers` contains JUDGE; ignored otherwise. */
    val judge: JudgeConfig? = null,
) {
    /** Renders the per-case prompt. The last line is the case input, by EchoModel convention. */
    fun renderPrompt(case: Case): String =
        promptTemplate.replace("{{input}}", case.input)
}
