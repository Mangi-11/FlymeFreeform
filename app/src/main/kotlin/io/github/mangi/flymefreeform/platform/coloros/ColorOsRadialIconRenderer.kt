package io.github.mangi.flymefreeform.platform.coloros

import android.content.res.Resources
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.lang.reflect.InvocationTargetException
import kotlin.math.roundToInt

/** 只在目录工作线程配置独立 Drawable；形状与图层绘制由 ColorOS 完成。 */
internal class ColorOsRadialIconRenderer(
    private val resources: Resources,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var failureReported = false
    private val access: NativeAccess? by lazy {
        try {
            NativeAccess()
        } catch (exception: ReflectiveOperationException) {
            reportFailure(exception)
            null
        } catch (exception: RuntimeException) {
            reportFailure(exception)
            null
        } catch (error: LinkageError) {
            reportFailure(error)
            null
        }
    }

    fun shapedIcon(source: Drawable): Drawable {
        // 普通位图没有可重新塑形的自适应图层，保留系统结果。
        if (source !is AdaptiveIconDrawable) return source
        val native = access ?: return source
        val state = source.constantState ?: return source
        return try {
            val originalExtension = native.extension.get(source)
            val config = native.config.get(originalExtension)
            val icon = state.newDrawable(resources).mutate() as AdaptiveIconDrawable
            val extension = native.extension.get(icon)
            val maximumSize = native.maximumSize.invoke(null, resources) as Int
            check(maximumSize > 0)
            // 保留系统加载器对应用图层的缩放和分类，仅将外框填满绘制区域。
            val foregroundScale = config?.let { native.foregroundScale.invoke(it) as Float } ?: 1f
            val platform = config?.let { native.platform.invoke(it) as Boolean } ?: false
            val adaptive = config?.let { native.adaptive.invoke(it) as Boolean } ?: true
            val radius = MASK_VIEWPORT / 2f
            val shape = Path().apply {
                addRoundRect(0f, 0f, MASK_VIEWPORT, MASK_VIEWPORT, radius, radius, Path.Direction.CW)
            }
            native.build.invoke(
                extension,
                resources,
                maximumSize,
                (maximumSize * foregroundScale).roundToInt(),
                shape,
                platform,
                adaptive,
            )
            icon
        } catch (exception: InvocationTargetException) {
            val cause = exception.targetException
            if (cause is Error) throw cause
            reportFailure(cause)
            source
        } catch (exception: ReflectiveOperationException) {
            reportFailure(exception)
            source
        } catch (exception: RuntimeException) {
            reportFailure(exception)
            source
        }
    }

    private fun reportFailure(cause: Throwable) {
        if (!failureReported) {
            failureReported = true
            logger(Log.WARN, "RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE", cause)
        }
    }

    private class NativeAccess {
        val extension = AdaptiveIconDrawable::class.java.getField("mIconDrawableExt")
        private val extensionClass = Class.forName("android.graphics.drawable.AdaptiveIconDrawableExtImpl")
        val config = extensionClass.getDeclaredField("mConfig").apply { isAccessible = true }
        private val configClass = Class.forName("android.app.uxicons.CustomAdaptiveIconConfig")
        val foregroundScale = configClass.getMethod("getForegroundScalePercent")
        val platform = configClass.getMethod("getIsPlatformDrawable")
        val adaptive = configClass.getMethod("getIsAdaptiveIconDrawable")
        val maximumSize = Class.forName("com.oplus.util.UxScreenUtil")
            .getMethod("getMaxIconSize", Resources::class.java)
        val build = extensionClass.getMethod(
            "buildAdaptiveIconDrawable",
            Resources::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Path::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }

    private companion object {
        const val MASK_VIEWPORT = 150f
    }
}
