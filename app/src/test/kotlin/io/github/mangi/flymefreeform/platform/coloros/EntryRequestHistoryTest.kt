package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryRequestHistoryTest {
    @Test
    fun repeatedRequestCannotBeExecutedAgainAfterItsSessionEnds() {
        val history = EntryRequestHistory()
        assertTrue(history.claim("request", 7_000, 0))
        assertFalse(history.claim("request", 7_000, 1_000))
        assertFalse(history.claim("request", 8_000, 2_000))
        assertFalse(history.claim("request", 7_000, 7_000))
    }

    @Test
    fun capacityDoesNotEvictLiveReplayProtectionAndExpiredEntriesAreReleased() {
        val history = EntryRequestHistory(1)
        assertTrue(history.claim("first", 10, 0))
        assertFalse(history.claim("second", 20, 1))
        assertFalse(history.claim("first", 10, 2))
        assertTrue(history.claim("second", 20, 10))
    }
}
