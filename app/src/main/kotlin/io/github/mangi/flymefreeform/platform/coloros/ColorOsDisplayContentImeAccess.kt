package io.github.mangi.flymefreeform.platform.coloros

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** 兼容 Android 17 的 DisplayContent IME 访问器，并保留旧字段回退。 */
internal class ColorOsDisplayContentImeAccess(
    displayContentClass: Class<*>,
    windowStateClass: Class<*>,
) {
    private val reader: Reader =
        findAccessor(displayContentClass, windowStateClass)
            ?: findField(displayContentClass, windowStateClass)
            ?: throw NoSuchMethodException("DisplayContent IME window accessor")

    fun get(displayContent: Any): Any? = reader.read(displayContent)

    private fun findAccessor(type: Class<*>, windowStateClass: Class<*>): Reader? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                val method = current.getDeclaredMethod("getImeWindow")
                if (method.parameterCount == 0 && windowStateClass.isAssignableFrom(method.returnType)) {
                    method.isAccessible = true
                    return MethodReader(method)
                }
            } catch (_: NoSuchMethodException) {
                // 厂商实现可能把访问器放在父类，继续沿继承链查找。
            }
            current = current.superclass
        }
        return null
    }

    private fun findField(type: Class<*>, windowStateClass: Class<*>): Reader? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                val field = current.getDeclaredField("mInputMethodWindow")
                if (windowStateClass.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return FieldReader(field)
                }
            } catch (_: NoSuchFieldException) {
                // Android 17 使用 mImeWindow，并通过 getImeWindow() 暴露；旧字段只作为回退。
            }
            current = current.superclass
        }
        return null
    }

    private sealed interface Reader {
        fun read(instance: Any): Any?
    }

    private class MethodReader(
        private val method: Method,
    ) : Reader {
        override fun read(instance: Any): Any? =
            try {
                method.invoke(instance)
            } catch (exception: InvocationTargetException) {
                throw exception.targetException
            }
    }

    private class FieldReader(
        private val field: Field,
    ) : Reader {
        override fun read(instance: Any): Any? = field.get(instance)
    }
}
