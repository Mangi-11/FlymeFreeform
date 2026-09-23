package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerStreamGateTest {
    @Test
    fun ignoredStreamSkipsContinuationsButNotNextDown() {
        val gate = PointerStreamGate()
        assertFalse(gate.shouldForward(gate.current()))

        val first = gate.begin()
        assertTrue(gate.shouldForward(first))
        gate.ignore(first)
        assertFalse(gate.shouldForward(first))

        val second = gate.begin()
        assertTrue(gate.shouldForward(second))
        gate.ignore(first)
        assertTrue(gate.shouldForward(second))
    }

    @Test
    fun resetInvalidatesCurrentStream() {
        val gate = PointerStreamGate()
        val stream = gate.begin()
        gate.reset()
        assertFalse(gate.shouldForward(gate.current()))
        assertTrue(gate.shouldForward(gate.begin()))
        gate.ignore(stream)
        assertTrue(gate.shouldForward(gate.current()))
    }
}
