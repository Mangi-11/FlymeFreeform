package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SidebarSymbolCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val symbols = mapOf(
        "resource" to "Lexample/Resource;->instance:Lexample/Predicates;",
        "landscape" to "Lexample/Predicates;->landscape()Z",
        "portrait" to "Lexample/Predicates;->portrait()Z",
        "hover" to "Lexample/Predicates;->hover(Z)Z",
        "shadowSetup" to "Lexample/Shadow;->setup(Landroid/view/View;)V",
        "shadowUpdate" to "Lexample/Shadow;->update(Landroid/view/View;F)V",
        "unit" to "Lexample/Unit;->instance:Lexample/Unit;",
        "blurKind" to "platform",
        "blurAttach" to "Lexample/Blur;->attach(Landroid/view/View;ZZ)Z",
        "blurUpdate" to "Lexample/Blur;->update(F)V",
        "blurClose" to "Lexample/Blur;->close()V",
    )

    @Test fun cacheRoundTripsAndRejectsOtherHostOrRule() {
        val file = temporary.newFile()
        SidebarSymbolCache.write(file, "host-rule", symbols)
        assertEquals(symbols, SidebarSymbolCache.read(file, "host-rule"))
        assertNull(SidebarSymbolCache.read(file, "other-host-rule"))
    }

    @Test fun incompleteUnknownAndMalformedMembersAreRejectedBeforeDexParsing() {
        val file = temporary.newFile()
        for (invalid in listOf(
            symbols - "hover", symbols + ("unknown" to "value"),
            symbols + ("blurKind" to "unknown"),
            symbols + ("hover" to "Lexample/Predicates;->hover(Z)"),
            symbols + ("hover" to "Lexample/Predicates;->hover(V)Z"),
            symbols + ("unit" to "Lexample/Unit;->instance:V"),
        )) {
            SidebarSymbolCache.write(file, "identity", invalid)
            assertNull(SidebarSymbolCache.read(file, "identity"))
        }
    }

    @Test fun unavailableBlurHasNoCallableBlurMembers() {
        val file = temporary.newFile()
        val unavailable = symbols - setOf("blurAttach", "blurUpdate", "blurClose") + ("blurKind" to "unavailable")
        SidebarSymbolCache.write(file, "identity", unavailable)
        assertEquals(unavailable, SidebarSymbolCache.read(file, "identity"))
    }

    @Test fun legacyShapeRequiresSupportsInsteadOfClose() {
        val file = temporary.newFile()
        val legacy = symbols - "blurClose" + mapOf("blurKind" to "legacy", "blurSupports" to "Lexample/Blur;->supports()Z")
        SidebarSymbolCache.write(file, "identity", legacy)
        assertEquals(legacy, SidebarSymbolCache.read(file, "identity"))
        SidebarSymbolCache.write(file, "identity", legacy + ("blurClose" to "Lexample/Blur;->close()V"))
        assertNull(SidebarSymbolCache.read(file, "identity"))
    }
}
