package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalDampedSpringTest {
    @Test
    fun convergesWithoutOvershootAndCanBeRetargeted() {
        val spring = CriticalDampedSpring()
        spring.retarget(1f)
        repeat(20) { spring.step(1f / 60f) }
        val presentation = spring.value
        assertTrue(presentation in 0f..1f)
        spring.retarget(0.25f)
        repeat(180) { spring.step(1f / 60f) }
        assertEquals(0.25f, spring.value, 0.001f)
        assertTrue(spring.isAtRest)
    }
}
