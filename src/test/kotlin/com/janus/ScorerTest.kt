package com.janus

import com.janus.model.ModelResponse
import com.janus.score.ContainsScorer
import com.janus.score.ExactMatchScorer
import com.janus.score.JsonValidScorer
import com.janus.score.NumericToleranceScorer
import com.janus.suite.Case
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ScorerTest {
    private fun resp(t: String) = ModelResponse(text = t)

    @Test
    fun `exact match ignores case and whitespace`() = runTest {
        val c = Case(id = "1", input = "x", expected = "Paris")
        assertEquals(1.0, ExactMatchScorer().score(c, resp("  paris ")).value)
        assertEquals(0.0, ExactMatchScorer().score(c, resp("London")).value)
    }

    @Test
    fun `contains is substring case-insensitive`() = runTest {
        val c = Case(id = "1", input = "x", expected = "cat")
        assertEquals(1.0, ContainsScorer().score(c, resp("The CAT sat")).value)
        assertEquals(0.0, ContainsScorer().score(c, resp("dog")).value)
    }

    @Test
    fun `json valid detects malformed json`() = runTest {
        val c = Case(id = "1", input = "x")
        assertEquals(1.0, JsonValidScorer().score(c, resp("""{"a":1}""")).value)
        assertEquals(0.0, JsonValidScorer().score(c, resp("{a:1")).value)
    }

    @Test
    fun `numeric tolerance parses first number`() = runTest {
        val c = Case(id = "1", input = "x", expected = "4")
        assertEquals(1.0, NumericToleranceScorer().score(c, resp("The answer is 4.0")).value)
        assertEquals(0.0, NumericToleranceScorer().score(c, resp("The answer is 5")).value)
    }
}
