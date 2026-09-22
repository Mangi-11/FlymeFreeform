package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge

/** 可选的本地 APK 回归测试，不将厂商二进制或混淆名写入测试仓库。 */
class LauncherDexQueryTest {
    @Test fun resolvesInspectedHostApkUniquely() {
        val apk = System.getenv("FLYMEFREEFORM_TEST_LAUNCHER_APK")
        val nativeLibrary = System.getenv("FLYMEFREEFORM_TEST_DEXKIT_LIBRARY")
        val expected = System.getenv("FLYMEFREEFORM_TEST_LAUNCHER_SYMBOLS")
        assumeNotNull(apk, nativeLibrary, expected)
        System.load(nativeLibrary!!)
        val symbols = DexKitBridge.create(apk!!).use { bridge ->
            bridge.setThreadNum(1)
            LauncherDexQuery.find(bridge)
        }
        assertEquals(
            expected,
            listOf(symbols.inputMethod, symbols.monitorField, symbols.wrapperClass, symbols.pilferMethod)
                .joinToString(","),
        )
    }
}
