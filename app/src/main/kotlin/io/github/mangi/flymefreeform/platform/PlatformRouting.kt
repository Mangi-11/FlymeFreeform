package io.github.mangi.flymefreeform.platform

import io.github.mangi.flymefreeform.platform.coloros.ColorOsPlatform
import io.github.mangi.flymefreeform.platform.hyperos.HyperOsPlatform

/** 唯一的平台分派点：属性优先、注入进程内再用类探针复核，未知平台一律回退 ColorOS。 */
internal object PlatformRouting {
    @Volatile
    private var resolved: FreeformPlatform? = null

    fun current(classLoader: ClassLoader? = PlatformRouting::class.java.classLoader): FreeformPlatform {
        resolved?.let { return it }
        val platform = detect(classLoader)
        resolved = platform
        return platform
    }

    private fun detect(classLoader: ClassLoader?): FreeformPlatform {
        if (!systemProperty(MIUI_VERSION_PROPERTY).isNullOrEmpty()) return HyperOsPlatform
        if (COLOROS_PROPERTIES.any { !systemProperty(it).isNullOrEmpty() }) return ColorOsPlatform
        if (classLoader != null) {
            if (classPresent(classLoader, HYPEROS_PROBE_CLASS)) return HyperOsPlatform
            if (classPresent(classLoader, COLOROS_PROBE_CLASS)) return ColorOsPlatform
        }
        return ColorOsPlatform
    }

    private fun systemProperty(name: String): String? =
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, name) as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun classPresent(classLoader: ClassLoader, name: String): Boolean =
        try {
            classLoader.loadClass(name)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }

    private const val MIUI_VERSION_PROPERTY = "ro.miui.ui.version.name"
    private const val HYPEROS_PROBE_CLASS = "miui.app.MiuiFreeFormManager"
    private const val COLOROS_PROBE_CLASS = "com.android.server.wm.FlexibleTaskController"
    private val COLOROS_PROPERTIES =
        listOf(
            "ro.build.version.oplusrom",
            "ro.build.oplus.rom",
            "ro.oplus.version",
        )
}