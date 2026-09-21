package io.github.mangi.flymefreeform.platform.coloros

import java.lang.reflect.Method

/** 按系统版本解析具体的 SystemUI Application，避免假定单一框架代际。 */
internal object ColorOsSystemUiApplicationTarget {
    private val classNames =
        listOf(
            "com.android.systemui.application.impl.SystemUIApplicationImpl",
            "com.android.systemui.SystemUIApplication",
        )

    internal data class Resolved(
        val className: String,
        val onCreate: Method,
    )

    /**
     * 类加载器来自目标进程。探测另一代框架时允许候选类缺失，其他链接错误继续暴露。
     */
    fun resolve(loadClass: (String) -> Class<*>): Resolved? {
        classNames.forEach { className ->
            val type =
                try {
                    loadClass(className)
                } catch (_: ClassNotFoundException) {
                    return@forEach
                }
            val method =
                try {
                    type.getDeclaredMethod("onCreate").apply { isAccessible = true }
                } catch (_: NoSuchMethodException) {
                    return@forEach
                }
            return Resolved(className, method)
        }
        return null
    }
}
