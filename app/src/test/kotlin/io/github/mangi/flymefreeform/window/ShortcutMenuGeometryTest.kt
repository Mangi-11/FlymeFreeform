package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.GesturePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutMenuGeometryTest {
    @Test
    fun menuOpensInsideBothCornersAndStaysOnScreen() {
        for (side in CornerSide.entries) {
            val icon = GesturePoint(if (side == CornerSide.Left) 100f else 300f, 760f)
            val bounds = ShortcutMenuGeometry.place(icon, side, 400f, 800f, 1f, 4, 48f)
            assertTrue(bounds.left >= 12f)
            assertTrue(bounds.right <= 388f)
            assertTrue(bounds.top >= 12f)
            assertTrue(bounds.bottom <= 788f)
            if (side == CornerSide.Left) assertTrue(bounds.left > icon.x)
            else assertTrue(bounds.right < icon.x)
            for (row in 0..3) {
                assertEquals(row, bounds.rowAt(bounds.left + 20f, bounds.top + (row + 0.5f) * bounds.rowHeight))
            }
            assertNull(bounds.rowAt(bounds.right + 1f, bounds.top + 5f))
            assertNull(bounds.rowAt(bounds.left + 5f, bounds.bottom))
        }
    }

    @Test
    fun narrowLandscapeWindowClampsMenuWidth() {
        val bounds = ShortcutMenuGeometry.place(GesturePoint(40f, 80f), CornerSide.Left,
            180f, 250f, 1f, 4, 48f)
        assertEquals(156f, bounds.width, 0f)
        assertTrue(bounds.bottom <= 238f)
    }

    @Test
    fun menuSwitchesSidesWhenAnInnerRingIconHasNoRoom() {
        val rightCorner = ShortcutMenuGeometry.place(GesturePoint(80f, 300f), CornerSide.Right,
            400f, 800f, 1f, 3, 48f)
        assertTrue(rightCorner.left > 80f)
        val leftCorner = ShortcutMenuGeometry.place(GesturePoint(320f, 300f), CornerSide.Left,
            400f, 800f, 1f, 3, 48f)
        assertTrue(leftCorner.right < 320f)
    }
}
