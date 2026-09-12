package io.github.mangi.flymefreeform.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTransferTest {
    @Test
    fun publishesOnlyACompleteOrderedCatalogOnce() {
        val transfer = CatalogTransfer<String, String>(3) { it }
        assertTrue(transfer.append(0, "tool"))
        assertTrue(transfer.append(1, "shortcut"))
        assertEquals(listOf("tool", "shortcut"), transfer.complete(2))
        assertNull(transfer.complete(2))
        assertFalse(transfer.append(2, "late"))
    }

    @Test
    fun gapDuplicateIdentityAndSizeOverflowInvalidateTheWholeTransfer() {
        for (second in listOf(2 to "next", 1 to "first")) {
            val transfer = CatalogTransfer<String, String>(2) { it }
            assertTrue(transfer.append(0, "first"))
            assertFalse(transfer.append(second.first, second.second))
            assertNull(transfer.complete(1))
        }
        val bounded = CatalogTransfer<String, String>(1) { it }
        assertTrue(bounded.append(0, "first"))
        assertFalse(bounded.append(1, "overflow"))
        assertNull(bounded.complete(1))
    }

    @Test
    fun missingCompletionDataAndCancellationCannotPublishPartialResults() {
        val transfer = CatalogTransfer<String, String>(2) { it }
        transfer.append(0, "first")
        assertNull(transfer.complete(2))
        val cancelled = CatalogTransfer<String, String>(2) { it }
        cancelled.append(0, "first")
        cancelled.cancel()
        assertNull(cancelled.complete(1))
    }

    @Test
    fun aConfirmedEmptyCatalogIsValid() {
        assertEquals(emptyList<String>(), CatalogTransfer<String, String>(2) { it }.complete(0))
    }
}
