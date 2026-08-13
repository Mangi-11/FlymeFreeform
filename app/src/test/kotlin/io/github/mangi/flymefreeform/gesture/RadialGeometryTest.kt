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

    @Test
    fun gestureProgressIsMirroredAndIndependentOfCornerStart() {
        val left =
            RadialGeometry.gestureProgress(
                side = CornerSide.Left,
                originX = 4f,
                originY = 1992f,
                x = 64f,
                y = 1922f,
                inwardDeadZone = 20f,
                upwardDeadZone = 10f,
                revealDistance = 100f,
            )
        val shiftedLeft =
            RadialGeometry.gestureProgress(
                side = CornerSide.Left,
                originX = 30f,
                originY = 1980f,
                x = 90f,
                y = 1910f,
                inwardDeadZone = 20f,
                upwardDeadZone = 10f,
                revealDistance = 100f,
            )
        val right =
            RadialGeometry.gestureProgress(
                side = CornerSide.Right,
                originX = 996f,
                originY = 1992f,
                x = 936f,
                y = 1922f,
                inwardDeadZone = 20f,
                upwardDeadZone = 10f,
                revealDistance = 100f,
            )

        assertEquals(left, shiftedLeft, 0.0001f)
        assertEquals(left, right, 0.0001f)
    }

    @Test
    fun gestureProgressStartsAfterDeadZoneAndCanMoveBack() {
        val atActivation =
            RadialGeometry.gestureProgress(
                CornerSide.Left,
                0f,
                2000f,
                20f,
                1990f,
                20f,
                10f,
                100f,
            )
        val forward =
            RadialGeometry.gestureProgress(
                CornerSide.Left,
                0f,
                2000f,
                80f,
                1930f,
                20f,
                10f,
                100f,
            )
        val backward =
            RadialGeometry.gestureProgress(
                CornerSide.Left,
                0f,
                2000f,
                45f,
                1965f,
                20f,
                10f,
                100f,
            )

        assertEquals(0f, atActivation, 0f)
        assertTrue(forward > backward)
        assertTrue(backward > atActivation)
        assertEquals(
            1f,
            RadialGeometry.gestureProgress(
                CornerSide.Left,
                0f,
                2000f,
                500f,
                1500f,
                20f,
                10f,
                100f,
            ),
            0f,
        )
    }

    @Test
    fun itemMotionStaggersOpacityPositionAndScale() {
        val near = RadialItemMotion.sample(revealProgress = 0.4f, slot = 0)
        val far = RadialItemMotion.sample(revealProgress = 0.4f, slot = 4)
        val hidden = RadialItemMotion.sample(revealProgress = 0f, slot = 0)
        val complete = RadialItemMotion.sample(revealProgress = 1f, slot = 0)

        assertTrue(near.positionProgress > far.positionProgress)
        assertTrue(near.alpha > far.alpha)
        assertTrue(near.scale > far.scale)
        assertEquals(0f, hidden.positionProgress, 0f)
        assertEquals(0.55f, hidden.scale, 0f)
        assertEquals(0f, hidden.alpha, 0f)
        assertEquals(1f, complete.positionProgress, 0f)
        assertEquals(1f, complete.scale, 0f)
        assertEquals(1f, complete.alpha, 0f)
    }
}
