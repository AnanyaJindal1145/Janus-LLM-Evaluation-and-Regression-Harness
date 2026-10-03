package com.janus.score

import com.fasterxml.jackson.databind.ObjectMapper
import com.janus.model.Model
import com.janus.model.ModelParams
import com.janus.model.ModelRequest
import com.janus.suite.Case
import com.janus.suite.JudgeConfig
import com.janus.model.ModelResponse

/**
 * Renders the judge prompt: the rubric, the case (input + optional expected), the model
 * output under review, and a hard instruction to emit ONLY a JSON object {label, reasoning}.
 * Structured output is deliberate — it forces a parseable label AND captures the judge's
 * reasoning, which is what you read in M4 when the judge disagrees with a human label.
 */
internal fun renderJudgePrompt(cfg: JudgeConfig, case: Case, output: String): String = buildString {
    appendLine("You are an impartial evaluator. Grade the response against the rubric.")
    appendLine()
    appendLine("## Rubric")
    appendLine(cfg.rubric.trim())
    appendLine()
    appendLine("## Case input")
    appendLine(case.input.trim())
    if (case.expected != null) {
        appendLine()
        appendLine("## Reference / expected answer")
        appendLine(case.expected.trim())
    }
    appendLine()
    appendLine("## Response under review")
    appendLine(output.trim())
    appendLine()
    appendLine("## Instructions")
    appendLine("Choose exactly one label from: ${cfg.labels.joinToString(", ")}")
    appendLine("Respond with ONLY a JSON object, no markdown, no prose around it:")
    appendLine("""{"label": "<one of the labels>", "reasoning": "<one or two sentences>"}""")
}

/**
 * LLM-as-judge scorer. It is a [Scorer] like the deterministic ones — same interface, so the
 * M5 runner and the M2 persistence treat it identically. Its model is a *separate* judge model
 * (Gemini free tier in the $0 stack), called at temp=0 so grading is reproducible.
 */
class LlmJudgeScorer(
    private val judgeModel: Model,
    private val cfg: JudgeConfig,
) : Scorer {
    override val metric = Metric.JUDGE
    private val mapper = ObjectMapper()

    override suspend fun score(case: Case, response: ModelResponse): Score {
        val prompt = renderJudgePrompt(cfg, case, response.text)
        val judged = judgeModel.complete(
            ModelRequest(prompt, ModelParams(temperature = cfg.temperature)),
        )
        val parsed = parse(judged.text)
            ?: return Score(metric, 0.0, "judge returned unparseable output: ${judged.text.take(120)}")

        val value = cfg.labelScores[parsed.label]
        if (value == null) {
            return Score(metric, 0.0, "judge returned unknown label '${parsed.label}'")
        }
        // Persist the label AND the reasoning — auditability is the M4 credibility hook.
        return Score(metric, value, "${parsed.label}: ${parsed.reasoning}")
    }

    private data class Verdict(val label: String, val reasoning: String)

    /** Tolerant parse: accept a bare JSON object even if the model wrapped it in prose/markdown. */
    private fun parse(raw: String): Verdict? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val node = mapper.readTree(raw.substring(start, end + 1))
            val label = node.get("label")?.asText() ?: return null
            val reasoning = node.get("reasoning")?.asText() ?: ""
            Verdict(label.trim(), reasoning.trim())
        } catch (e: Exception) {
            null
        }
    }
}
