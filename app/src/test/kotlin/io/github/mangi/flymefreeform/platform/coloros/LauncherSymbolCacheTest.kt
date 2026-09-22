package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LauncherSymbolCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val symbols = LauncherDexQuery.Symbols("input", "monitor", "example.Wrapper", "claim")

    @Test fun apkReplacementWithSameSizeAndTimestampInvalidatesCache() {
        val apk = temporary.newFile("host.apk").apply { writeText("first") }
        val timestamp = apk.lastModified()
        val original = LauncherSymbolCache.identity(apk)
        val cache = temporary.newFile("symbols")
        LauncherSymbolCache.write(cache, original, symbols)
        assertEquals(symbols, LauncherSymbolCache.read(cache, original))
        apk.writeText("other")
        check(apk.setLastModified(timestamp))
        val replacement = LauncherSymbolCache.identity(apk)
        assertNotEquals(original, replacement)
        assertNull(LauncherSymbolCache.read(cache, replacement))
    }

    @Test fun corruptIncompleteAndOversizedCachesAreRejected() {
        val cache = temporary.newFile("symbols")
        for (text in listOf("id\ninput", "id\ninput\nmonitor\nexample.Wrapper\nclaim()", "x".repeat(4097))) {
            cache.writeText(text)
            assertNull(LauncherSymbolCache.read(cache, "id"))
        }
    }

    @Test fun ruleChangeInvalidatesCacheAndReplacementLeavesNoTemporaryFiles() {
        val directory = temporary.newFolder()
        val cache = directory.resolve("symbols")
        LauncherSymbolCache.write(cache, "old-rule", symbols)
        assertNull(LauncherSymbolCache.read(cache, "new-rule"))
        val changed = symbols.copy(inputMethod = "renamed")
        LauncherSymbolCache.write(cache, "new-rule", changed)
        assertEquals(changed, LauncherSymbolCache.read(cache, "new-rule"))
        assertEquals(listOf("symbols"), directory.list()!!.toList())
    }
}
