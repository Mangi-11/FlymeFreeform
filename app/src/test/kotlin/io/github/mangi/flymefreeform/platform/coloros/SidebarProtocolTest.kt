package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarProtocolTest {
    @Test
    fun onlyTheExpectedUidAndProtocolVersionAreTrusted() {
        assertTrue(SidebarProtocol.isTrustedPeer(1000, 1000, 1))
        assertFalse(SidebarProtocol.isTrustedPeer(10226, 1000, 1))
        assertFalse(SidebarProtocol.isTrustedPeer(0, 1000, 1))
        assertFalse(SidebarProtocol.isTrustedPeer(1000, 1000, 2))
        assertFalse(SidebarProtocol.isTrustedPeer(-1, -1, 1))
    }

    @Test
    fun deadlinesMustBeFutureAndBounded() {
        assertTrue(SidebarProtocol.isValidDeadline(2100, 100, 2000))
        assertFalse(SidebarProtocol.isValidDeadline(100, 100, 2000))
        assertFalse(SidebarProtocol.isValidDeadline(2101, 100, 2000))
        assertFalse(SidebarProtocol.isValidDeadline(Long.MAX_VALUE, 100, 2000))
        assertFalse(SidebarProtocol.isValidDeadline(Long.MIN_VALUE, 100, 2000))
    }

    @Test
    fun requestIdsMustBeCanonicalBoundedUuids() {
        assertTrue(SidebarProtocol.isValidRequestId("017500b2-fc2a-4403-aa28-196cfc137a68"))
        assertFalse(SidebarProtocol.isValidRequestId("1-1-1-1-1"))
        assertFalse(SidebarProtocol.isValidRequestId("x".repeat(36)))
        assertFalse(SidebarProtocol.isValidRequestId("x".repeat(10000)))
    }
}
