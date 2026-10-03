package com.janus.score

import com.fasterxml.jackson.databind.ObjectMapper
import com.janus.model.ModelResponse
import com.janus.suite.Case

/** 1.0 iff the (optionally normalized) output equals the case's `expected` exactly. */
class ExactMatchScorer(private val ignoreCaseAndSpace: Boolean = true) : Scorer {
    override val metric = Metric.EXACT_MATCH

    override suspend fun score(case: Case, response: ModelResponse): Score {
        val expected = case.expected
            ?: return Score(metric, 0.0, "no `expected` on case")
        val a = normalize(response.text)
        val b = normalize(expected)
        val pass = a == b
        return Score(metric, if (pass) 1.0 else 0.0, if (pass) "match" else "expected '$b', got '$a'")
    }

    private fun normalize(s: String) =
        if (ignoreCaseAndSpace) s.trim().lowercase() else s
}

/** 1.0 iff the output matches `case.expected` interpreted as a regex. */
class RegexMatchScorer : Scorer {
    override val metric = Metric.REGEX_MATCH

    override suspend fun score(case: Case, response: ModelResponse): Score {
        val pattern = case.expected
            ?: return Score(metric, 0.0, "no `expected` (regex) on case")
        return try {
            val pass = Regex(pattern).containsMatchIn(response.text)
            Score(metric, if (pass) 1.0 else 0.0, if (pass) "matched /$pattern/" else "no match for /$pattern/")
        } catch (e: Exception) {
            Score(metric, 0.0, "invalid regex /$pattern/: ${e.message}")
        }
    }
}

/** 1.0 iff the output contains `case.expected` as a substring (case-insensitive). */
class ContainsScorer : Scorer {
    override val metric = Metric.CONTAINS

    override suspend fun score(case: Case, response: ModelResponse): Score {
        val needle = case.expected
            ?: return Score(metric, 0.0, "no `expected` on case")
        val pass = response.text.contains(needle, ignoreCase = true)
        return Score(metric, if (pass) 1.0 else 0.0, if (pass) "contains '$needle'" else "missing '$needle'")
    }
}

/** 1.0 iff the output is syntactically valid JSON. Independent of `expected`. */
class JsonValidScorer : Scorer {
    override val metric = Metric.JSON_VALID
    private val mapper = ObjectMapper()

    override suspend fun score(case: Case, response: ModelResponse): Score {
        return try {
            mapper.readTree(response.text)
            Score(metric, 1.0, "valid JSON")
        } catch (e: Exception) {
            Score(metric, 0.0, "invalid JSON: ${e.message?.take(80)}")
        }
    }
}

/**
 * 1.0 iff the numeric value parsed from the output is within `tolerance` of the numeric
 * `expected`. Handles the common "answer is a number, formatting varies" case that exact
 * match is too brittle for.
 */
class NumericToleranceScorer(private val tolerance: Double = 1e-6) : Scorer {
    override val metric = Metric.NUMERIC_TOLERANCE
    private val numberRegex = Regex("-?\\d+(?:\\.\\d+)?")

    override suspend fun score(case: Case, response: ModelResponse): Score {
        val expected = case.expected?.let { firstNumber(it) }
            ?: return Score(metric, 0.0, "no numeric `expected` on case")
        val actual = firstNumber(response.text)
            ?: return Score(metric, 0.0, "no number found in output")
        val pass = kotlin.math.abs(actual - expected) <= tolerance
        return Score(metric, if (pass) 1.0 else 0.0, "expected≈$expected got $actual (tol=$tolerance)")
    }

    private fun firstNumber(s: String): Double? =
        numberRegex.find(s)?.value?.toDoubleOrNull()
}
