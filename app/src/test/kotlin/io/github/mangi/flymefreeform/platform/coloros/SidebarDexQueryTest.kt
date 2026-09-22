package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge
import java.io.File

class SidebarDexQueryTest {
    @Test fun resolvesBothInspectedSidebarGenerations() {
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
            assertEquals(expected, actual)
        }
    }
}
