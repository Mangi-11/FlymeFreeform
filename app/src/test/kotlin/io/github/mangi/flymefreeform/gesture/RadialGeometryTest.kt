package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialGeometryTest {
    @Test
    fun leftAndRightLayoutsAreExactMirrors() {
        val left = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        val right = RadialGeometry.layout(CornerSide.Right, 1000f, 2000f, 400f, 7)
        left.itemCenters.zip(right.itemCenters).forEach { (l, r) ->
            assertEquals(1000f, l.x + r.x, 0.001f)
            assertEquals(l.y, r.y, 0.001f)
        }
    }

    @Test
    fun previousSelectionUsesLargerKeepRadius() {
        val layout = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        val center = layout.itemCenters[2]
        assertEquals(2, RadialGeometry.selection(layout, center.x + 39f, center.y, 2, 30f, 42f))
        assertNull(RadialGeometry.selection(layout, center.x + 45f, center.y, 2, 30f, 42f))
    }

    @Test
    fun appCentersStayInsideScreenAndMoreRemainsClosestToCorner() {
        val width = 1000f
        val height = 2000f
        val left = RadialGeometry.layout(CornerSide.Left, width, height, 400f, 7)
        val right = RadialGeometry.layout(CornerSide.Right, width, height, 400f, 7)

        assertTrue(left.itemCenters.dropLast(1).all { center -> center.x > 0f })
        assertTrue(right.itemCenters.dropLast(1).all { center -> center.x < width })
        assertTrue(left.itemCenters.last().y > left.itemCenters.dropLast(1).maxOf { it.y })
        assertTrue(right.itemCenters.last().y > right.itemCenters.dropLast(1).maxOf { it.y })
    }
}
