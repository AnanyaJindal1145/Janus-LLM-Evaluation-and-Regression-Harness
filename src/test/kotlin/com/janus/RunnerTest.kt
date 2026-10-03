package com.janus

import com.janus.model.Model
import com.janus.model.ModelRequest
import com.janus.model.ModelResponse
import com.janus.model.ProviderException
import com.janus.run.Runner
import com.janus.score.ContainsScorer
import com.janus.suite.Case
import com.janus.suite.Suite
import com.janus.suite.Target
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunnerTest {

    private val suite = Suite("s", "1", listOf(Case("a", "Paris", expected = "Paris")))
    private val target = Target("t", "1", "echo", "{{input}}")

    @Test
    fun `runs N samples per case`() = runTest {
        val model = object : Model {
            override val id = "m"
            override suspend fun complete(request: ModelRequest) = ModelResponse("Paris", latencyMs = 1)
        }
        val runner = Runner(model, listOf(ContainsScorer()), rng = Random(0))
        val run = runner.run(suite, target, samplesPerCase = 4, runId = "r", createdAtEpochMs = 0L)
        assertEquals(4, run.results.single().samples.size)
        assertEquals(1.0, run.results.single().perCaseScore(com.janus.score.Metric.CONTAINS))
    }

    @Test
    fun `retries a retryable failure then succeeds`() = runTest {
        val calls = AtomicInteger(0)
        val model = object : Model {
            override val id = "flaky"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                return if (calls.getAndIncrement() < 2) throw ProviderException(429, "throttled")
                else ModelResponse("Paris")
            }
        }
        // rng fixed so jitter delays are deterministic/zero-ish under runTest's virtual clock
        val runner = Runner(model, listOf(ContainsScorer()), rng = Random(0))
        val run = runner.run(suite, target, samplesPerCase = 1, runId = "r", createdAtEpochMs = 0L)
        assertTrue(calls.get() >= 3, "should have retried twice then succeeded")
        assertEquals(1.0, run.results.single().perCaseScore(com.janus.score.Metric.CONTAINS))
    }

    @Test
    fun `does not retry a non-retryable failure`() = runTest {
        val calls = AtomicInteger(0)
        val model = object : Model {
            override val id = "bad"
            override suspend fun complete(request: ModelRequest): ModelResponse {
                calls.incrementAndGet()
                throw ProviderException(400, "bad request") // non-retryable
            }
        }
        val runner = Runner(model, listOf(ContainsScorer()), rng = Random(0))
        assertFailsWith<ProviderException> {
            runner.run(suite, target, samplesPerCase = 1, runId = "r", createdAtEpochMs = 0L)
        }
        assertEquals(1, calls.get(), "non-retryable 4xx should fail fast, no retries")
    }
}
