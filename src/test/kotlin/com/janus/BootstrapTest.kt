package com.janus

import com.janus.compare.Bootstrap
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class BootstrapTest {

    @Test
    fun `identical scores give a CI tight around zero`() {
        val s = listOf(1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val ci = Bootstrap.deltaCI(s, s)
        assertEquals(0.0, ci.point, 1e-9)
        assertTrue(ci.lo <= 0.0 && ci.hi >= 0.0, "CI should contain zero for identical runs: $ci")
    }

    @Test
    fun `a clear uniform drop gives a CI entirely below zero`() {
        val old = List(20) { 1.0 }
        val new = List(20) { 0.6 }   // every case worse
        val ci = Bootstrap.deltaCI(old, new)
        assertTrue(ci.hi < 0.0, "CI should be entirely below zero for a real regression: $ci")
        assertEquals(-0.4, ci.point, 1e-9)
    }

    @Test
    fun `bootstrap is deterministic across runs with same seed`() {
        val old = listOf(1.0, 0.0, 1.0, 0.5, 0.0, 1.0, 0.0, 0.5)
        val new = listOf(0.5, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val a = Bootstrap.deltaCI(old, new)
        val b = Bootstrap.deltaCI(old, new)
        assertEquals(a.lo, b.lo, 1e-12)
        assertEquals(a.hi, b.hi, 1e-12)
    }
}
