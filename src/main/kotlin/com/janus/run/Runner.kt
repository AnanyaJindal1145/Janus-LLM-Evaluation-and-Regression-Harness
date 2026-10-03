package com.janus.run

import com.janus.model.Model
import com.janus.model.ModelRequest
import com.janus.model.ProviderException
import com.janus.score.Scorer
import com.janus.suite.Case
import com.janus.suite.Suite
import com.janus.suite.Target
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * M5 concurrency substrate. Fans out (cases × N samples) independent model calls, but a
 * bounded [Semaphore] caps how many are in flight at once. The bound is the whole point:
 * it's backpressure that keeps us under provider rate limits (the real constraint on free
 * tiers) and protects against an accidental fan-out storm. Coroutines are cheap — the limit
 * isn't threads, it's outbound request rate — so the semaphore caps at the resource that
 * actually matters, independent of how many coroutines exist.
 *
 * `rng` is injected so tests get deterministic jitter; production passes the real one.
 */
class Runner(
    private val model: Model,
    private val scorers: List<Scorer>,
    private val maxConcurrency: Int = 4,
    private val maxAttempts: Int = 5,
    private val baseBackoffMs: Long = 200,
    private val maxBackoffMs: Long = 5_000,
    private val rng: Random = Random.Default,
) {
    private val gate = Semaphore(maxConcurrency)

    suspend fun run(
        suite: Suite,
        target: Target,
        samplesPerCase: Int,
        runId: String,
        createdAtEpochMs: Long,
    ): Run = coroutineScope {
        val results = suite.cases.map { case ->
            async {
                // Fan out N samples for this case; each goes through the semaphore so the
                // global in-flight cap holds across all cases, not per-case.
                val samples = (1..samplesPerCase).map {
                    async { sampleOnce(case, target) }
                }.awaitAll()
                CaseResult(caseId = case.id, samples = samples) // keep samples grouped under the case (M5.3 needs it)
            }
        }.awaitAll()

        Run(
            runId = runId,
            suiteName = suite.name,
            suiteVersion = suite.version,
            targetName = target.name,
            promptVersion = target.promptVersion,
            modelId = target.modelId,
            samplesPerCase = samplesPerCase,
            createdAtEpochMs = createdAtEpochMs,
            results = results,
        )
    }

    private suspend fun sampleOnce(case: Case, target: Target): Sample {
        val prompt = target.renderPrompt(case)
        val response = gate.withPermit { callWithRetry(prompt, target) }
        val scores = scorers.map { it.score(case, response) }
        return Sample(
            output = response.text,
            scores = scores,
            latencyMs = response.latencyMs,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
        )
    }

    /**
     * Retry with exponential backoff + full jitter. Jitter matters: without it, every worker
     * that hits a 429 at the same instant backs off in lockstep and retries in lockstep — a
     * synchronized stampede that re-triggers the rate limit. Full jitter (random in [0, base))
     * de-correlates them. Non-retryable failures (non-429 4xx) fail fast — retrying a malformed
     * or unauthorized request just burns rate-limit budget.
     */
    private suspend fun callWithRetry(prompt: String, target: Target): com.janus.model.ModelResponse {
        var attempt = 0
        while (true) {
            try {
                return model.complete(ModelRequest(prompt, target.params))
            } catch (e: ProviderException) {
                attempt++
                if (!e.isRetryable || attempt >= maxAttempts) throw e
                val exp = minOf(baseBackoffMs * (1L shl (attempt - 1)), maxBackoffMs)
                val jittered = rng.nextLong(0, exp + 1)
                delay(jittered)
            }
        }
    }
}
