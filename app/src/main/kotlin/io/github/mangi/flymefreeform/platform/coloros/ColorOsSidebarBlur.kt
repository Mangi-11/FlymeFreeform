package io.github.mangi.flymefreeform.platform.coloros

import android.view.View
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 通过反射连接侧边栏模糊管理器，不把厂商私有工具类变成模块依赖。
 * ColorOS 17 将静态工具调用改为实例管理器。
 */
internal class ColorOsSidebarBlur(
    target: ColorOsSidebarVisualTarget.Blur?,
) {
    private val legacy = if (target == null || target.platform) null else LegacyBlur(
        checkNotNull(target.supports), target.attach, target.update,
    )
    private val platform = if (target == null || !target.platform) null else PlatformBlur(
        target.attach.declaringClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance(),
        target.attach, target.defaultAttach, target.update, checkNotNull(target.close),
    )

    val available: Boolean
        get() = legacy != null || platform != null

    fun attach(view: View): Session? =
        platform?.attach(view)?.let { PlatformSession(it) }
            ?: legacy?.attach(view)?.let { LegacySession(legacy, view, it) }

    fun close() { platform?.close() }

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
        private val defaultAttach: Boolean,
        private val updateMethod: Method,
        private val closeMethod: Method,
    ) {
        private var attached = false

        fun attach(view: View): PlatformBlur? {
            attached = true
            var success = false
            try {
                // 新签名的最后一项由宿主 Kotlin 默认参数桥解析，mask 8 保留其默认背景色。
                success = if (defaultAttach) {
                    attachMethod.invokeUnwrapped(null, instance, view, true, true, 0, 8, null) == true
                } else {
                    attachMethod.invokeUnwrapped(instance, view, true, true) == true
                }
                return if (success) this else null
            } finally {
                if (!success) close()
            }
        }

        fun update(alpha: Float) {
            updateMethod.invokeUnwrapped(instance, alpha)
        }

        fun close() {
            if (!attached) return
            attached = false
            closeMethod.invokeUnwrapped(instance)
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
    }
}

private fun Method.invokeUnwrapped(receiver: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(receiver, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
