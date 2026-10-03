package com.janus.suite

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/**
 * Loads Suites and Targets from versioned YAML files. YAML (not code) is deliberate:
 * suites and targets live in git so a prompt change is a reviewable diff — the whole
 * premise of "CI for prompts".
 */
class SuiteLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule().apply {
        // Fail loudly on typo'd keys rather than silently dropping them — a mislabeled
        // field in a test suite must not pass silently.
        enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    fun loadSuite(path: String): Suite {
        val file = File(path)
        require(file.exists()) { "Suite file not found: $path" }
        val suite: Suite = mapper.readValue(file)
        require(suite.cases.isNotEmpty()) { "Suite '${suite.name}' has no cases" }
        val dupes = suite.cases.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys
        require(dupes.isEmpty()) { "Suite '${suite.name}' has duplicate case ids: $dupes" }
        return suite
    }

    fun loadTarget(path: String): Target {
        val file = File(path)
        require(file.exists()) { "Target file not found: $path" }
        return mapper.readValue(file)
    }
}
