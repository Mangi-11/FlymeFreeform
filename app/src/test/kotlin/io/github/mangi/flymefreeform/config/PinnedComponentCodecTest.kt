package io.github.mangi.flymefreeform.config

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedComponentCodecTest {
    @Test
    fun ignoresMalformedAndDuplicateLinesAndCapsAtSix() {
        val raw = """
            a/.A
            invalid
            a/.A
            b/.B
            c/.C
            d/.D
            e/.E
            f/.F
            g/.G
        """.trimIndent()
        assertEquals(listOf("a/.A", "b/.B", "c/.C", "d/.D", "e/.E", "f/.F"), PinnedComponentCodec.decodeRaw(raw))
    }

    @Test
    fun anExistingEmptyValueRemainsAnExplicitEmptyList() {
        assertEquals(emptyList<String>(), PinnedComponentCodec.decodeRaw(""))
    }

    @Test
    fun distinguishesPrimaryAndCloneTargetsByUserSuffix() {
        assertEquals("a/.A", PinnedComponentCodec.componentPart("a/.A#999"))
        assertEquals(999, PinnedComponentCodec.parseUserSuffix("a/.A#999"))
        assertEquals(null, PinnedComponentCodec.parseUserSuffix("a/.A"))
    }
}
