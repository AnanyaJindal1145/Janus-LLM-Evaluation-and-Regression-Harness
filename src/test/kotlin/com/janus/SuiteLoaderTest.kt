package com.janus

import com.janus.model.EchoModel
import com.janus.model.ModelRequest
import com.janus.model.ModelParams
import com.janus.suite.SuiteLoader
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SuiteLoaderTest {

    private fun tempYaml(content: String): String {
        val f = File.createTempFile("janus-test", ".yaml")
        f.deleteOnExit()
        f.writeText(content)
        return f.absolutePath
    }

    @Test
    fun `loads a valid suite`() {
        val path = tempYaml(
            """
            name: t
            version: "1"
            cases:
              - id: a
                input: "hello"
                expected: "hello"
            """.trimIndent()
        )
        val suite = SuiteLoader().loadSuite(path)
        assertEquals("t", suite.name)
        assertEquals(1, suite.cases.size)
        assertEquals("a", suite.cases[0].id)
    }

    @Test
    fun `rejects duplicate case ids`() {
        val path = tempYaml(
            """
            name: t
            version: "1"
            cases:
              - id: dup
                input: "x"
              - id: dup
                input: "y"
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { SuiteLoader().loadSuite(path) }
        assertTrue(ex.message!!.contains("duplicate"))
    }

    @Test
    fun `rejects empty suite`() {
        val path = tempYaml("name: t\nversion: \"1\"\ncases: []\n")
        assertFailsWith<IllegalArgumentException> { SuiteLoader().loadSuite(path) }
    }

    @Test
    fun `echo model returns the last non-blank line`() = runTest {
        val resp = EchoModel().complete(ModelRequest("Answer concisely.\nParis", ModelParams()))
        assertEquals("Paris", resp.text)
    }
}
