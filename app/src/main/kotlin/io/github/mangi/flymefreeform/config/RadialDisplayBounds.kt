package io.github.mangi.flymefreeform.config

import android.content.Context
import android.view.WindowManager
import kotlin.math.floor

/** 不使用 Activity 的可用内容高度，避免导航栏、分屏和旋转改变手机短边上限。 */
internal fun screenShortEdgeDp(context: Context): Int {
    val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    return floor(minOf(bounds.width(), bounds.height()) / context.resources.displayMetrics.density)
        .toInt().coerceAtLeast(1)
}
