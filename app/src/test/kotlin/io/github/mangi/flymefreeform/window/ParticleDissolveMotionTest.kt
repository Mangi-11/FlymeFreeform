package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleDissolveMotionTest {
    @Test
    fun sameSnapshotProducesSameParticles() {
        val first = sampleFilled(color = 0xFF3366CC.toInt())
        val second = sampleFilled(color = 0xFF3366CC.toInt())
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun colorComesFromTheSampledPixel() {
        val particles = sampleFilled(color = 0xFF3366CC.toInt())
        assertTrue(particles.all { it.color == 0xFF3366CC.toInt() })
    }

    @Test
    fun faintPixelsBelowThresholdAreSkipped() {
        // 遮罩只有 0.1 的透明（约 25），不应该变成粒子。
        val particles = sampleFilled(color = 0x1A000000)
        assertTrue(particles.isEmpty())
    }

    @Test
    fun transparentSnapshotProducesNothing() {
        assertTrue(sampleFilled(color = 0x00000000).isEmpty())
    }

    @Test
    fun particleTimeIsClampedToItsOwnWindow() {
        assertEquals(0f, ParticleDissolveMotion.particleTime(progress = 0.2f, delay = 0.4f), 0f)
        assertEquals(1f, ParticleDissolveMotion.particleTime(progress = 1f, delay = 0.4f), 0f)
        assertEquals(0.5f, ParticleDissolveMotion.particleTime(progress = 0.7f, delay = 0.4f), 0.0001f)
    }

    @Test
    fun motionEndpointsMatchTheReference() {
        assertEquals(0f, ParticleDissolveMotion.rise(0f), 0f)
        assertEquals(1f, ParticleDissolveMotion.rise(1f), 0f)
        assertEquals(1f, ParticleDissolveMotion.fade(0f), 0f)
        assertEquals(0f, ParticleDissolveMotion.fade(1f), 0f)
        assertEquals(1f, ParticleDissolveMotion.shrink(0f), 0f)
        assertEquals(0.62f, ParticleDissolveMotion.shrink(1f), 0.0001f)
    }

    private fun sampleFilled(color: Int): List<DissolveParticle> =
        ParticleDissolveMotion.sample(
            width = 40,
            height = 40,
            cellPx = 4,
            alphaThreshold = 40,
            pixelAt = { _, _ -> color },
        )
}