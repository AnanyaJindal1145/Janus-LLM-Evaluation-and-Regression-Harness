package com.janus.store

import com.janus.run.CaseResult
import com.janus.run.Run
import com.janus.run.Sample
import com.janus.score.Metric
import com.janus.score.Score
import java.sql.Connection
import java.sql.DriverManager

/**
 * Persists runs to an embedded SQLite database. SQLite (not a server DB) is the right fit for a
 * single-user CLI persisting history for comparison: real SQL for the compare queries, zero ops,
 * one portable file. (The design doc notes Postgres as the migration if this ever went multi-user;
 * the RunStore seam makes that a swap, not a rewrite.)
 *
 * Schema (sample-grained, since M5 keeps N samples per case):
 *   runs     — one row per run (identity + metadata)
 *   samples  — one row per (run, case, sample_index); output + latency + tokens
 *   scores   — one row per (run, case, sample_index, metric); the grain stats read
 */
class RunStore(private val dbPath: String) : AutoCloseable {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").also {
        it.createStatement().use { st -> st.execute("PRAGMA foreign_keys = ON") }
        initSchema(it)
    }

    private fun initSchema(c: Connection) {
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS runs (
                    run_id           TEXT PRIMARY KEY,
                    suite_name       TEXT NOT NULL,
                    suite_version    TEXT NOT NULL,
                    target_name      TEXT NOT NULL,
                    prompt_version   TEXT NOT NULL,
                    model_id         TEXT NOT NULL,
                    samples_per_case INTEGER NOT NULL,
                    created_at       INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS samples (
                    run_id        TEXT NOT NULL,
                    case_id       TEXT NOT NULL,
                    sample_index  INTEGER NOT NULL,
                    output        TEXT NOT NULL,
                    latency_ms    INTEGER,
                    input_tokens  INTEGER,
                    output_tokens INTEGER,
                    PRIMARY KEY (run_id, case_id, sample_index),
                    FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS scores (
                    run_id       TEXT NOT NULL,
                    case_id      TEXT NOT NULL,
                    sample_index INTEGER NOT NULL,
                    metric       TEXT NOT NULL,
                    value        REAL NOT NULL,
                    detail       TEXT,
                    FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_scores_run_metric ON scores(run_id, metric)")
        }
    }

    fun save(run: Run) {
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO runs(run_id,suite_name,suite_version,target_name,prompt_version,model_id,samples_per_case,created_at) VALUES(?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, run.runId)
                ps.setString(2, run.suiteName)
                ps.setString(3, run.suiteVersion)
                ps.setString(4, run.targetName)
                ps.setString(5, run.promptVersion)
                ps.setString(6, run.modelId)
                ps.setInt(7, run.samplesPerCase)
                ps.setLong(8, run.createdAtEpochMs)
                ps.executeUpdate()
            }
            for (cr in run.results) {
                cr.samples.forEachIndexed { idx, s ->
                    conn.prepareStatement(
                        "INSERT INTO samples(run_id,case_id,sample_index,output,latency_ms,input_tokens,output_tokens) VALUES(?,?,?,?,?,?,?)"
                    ).use { ps ->
                        ps.setString(1, run.runId)
                        ps.setString(2, cr.caseId)
                        ps.setInt(3, idx)
                        ps.setString(4, s.output)
                        if (s.latencyMs != null) ps.setLong(5, s.latencyMs) else ps.setNull(5, java.sql.Types.INTEGER)
                        if (s.inputTokens != null) ps.setInt(6, s.inputTokens) else ps.setNull(6, java.sql.Types.INTEGER)
                        if (s.outputTokens != null) ps.setInt(7, s.outputTokens) else ps.setNull(7, java.sql.Types.INTEGER)
                        ps.executeUpdate()
                    }
                    for (sc in s.scores) {
                        conn.prepareStatement(
                            "INSERT INTO scores(run_id,case_id,sample_index,metric,value,detail) VALUES(?,?,?,?,?,?)"
                        ).use { ps ->
                            ps.setString(1, run.runId)
                            ps.setString(2, cr.caseId)
                            ps.setInt(3, idx)
                            ps.setString(4, sc.metric.name)
                            ps.setDouble(5, sc.value)
                            ps.setString(6, sc.detail)
                            ps.executeUpdate()
                        }
                    }
                }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Rehydrates a full Run (with all samples + scores) for the comparator. */
    fun loadRun(runId: String): Run {
        val meta = conn.prepareStatement("SELECT * FROM runs WHERE run_id=?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                require(rs.next()) { "run '$runId' not found" }
                RunMeta(
                    rs.getString("suite_name"), rs.getString("suite_version"),
                    rs.getString("target_name"), rs.getString("prompt_version"),
                    rs.getString("model_id"), rs.getInt("samples_per_case"), rs.getLong("created_at"),
                )
            }
        }

        // scores keyed by (case, sample_index)
        val scoreKey = HashMap<Pair<String, Int>, MutableList<Score>>()
        conn.prepareStatement("SELECT case_id,sample_index,metric,value,detail FROM scores WHERE run_id=?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val k = rs.getString("case_id") to rs.getInt("sample_index")
                    scoreKey.getOrPut(k) { mutableListOf() }
                        .add(Score(Metric.valueOf(rs.getString("metric")), rs.getDouble("value"), rs.getString("detail")))
                }
            }
        }

        // samples grouped into cases, preserving sample order
        val samplesByCase = LinkedHashMap<String, MutableList<Pair<Int, Sample>>>()
        conn.prepareStatement("SELECT case_id,sample_index,output,latency_ms,input_tokens,output_tokens FROM samples WHERE run_id=? ORDER BY case_id,sample_index").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val caseId = rs.getString("case_id")
                    val idx = rs.getInt("sample_index")
                    val sample = Sample(
                        output = rs.getString("output"),
                        scores = scoreKey[caseId to idx] ?: emptyList(),
                        latencyMs = rs.getObject("latency_ms")?.let { (it as Number).toLong() },
                        inputTokens = rs.getObject("input_tokens")?.let { (it as Number).toInt() },
                        outputTokens = rs.getObject("output_tokens")?.let { (it as Number).toInt() },
                    )
                    samplesByCase.getOrPut(caseId) { mutableListOf() }.add(idx to sample)
                }
            }
        }

        val results = samplesByCase.map { (caseId, pairs) ->
            CaseResult(caseId, pairs.sortedBy { it.first }.map { it.second })
        }
        return Run(
            runId, meta.suiteName, meta.suiteVersion, meta.targetName,
            meta.promptVersion, meta.modelId, meta.samplesPerCase, meta.createdAt, results,
        )
    }

    private data class RunMeta(
        val suiteName: String, val suiteVersion: String, val targetName: String,
        val promptVersion: String, val modelId: String, val samplesPerCase: Int, val createdAt: Long,
    )

    override fun close() = conn.close()
}
