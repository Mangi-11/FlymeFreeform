package io.github.mangi.flymefreeform.platform.coloros

import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogSnapshotTest {
    @Test
    fun lateCatalogCannotReplaceNewerSelection() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested))
        assertFalse(completed.matches(requested.copy(pinsSaved = true)))
    }

    @Test
    fun geometryChangesInvalidatePreparedCatalogCapacity() {
        val settings = ModuleSettingsSnapshot()
        val snapshot = AppCatalogSnapshot(settings = settings)
        assertFalse(snapshot.matches(settings.copy(radialMenu = settings.radialMenu.copy(ringCount = 3))))
        assertFalse(snapshot.matches(settings.copy(radialMenu = settings.radialMenu.copy(iconSizeDp = 32))))
        assertFalse(snapshot.matches(settings.copy(radialMenu = settings.radialMenu.copy(radiusDp = 360))))
        assertFalse(snapshot.matches(settings.copy(radialMenu = settings.radialMenu.copy(ringGapDp = 24))))
        assertFalse(snapshot.matches(settings.copy(radialMenu = settings.radialMenu.copy(itemGapDp = 24))))
    }

    @Test
    fun displaySizeChangesInvalidatePreparedCapacity() {
        val settings = ModuleSettingsSnapshot()
        val snapshot = AppCatalogSnapshot(settings = settings, shortEdgeDp = 400)
        assertTrue(snapshot.matches(settings, 400))
        assertFalse(snapshot.matches(settings, 320))
    }

    @Test
    fun unrelatedGestureSettingsDoNotInvalidatePreparedIcons() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested.copy(cornerTriggerRangeDp = 100, enabled = true)))
    }
}
