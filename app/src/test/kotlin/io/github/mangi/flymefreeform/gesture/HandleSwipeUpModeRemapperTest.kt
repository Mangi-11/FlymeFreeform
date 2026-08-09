package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class HandleSwipeUpModeRemapperTest {
    @Test
    fun remapsOrdinaryBottomHandleSwipeUpToNativeMiniMode() {
        assertEquals(
            4,
            HandleSwipeUpModeRemapper.remap(
                enabled = true,
                actionFlag = 10,
                originalMode = 0,
                flexibleState = 1,
            ),
        )
    }

    @Test
    fun preservesOriginalModeWhenDisabled() {
        assertEquals(
            0,
            HandleSwipeUpModeRemapper.remap(
                enabled = false,
                actionFlag = 10,
                originalMode = 0,
                flexibleState = 1,
            ),
        )
    }

    @Test
    fun preservesNonBottomNonSwipeUpAndNonOrdinaryModes() {
        assertEquals(0, HandleSwipeUpModeRemapper.remap(true, 1, 0, 1))
        assertEquals(1, HandleSwipeUpModeRemapper.remap(true, 10, 1, 1))
        assertEquals(0, HandleSwipeUpModeRemapper.remap(true, 10, 0, 2))
        assertEquals(-1, HandleSwipeUpModeRemapper.remap(true, 10, -1, 1))
    }
}
