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
        "shadowKind" to "available",
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

    @Test fun unavailableShadowHasNoCallableShadowMembers() {
        val file = temporary.newFile()
        val unavailable = symbols - setOf("shadowSetup", "shadowUpdate") + ("shadowKind" to "unavailable")
        SidebarSymbolCache.write(file, "identity", unavailable)
        assertEquals(unavailable, SidebarSymbolCache.read(file, "identity"))
        val withoutEffects = unavailable - setOf("blurAttach", "blurUpdate", "blurClose") + ("blurKind" to "unavailable")
        SidebarSymbolCache.write(file, "identity", withoutEffects)
        assertEquals(withoutEffects, SidebarSymbolCache.read(file, "identity"))
    }

    @Test fun partialUnknownAndLegacyShadowShapesAreRejected() {
        val file = temporary.newFile()
        val unavailable = symbols - setOf("shadowSetup", "shadowUpdate") + ("shadowKind" to "unavailable")
        for (invalid in listOf(
            symbols - "shadowSetup",
            symbols - "shadowUpdate",
            symbols - setOf("shadowSetup", "shadowUpdate"),
            unavailable + ("shadowSetup" to symbols.getValue("shadowSetup")),
            unavailable + ("shadowUpdate" to symbols.getValue("shadowUpdate")),
            symbols + ("shadowKind" to "unavailable"),
            symbols + ("shadowKind" to "unknown"),
            symbols - "shadowKind",
            unavailable - "shadowKind",
        )) {
            SidebarSymbolCache.write(file, "identity", invalid)
            assertNull(SidebarSymbolCache.read(file, "identity"))
        }
    }

    @Test fun platformDefaultsRequireTheCompletePlatformShape() {
        val file = temporary.newFile()
        val defaults = symbols + mapOf(
            "blurKind" to "platform-default",
            "blurAttach" to "Lexample/Blur;->attachDefault(Lexample/Blur;Landroid/view/View;ZZIILjava/lang/Object;)Z",
        )
        SidebarSymbolCache.write(file, "identity", defaults)
        assertEquals(defaults, SidebarSymbolCache.read(file, "identity"))
        for (invalid in listOf(
            defaults - "blurAttach",
            defaults - "blurUpdate",
            defaults - "blurClose",
            defaults + ("blurSupports" to "Lexample/Blur;->supports()Z"),
            defaults + ("blurKind" to "platform-mode"),
        )) {
            SidebarSymbolCache.write(file, "identity", invalid)
            assertNull(SidebarSymbolCache.read(file, "identity"))
        }
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
