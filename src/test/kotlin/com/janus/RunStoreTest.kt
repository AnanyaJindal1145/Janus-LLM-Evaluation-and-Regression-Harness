package com.janus

import com.janus.run.CaseResult
import com.janus.run.Run
import com.janus.run.Sample
import com.janus.score.Metric
import com.janus.score.Score
import com.janus.store.RunStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RunStoreTest {

    private fun tempDb(): String {
        val f = File.createTempFile("janus-store", ".db")
        f.delete()
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun `save then load round-trips samples and scores`() {
        val run = Run(
            runId = "run-1",
            suiteName = "s", suiteVersion = "1",
            targetName = "t", promptVersion = "1", modelId = "echo",
            samplesPerCase = 2, createdAtEpochMs = 123L,
            results = listOf(
                CaseResult(
                    caseId = "a",
                    samples = listOf(
                        Sample("Paris", listOf(Score(Metric.EXACT_MATCH, 1.0)), latencyMs = 5, inputTokens = 10, outputTokens = 2),
                        Sample("paris", listOf(Score(Metric.EXACT_MATCH, 1.0)), latencyMs = 7),
                    ),
                ),
                CaseResult(
                    caseId = "b",
                    samples = listOf(
                        Sample("wrong", listOf(Score(Metric.EXACT_MATCH, 0.0))),
                    ),
                ),
            ),
        )

        val db = tempDb()
        RunStore(db).use { it.save(run) }

        RunStore(db).use { store ->
            val loaded = store.loadRun("run-1")
            assertEquals(2, loaded.results.size)
            assertEquals("t", loaded.targetName)
            val a = loaded.results.first { it.caseId == "a" }
            assertEquals(2, a.samples.size)
            assertEquals(1.0, a.perCaseScore(Metric.EXACT_MATCH))
            assertEquals(0.0, loaded.results.first { it.caseId == "b" }.perCaseScore(Metric.EXACT_MATCH))
            assertEquals(5L, a.samples.first().latencyMs)
        }
    }
}
