package io.github.mangi.flymefreeform.platform.coloros

import io.github.mangi.flymefreeform.config.PinnedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidebarEntryKindTest {
    @Test
    fun cloneAppIsDistinctFromOrdinaryAndSpecialAppEntries() {
        assertEquals(PinnedTarget.Kind.CloneApp, SidebarEntryKind.fromNative(2, 0, true, 999, 0))
        assertEquals(PinnedTarget.Kind.AppEntry, SidebarEntryKind.fromNative(2, 11, false, 0, 0))
        assertNull(SidebarEntryKind.fromNative(2, 0, false, 0, 0))
        assertNull(SidebarEntryKind.fromNative(2, 11, true, 999, 0))
    }

    @Test
    fun cloneRequiresAnotherValidProfileAndApplicationType() {
        assertNull(SidebarEntryKind.fromNative(2, 0, true, 0, 0))
        assertNull(SidebarEntryKind.fromNative(2, 0, true, 999, 999))
        assertNull(SidebarEntryKind.fromNative(2, 0, true, 21_475, 0))
        assertNull(SidebarEntryKind.fromNative(1, 0, true, 999, 0))
    }
}
