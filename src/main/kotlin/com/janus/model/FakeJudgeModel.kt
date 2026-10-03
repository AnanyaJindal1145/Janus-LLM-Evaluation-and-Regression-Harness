package com.janus.model

/**
 * An offline, deterministic stand-in for a judge model, so M3 runs end to end at $0 with no
 * API key. It reads the rendered judge prompt (which contains the case's expected answer and
 * the response under review) and emits the {label, reasoning} JSON the real judge would —
 * PASS when the response contains the expected answer, FAIL otherwise.
 *
 * This is NOT the calibration judge (that's Gemini in M4). It exists purely to exercise the
 * LlmJudgeScorer parse/score/persist path deterministically in tests and demos.
 */
class FakeJudgeModel : Model {
    override val id: String = "fake-judge"

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val prompt = request.prompt
        val expected = section(prompt, "## Reference / expected answer")
        val response = section(prompt, "## Response under review")

        val pass = expected != null &&
            response != null &&
            response.contains(expected, ignoreCase = true)

        val label = if (pass) "PASS" else "FAIL"
        val reasoning = if (pass) {
            "response contains the expected answer"
        } else {
            "response does not match the expected answer"
        }
        val json = """{"label": "$label", "reasoning": "$reasoning"}"""
        return ModelResponse(text = json, latencyMs = 0)
    }

    /** Extracts the text under a `## Heading` up to the next `## ` heading or end of prompt. */
    private fun section(prompt: String, heading: String): String? {
        val start = prompt.indexOf(heading)
        if (start < 0) return null
        val bodyStart = start + heading.length
        val next = prompt.indexOf("\n## ", bodyStart)
        val end = if (next < 0) prompt.length else next
        return prompt.substring(bodyStart, end).trim().ifBlank { null }
    }
}
