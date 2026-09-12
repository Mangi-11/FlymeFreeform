package io.github.mangi.flymefreeform.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionPolicyTest {
    @Test
    fun firstUseShowsRecentAppsOnly() {
        val recent = (1..7).map { "recent-$it" }
        assertEquals(
            recent.take(6),
            AppSelectionPolicy.radialItems(false, emptyList(), recent, listOf("other"), { it }, 6),
        )
    }

    @Test
    fun savedPinsSkipUnavailableEntriesAndNeverFallBack() {
        assertEquals(
            listOf("available"),
            AppSelectionPolicy.radialItems(true, listOf("available"), listOf("recent"), listOf("other"), { it }, 6),
        )
        assertEquals(
            emptyList<String>(),
            AppSelectionPolicy.radialItems(true, emptyList(), listOf("recent"), listOf("other"), { it }, 6),
        )
    }
}
