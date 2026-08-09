package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.config.ModulePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class RadialIconStyleTest {
    @Test
    fun defaultsKeepTheMaskAtPlateSizeAndEnlargeContent() {
        val style =
            RadialIconStyle.fromPercent(
                circularEnabled = true,
                contentScalePercent = ModulePreferences.DEFAULT_RADIAL_ICON_CONTENT_SCALE_PERCENT,
                maskScalePercent = ModulePreferences.DEFAULT_RADIAL_ICON_MASK_SCALE_PERCENT,
            )

        assertEquals(100f, style.maskDiameter(100f), 0.001f)
        assertEquals(120f, style.contentDiameter(100f), 0.001f)
    }

    @Test
    fun maskAndContentScalesComposeIndependently() {
        val style =
            RadialIconStyle.fromPercent(
                circularEnabled = true,
                contentScalePercent = 120,
                maskScalePercent = 80,
            )

        assertEquals(80f, style.maskDiameter(100f), 0.001f)
        assertEquals(96f, style.contentDiameter(100f), 0.001f)
    }

    @Test
    fun percentagesAreClampedBeforeTheyReachDrawing() {
        val style =
            RadialIconStyle.fromPercent(
                circularEnabled = true,
                contentScalePercent = 20,
                maskScalePercent = 200,
            )

        assertEquals(120f, style.maskDiameter(100f), 0.001f)
        assertEquals(96f, style.contentDiameter(100f), 0.001f)
    }

    @Test
    fun circularRenderingCanBeDisabledWithoutChangingStoredScales() {
        val style =
            RadialIconStyle.fromPercent(
                circularEnabled = false,
                contentScalePercent = 120,
                maskScalePercent = 100,
            )

        assertEquals(false, style.circularEnabled)
        assertEquals(100f, style.maskDiameter(100f), 0.001f)
        assertEquals(120f, style.contentDiameter(100f), 0.001f)
    }
}
