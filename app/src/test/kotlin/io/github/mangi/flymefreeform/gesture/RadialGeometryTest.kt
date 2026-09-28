package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialGeometryTest {
    @Test
    fun hiddenMenuCannotSelectAnItemAtTheCollapsedOrigin() {
        val layout = RadialGeometry.layout(CornerSide.Left, 400f, 890f, 0f, 7)
        assertNull(RadialGeometry.selection(layout, 0f, 890f, 0, 0f, 0f))
    }

    @Test
    fun sevenItemsUseCenteredTwelveDegreeSlots() {
        val layout = RadialGeometry.layout(CornerSide.Left, 400f, 890f, 242f, 7)
        val degrees = layout.itemCenters.map { RadialGeometry.polarAngle(layout, it) * 180f / kotlin.math.PI.toFloat() }
        degrees.zip(listOf(-21f, -33f, -45f, -57f, -69f, -81f, -9f)).forEach { (actual, expected) ->
            assertEquals(expected, actual, 0.001f)
        }
        val onlyMore = RadialGeometry.layout(CornerSide.Left, 400f, 890f, 242f, 1)
        assertEquals(-45f, RadialGeometry.polarAngle(onlyMore, onlyMore.itemCenters.single()) * 180f / kotlin.math.PI.toFloat(), 0.001f)
    }

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
    fun hoverIntensityPeaksOnTheNearestItemAndFadesWithDistance() {
        val layout = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        val target = layout.itemCenters[2]
        val onTarget = RadialGeometry.hoverIntensities(layout, target.x, target.y, 88f)
        assertEquals(1f, onTarget[2], 0.001f)
        // 手指压在目标上时，邻居只被轻微点亮，远处条目几乎为零。
        assertTrue(onTarget[1] > 0f && onTarget[1] < 0.6f)
        assertTrue(onTarget[5] < 0.05f)

        // 手指移到两项之间：两边都被点亮，且都明显高于远端条目。
        val between = GesturePoint((target.x + layout.itemCenters[1].x) / 2f, (target.y + layout.itemCenters[1].y) / 2f)
        val midpoint = RadialGeometry.hoverIntensities(layout, between.x, between.y, 88f)
        assertTrue(midpoint[1] > 0.2f && midpoint[2] > 0.2f)
        assertTrue(midpoint[1] < 1f && midpoint[2] < 1f)
    }

    @Test
    fun hoverIntensityIsMonotonicAsTheFingerApproaches() {
        val layout = RadialGeometry.layout(CornerSide.Left, 1000f, 2000f, 400f, 7)
        val target = layout.itemCenters[3]
        val far = RadialGeometry.hoverIntensities(layout, target.x + 160f, target.y, 88f)[3]
        val near = RadialGeometry.hoverIntensities(layout, target.x + 40f, target.y, 88f)[3]
        val on = RadialGeometry.hoverIntensities(layout, target.x, target.y, 88f)[3]
        assertTrue(far < near)
        assertTrue(near < on)
    }

    @Test
    fun hoverIntensitiesAreEmptyWhenThereAreNoItems() {
        val layout = RadialGeometry.layout(CornerSide.Left, 400f, 890f, 242f, 0)
        assertTrue(RadialGeometry.hoverIntensities(layout, 0f, 890f, 88f).isEmpty())
    }
}
