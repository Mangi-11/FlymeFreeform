package io.github.mangi.flymefreeform.platform.coloros

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** 读取独立于 Task 布局边界的输入法避让结果；空边界表示没有避让。 */
internal class ColorOsTaskImeBoundsAccess(
    taskClass: Class<*>,
    taskExtClass: Class<*>,
    boundsClass: Class<*>,
) {
    private val getWrapper = taskClass.getMethod("getWrapper").apply { isAccessible = true }
    private val getExtImpl = getWrapper.returnType.getMethod("getExtImpl").apply { isAccessible = true }
    private val getImeBounds =
        taskExtClass.getMethod("getFlexibleTaskPosAfterIme").apply {
            if (!boundsClass.isAssignableFrom(returnType) || !getExtImpl.returnType.isAssignableFrom(taskExtClass)) {
                throw NoSuchMethodException("Task IME bounds accessor signature")
            }
            isAccessible = true
        }

    fun get(task: Any): Any? {
        val wrapper = getWrapper.invokeUnwrapped(task) ?: return null
        val extension = getExtImpl.invokeUnwrapped(wrapper) ?: return null
        return getImeBounds.invokeUnwrapped(extension)
    }

    private fun Method.invokeUnwrapped(instance: Any): Any? =
        try {
            invoke(instance)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
}
