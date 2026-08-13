package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelRevealMotionTest {
    @Test
    fun growsFromSeedToFullExtent() {
        assertEquals(120f, PanelRevealMotion.extent(800f, 120f, 0f), 0f)
        assertEquals(460f, PanelRevealMotion.extent(800f, 120f, 0.5f), 0f)
        assertEquals(800f, PanelRevealMotion.extent(800f, 120f, 1f), 0f)
    }

    @Test
    fun anchorsRevealToRequestedBottomCorner() {
        assertEquals(0f, PanelRevealMotion.horizontalOffset(800f, 460f, anchorOnLeft = true), 0f)
        assertEquals(340f, PanelRevealMotion.horizontalOffset(800f, 460f, anchorOnLeft = false), 0f)
        assertEquals(540f, PanelRevealMotion.verticalOffset(1_200f, 660f), 0f)
    }

    @Test
    fun clampsProgressAndOversizedSeed() {
        assertEquals(100f, PanelRevealMotion.extent(100f, 200f, -1f), 0f)
        assertEquals(100f, PanelRevealMotion.extent(100f, 20f, 2f), 0f)
    }
}
