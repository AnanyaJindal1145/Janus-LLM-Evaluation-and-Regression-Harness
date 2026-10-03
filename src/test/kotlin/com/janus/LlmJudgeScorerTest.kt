package com.janus

import com.janus.model.FakeJudgeModel
import com.janus.model.Model
import com.janus.model.ModelRequest
import com.janus.model.ModelResponse
import com.janus.score.LlmJudgeScorer
import com.janus.score.Metric
import com.janus.suite.Case
import com.janus.suite.JudgeConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmJudgeScorerTest {

    private val cfg = JudgeConfig(
        model = "fake-judge",
        rubric = "PASS if the response matches the expected answer.",
    )

    @Test
    fun `fake judge passes a correct answer and persists reasoning`() = runTest {
        val scorer = LlmJudgeScorer(FakeJudgeModel(), cfg)
        val case = Case(id = "1", input = "Capital of France?", expected = "Paris")
        val score = scorer.score(case, ModelResponse(text = "Paris"))
        assertEquals(Metric.JUDGE, score.metric)
        assertEquals(1.0, score.value)
        assertTrue(score.detail!!.startsWith("PASS"))
    }

    @Test
    fun `fake judge fails a wrong answer`() = runTest {
        val scorer = LlmJudgeScorer(FakeJudgeModel(), cfg)
        val case = Case(id = "1", input = "Capital of France?", expected = "Paris")
        val score = scorer.score(case, ModelResponse(text = "London"))
        assertEquals(0.0, score.value)
        assertTrue(score.detail!!.startsWith("FAIL"))
    }

    @Test
    fun `unparseable judge output scores zero with a diagnostic`() = runTest {
        val garbage = object : Model {
            override val id = "garbage"
            override suspend fun complete(request: ModelRequest) =
                ModelResponse(text = "I cannot produce JSON today, sorry.")
        }
        val scorer = LlmJudgeScorer(garbage, cfg)
        val score = scorer.score(Case(id = "1", input = "x", expected = "y"), ModelResponse(text = "z"))
        assertEquals(0.0, score.value)
        assertTrue(score.detail!!.contains("unparseable"))
    }

    @Test
    fun `unknown label scores zero`() = runTest {
        val weird = object : Model {
            override val id = "weird"
            override suspend fun complete(request: ModelRequest) =
                ModelResponse(text = """{"label": "MAYBE", "reasoning": "unsure"}""")
        }
        val scorer = LlmJudgeScorer(weird, cfg)
        val score = scorer.score(Case(id = "1", input = "x", expected = "y"), ModelResponse(text = "z"))
        assertEquals(0.0, score.value)
        assertTrue(score.detail!!.contains("unknown label"))
    }

    @Test
    fun `parses a json object wrapped in prose or markdown`() = runTest {
        val chatty = object : Model {
            override val id = "chatty"
            override suspend fun complete(request: ModelRequest) = ModelResponse(
                text = "Sure! Here is my verdict:\n```json\n{\"label\": \"PASS\", \"reasoning\": \"ok\"}\n```",
            )
        }
        val scorer = LlmJudgeScorer(chatty, cfg)
        val score = scorer.score(Case(id = "1", input = "x", expected = "y"), ModelResponse(text = "z"))
        assertEquals(1.0, score.value)
    }
}
