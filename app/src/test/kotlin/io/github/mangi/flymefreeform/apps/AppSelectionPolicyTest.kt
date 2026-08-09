package io.github.mangi.flymefreeform.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionPolicyTest {
    @Test
    fun firstUseFallsBackToRecentThenAll() {
        assertEquals(
            listOf("recent", "other"),
            AppSelectionPolicy.radialItems(false, emptyList(), listOf("recent"), listOf("recent", "other"), { it }, 6),
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
