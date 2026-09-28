package io.github.mangi.flymefreeform.platform.common

import java.lang.reflect.Method

/** 沿继承链查私有字段：system_server 的字段名在厂商版本间可能上移或被改写。 */
internal fun Any.readField(name: String): Any? {
    var current: Class<*>? = javaClass
    while (current != null) {
        try {
            return current.getDeclaredField(name).also { it.isAccessible = true }.get(this)
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    return null
}

/** 按名字与参数个数查方法，避免厂商版本的签名漂移。 */
internal fun Class<*>.findMethod(name: String, parameterCount: Int): Method {
    var current: Class<*>? = this
    while (current != null) {
        current.declaredMethods.firstOrNull { method ->
            method.name == name && method.parameterCount == parameterCount
        }?.let { method ->
            method.isAccessible = true
            return method
        }
        current = current.superclass
    }
    throw NoSuchMethodException(name)
}