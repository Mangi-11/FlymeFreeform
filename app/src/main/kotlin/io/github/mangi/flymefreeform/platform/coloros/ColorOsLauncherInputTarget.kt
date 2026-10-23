package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.Context
import android.view.InputEvent
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

internal data class ColorOsLauncherInputTarget(
    val inputMethod: Method,
    val monitorField: Field,
    val pilferMethod: Method,
) {
    companion object {
        /** 仅在桌面后台初始化期间调用。缓存命中不加载 native 库，失败后只重新解析一次。 */
        fun resolve(
            loader: ClassLoader,
            apk: File,
            cache: File,
            report: (String, Throwable?) -> Unit,
        ): ColorOsLauncherInputTarget {
            val identity = LauncherSymbolCache.identity(apk)
            val cached = try {
                LauncherSymbolCache.read(cache, identity)
            } catch (exception: IOException) {
                report("LAUNCHER_DEX_CACHE_READ_FAILED", exception)
                null
            }
            if (cached != null) {
                try {
                    return bind(loader, cached).also { report("LAUNCHER_DEX_CACHE_HIT", null) }
                } catch (exception: ReflectiveOperationException) {
                    report("LAUNCHER_DEX_CACHE_REJECTED", exception)
                } catch (exception: IllegalArgumentException) {
                    report("LAUNCHER_DEX_CACHE_REJECTED", exception)
                }
            }
            System.loadLibrary("dexkit")
            val symbols = DexKitBridge.create(apk.path).use { bridge ->
                bridge.setThreadNum(1)
                LauncherDexQuery.find(bridge)
            }
            val target = bind(loader, symbols)
            try {
                LauncherSymbolCache.write(cache, identity, symbols)
            } catch (exception: IOException) {
                report("LAUNCHER_DEX_CACHE_WRITE_FAILED", exception)
            }
            report("LAUNCHER_DEX_RESOLVED", null)
            return target
        }

        // InputMonitor 属于桌面持有的隐藏类型；下方校验字段和方法签名，失败交由安装层回退。
        @SuppressLint("PrivateApi")
        private fun bind(loader: ClassLoader, symbols: LauncherDexQuery.Symbols): ColorOsLauncherInputTarget {
            val service = loader.loadClass(LauncherDexQuery.SERVICE_CLASS)
            require(Context::class.java.isAssignableFrom(service))
            val input = service.getDeclaredMethod(symbols.inputMethod, InputEvent::class.java)
            require(input.returnType == Void.TYPE && !Modifier.isStatic(input.modifiers))
            val owner = service.getDeclaredField(symbols.monitorField)
            require(owner.type.name == symbols.wrapperClass && !Modifier.isStatic(owner.modifiers))
            require(owner.type.declaredFields.count {
                it.type.name == LauncherDexQuery.INPUT_MONITOR_CLASS && !Modifier.isStatic(it.modifiers)
            } == 1)
            val pilfer = owner.type.getDeclaredMethod(symbols.pilferMethod)
            require(pilfer.returnType == Void.TYPE && !Modifier.isStatic(pilfer.modifiers))
            input.isAccessible = true
            owner.isAccessible = true
            pilfer.isAccessible = true
            return ColorOsLauncherInputTarget(input, owner, pilfer)
        }
    }
}
