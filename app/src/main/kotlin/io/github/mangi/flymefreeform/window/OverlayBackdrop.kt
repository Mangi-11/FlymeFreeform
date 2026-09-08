package io.github.mangi.flymefreeform.window

/** 扇形与全部面板使用同一遮罩强度，避免交接时桌面亮度改变。 */
internal object OverlayBackdrop {
    const val MAX_ALPHA = 0.1f

    fun color(progress: Float = 1f): Int =
        (progress.coerceIn(0f, 1f) * MAX_ALPHA * 255).toInt() shl 24
}
