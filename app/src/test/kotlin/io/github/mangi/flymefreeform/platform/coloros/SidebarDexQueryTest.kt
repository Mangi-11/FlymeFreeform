package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.io.InterruptedIOException

class SidebarDexQueryTest {
    @Test fun resolvesInspectedSidebarApks() {
        val fixtures = System.getenv("FLYMEFREEFORM_TEST_SIDEBAR_FIXTURES")
        val native = System.getenv("FLYMEFREEFORM_TEST_DEXKIT_LIBRARY")
        assumeNotNull(fixtures, native)
        System.load(native!!)
        // 每行 APK 路径和已人工核对的 key=descriptor 文件，使用制表符分隔。
        File(fixtures!!).readLines().filter { it.isNotBlank() }.forEach { line ->
            val (apk, expectedFile) = line.split('\t')
            val expected = File(expectedFile).readLines().filter { it.isNotBlank() }.associate {
                val index = it.indexOf('=')
                it.substring(0, index) to it.substring(index + 1)
            }
            val actual = DexKitBridge.create(apk).use { bridge ->
                bridge.setThreadNum(1)
                SidebarDexQuery.find(bridge)
            }
            assertEquals("Sidebar APK: $apk", expected, actual)
        }
    }

    @Test fun interruptedQueryDoesNotReturnPartialCapabilities() {
        val fixtures = System.getenv("FLYMEFREEFORM_TEST_SIDEBAR_FIXTURES")
        val native = System.getenv("FLYMEFREEFORM_TEST_DEXKIT_LIBRARY")
        assumeNotNull(fixtures, native)
        System.load(native!!)
        val apk = File(fixtures!!).readLines().first { it.isNotBlank() }.substringBefore('\t')
        DexKitBridge.create(apk).use { bridge ->
            bridge.setThreadNum(1)
            try {
                Thread.currentThread().interrupt()
                val exception = assertThrows(InterruptedIOException::class.java) { SidebarDexQuery.find(bridge) }
                assertEquals("SIDEBAR_DEX_CANCELLED", exception.message)
            } finally {
                Thread.interrupted()
            }
        }
    }
}
