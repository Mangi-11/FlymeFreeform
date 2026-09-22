package io.github.mangi.flymefreeform.platform.coloros

import android.view.View
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 只保存已经校验的成员；宿主单例与模糊实例在创建内容时才在主线程取得。 */
internal data class ColorOsSidebarVisualTarget(
    val resource: Field,
    val landscape: Method,
    val portrait: Method,
    val hover: Method,
    val shadowSetup: Method,
    val shadowUpdate: Method,
    val unit: Field,
    val blur: Blur?,
) {
    data class Blur(val platform: Boolean, val attach: Method, val update: Method, val close: Method?, val supports: Method?)

    companion object {
        fun resolve(loader: ClassLoader, apk: File, cache: File, report: (String, Throwable?) -> Unit): ColorOsSidebarVisualTarget {
            val identity = DexApkIdentity.compute(apk, SidebarDexQuery.RULE_VERSION)
            val cached = try { SidebarSymbolCache.read(cache, identity) } catch (e: IOException) {
                report("SIDEBAR_DEX_CACHE_READ_FAILED", e)
                null
            }
            if (cached != null) {
                try {
                    return bind(loader, cached).also { report("SIDEBAR_DEX_CACHE_HIT", null) }
                } catch (e: ReflectiveOperationException) {
                    report("SIDEBAR_DEX_CACHE_REJECTED", e)
                } catch (e: IllegalArgumentException) {
                    report("SIDEBAR_DEX_CACHE_REJECTED", e)
                }
            }
            System.loadLibrary("dexkit")
            val symbols = DexKitBridge.create(apk.path).use { bridge ->
                bridge.setThreadNum(1)
                SidebarDexQuery.find(bridge, report)
            }
            val target = bind(loader, symbols)
            try { SidebarSymbolCache.write(cache, identity, symbols) } catch (e: IOException) {
                report("SIDEBAR_DEX_CACHE_WRITE_FAILED", e)
            }
            report("SIDEBAR_DEX_RESOLVED", null)
            return target
        }

        private fun bind(loader: ClassLoader, symbols: Map<String, String>): ColorOsSidebarVisualTarget {
            fun field(key: String): Field = DexField(symbols.getValue(key)).getFieldInstance(loader).apply {
                require(Modifier.isStatic(modifiers))
                isAccessible = true
            }
            fun method(key: String, static: Boolean, result: Class<*>, vararg parameters: Class<*>): Method =
                DexMethod(symbols.getValue(key)).getMethodInstance(loader).apply {
                    require(Modifier.isStatic(modifiers) == static && returnType == result && parameterTypes.contentEquals(parameters))
                    isAccessible = true
                }
            val bool = java.lang.Boolean.TYPE
            val float = java.lang.Float.TYPE
            val resource = field("resource")
            val landscape = method("landscape", false, bool)
            val portrait = method("portrait", false, bool)
            val hover = method("hover", false, bool, bool)
            require(listOf(landscape, portrait, hover).all { it.declaringClass == resource.type })
            val setup = method("shadowSetup", true, Void.TYPE, View::class.java)
            val update = method("shadowUpdate", true, Void.TYPE, View::class.java, float)
            require(setup.declaringClass == update.declaringClass)
            val unit = field("unit")
            require(unit.type == unit.declaringClass)
            if (symbols.getValue("blurKind") == "unavailable") {
                return ColorOsSidebarVisualTarget(resource, landscape, portrait, hover, setup, update, unit, null)
            }
            val platform = when (symbols.getValue("blurKind")) {
                "platform" -> true
                "legacy" -> false
                else -> throw IllegalArgumentException("SIDEBAR_DEX_BLUR_KIND_INVALID")
            }
            val attach = method("blurAttach", !platform, if (platform) bool else loader.loadClass("com.oplus.view.ViewRootManager"), View::class.java, bool, bool)
            val blurUpdate = if (platform) method("blurUpdate", false, Void.TYPE, float)
                else method("blurUpdate", true, Void.TYPE, View::class.java, attach.returnType, float)
            val close = if (platform) method("blurClose", false, Void.TYPE) else null
            val supports = if (platform) null else method("blurSupports", true, bool)
            require(listOfNotNull(blurUpdate, close, supports).all { it.declaringClass == attach.declaringClass })
            if (platform) attach.declaringClass.getDeclaredConstructor()
            return ColorOsSidebarVisualTarget(resource, landscape, portrait, hover, setup, update, unit, Blur(platform, attach, blurUpdate, close, supports))
        }
    }
}
