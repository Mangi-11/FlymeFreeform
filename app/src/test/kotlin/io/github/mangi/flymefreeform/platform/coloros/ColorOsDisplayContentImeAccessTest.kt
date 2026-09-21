package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertSame
import org.junit.Test

class ColorOsDisplayContentImeAccessTest {
    @Test
    fun usesAndroid17AccessorBeforeLegacyField() {
        val window = FakeWindowState()
        val content = AccessorDisplayContent(window)
        val access =
            ColorOsDisplayContentImeAccess(
                AccessorDisplayContent::class.java,
                FakeWindowState::class.java,
            )

        assertSame(window, access.get(content))
    }

    @Test
    fun supportsLegacyInputMethodWindowField() {
        val window = FakeWindowState()
        val content = FieldDisplayContent(window)
        val access =
            ColorOsDisplayContentImeAccess(
                FieldDisplayContent::class.java,
                FakeWindowState::class.java,
            )

        assertSame(window, access.get(content))
    }

    private class FakeWindowState

    private class AccessorDisplayContent(
        private val window: FakeWindowState,
    ) {
        @Suppress("UNUSED")
        private fun getImeWindow(): FakeWindowState = window
    }

    private class FieldDisplayContent(
        @Suppress("unused")
        private val mInputMethodWindow: FakeWindowState,
    )
}
