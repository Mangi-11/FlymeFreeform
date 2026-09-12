package io.github.mangi.flymefreeform.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionPolicyTest {
    @Test
    fun firstUsePrefersRecentAppsAndFillsRemainingSlots() {
        val recent = (1..7).map { "recent-$it" }
        assertEquals(
            recent.take(6),
            AppSelectionPolicy.radialItems(false, emptyList(), recent, listOf("other"), { it }, 6),
        )
        assertEquals(
            listOf("recent", "other"),
            AppSelectionPolicy.radialItems(false, emptyList(), listOf("recent"), listOf("recent", "other"), { it }, 6),
        )
    }

    @Test
    fun savedPinsPreferPinnedEntriesThenRecentApps() {
        assertEquals(
            listOf("available", "recent"),
            AppSelectionPolicy.radialItems(true, listOf("available"), listOf("recent"), listOf("other"), { it }, 6),
        )
        assertEquals(
            listOf("recent"),
            AppSelectionPolicy.radialItems(true, emptyList(), listOf("recent"), listOf("other"), { it }, 6),
        )
    }
}
