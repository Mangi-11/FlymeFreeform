package io.github.mangi.flymefreeform.platform.coloros

import android.view.View
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 通过反射连接侧边栏模糊管理器，不把厂商私有工具类变成模块依赖。
 * ColorOS 17 将静态工具调用改为实例管理器。
 */
internal class ColorOsSidebarBlur(
    loader: ClassLoader,
) {
    private val legacy = LegacyBlur.tryCreate(loader)
    private val platform = PlatformBlur.tryCreate(loader)

    val available: Boolean
        get() = legacy != null || platform != null

    fun attach(view: View): Session? =
        platform?.attach(view)?.let { PlatformSession(it) }
            ?: legacy?.attach(view)?.let { LegacySession(legacy, view, it) }

    internal interface Session {
        fun update(alpha: Float)

        fun close()
    }

    private class PlatformSession(
        private val helper: PlatformBlur,
    ) : Session {
        override fun update(alpha: Float) = helper.update(alpha)

        override fun close() = helper.close()
    }

    private class LegacySession(
        private val helper: LegacyBlur,
        private val view: View,
        private val manager: Any,
    ) : Session {
        override fun update(alpha: Float) = helper.update(view, manager, alpha)

        override fun close() = Unit
    }

    private class PlatformBlur(
        private val instance: Any,
        private val attachMethod: Method,
        private val updateMethod: Method,
        private val closeMethod: Method,
    ) {
        fun attach(view: View): PlatformBlur? =
            if (attachMethod.invokeUnwrapped(instance, view, true, true) == true) this else null

        fun update(alpha: Float) {
            updateMethod.invokeUnwrapped(instance, alpha)
        }

        fun close() {
            closeMethod.invokeUnwrapped(instance)
        }

        companion object {
            fun tryCreate(loader: ClassLoader): PlatformBlur? =
                try {
                    val type = loader.loadClass(PLATFORM_CLASS_NAME)
                    val instance = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                    PlatformBlur(
                        instance = instance,
                        attachMethod = type.getDeclaredMethod(
                            "r",
                            View::class.java,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                        ).apply { isAccessible = true },
                        updateMethod = type.getDeclaredMethod("S", Float::class.javaPrimitiveType).apply {
                            isAccessible = true
                        },
                        closeMethod = type.getDeclaredMethod("H").apply { isAccessible = true },
                    )
                } catch (_: ClassNotFoundException) {
                    null
                } catch (_: ReflectiveOperationException) {
                    null
                }
        }
    }

    private class LegacyBlur(
        private val supports: Method,
        private val create: Method,
        private val updateMethod: Method,
    ) {
        fun attach(view: View): Any? {
            if (supports.invokeUnwrapped(null) != true) return null
            return create.invokeUnwrapped(null, view, false, false)
        }

        fun update(view: View, manager: Any, alpha: Float) {
            updateMethod.invokeUnwrapped(null, view, manager, alpha)
        }

        companion object {
            fun tryCreate(loader: ClassLoader): LegacyBlur? =
                try {
                    val type = loader.loadClass(LEGACY_CLASS_NAME)
                    val supports = type.getDeclaredMethod("k").apply { isAccessible = true }
                    val create = type.getDeclaredMethod(
                        "q",
                        View::class.java,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    val update = type.getDeclaredMethod(
                        "s",
                        View::class.java,
                        create.returnType,
                        Float::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    LegacyBlur(supports, create, update)
                } catch (_: ClassNotFoundException) {
                    null
                } catch (_: ReflectiveOperationException) {
                    null
                }
        }
    }

    private companion object {
        const val PLATFORM_CLASS_NAME =
            "com.oplus.smartsidebar.utils.SidebarPlatformBlurHelper"
        const val LEGACY_CLASS_NAME = "com.oplus.smartsidebar.utils.b"
    }
}

private fun Method.invokeUnwrapped(receiver: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(receiver, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
