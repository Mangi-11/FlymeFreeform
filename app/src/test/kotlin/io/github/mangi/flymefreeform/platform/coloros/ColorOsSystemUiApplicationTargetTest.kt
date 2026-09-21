package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ColorOsSystemUiApplicationTargetTest {
    @Test
    fun prefersAndroid17ImplementationWhenBothNamesResolve() {
        val resolved =
            ColorOsSystemUiApplicationTarget.resolve { name ->
                when (name) {
                    "com.android.systemui.application.impl.SystemUIApplicationImpl" ->
                        Android17Application::class.java
                    "com.android.systemui.SystemUIApplication" -> LegacyApplication::class.java
                    else -> error(name)
                }
            }

        assertNotNull(resolved)
        assertEquals(
            "com.android.systemui.application.impl.SystemUIApplicationImpl",
            resolved?.className,
        )
    }

    @Test
    fun fallsBackToLegacyImplementationWhenNewClassIsMissing() {
        val resolved =
            ColorOsSystemUiApplicationTarget.resolve { name ->
                if (name == "com.android.systemui.SystemUIApplication") {
                    LegacyApplication::class.java
                } else {
                    throw ClassNotFoundException(name)
                }
            }

        assertEquals("com.android.systemui.SystemUIApplication", resolved?.className)
    }

    private class Android17Application {
        fun onCreate() = Unit
    }

    private class LegacyApplication {
        fun onCreate() = Unit
    }
}
