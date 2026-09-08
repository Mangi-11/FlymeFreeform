package io.github.mangi.flymefreeform.window

import kotlin.math.PI
import kotlin.math.exp

/** 16.14.6 面板退场使用临界阻尼弹簧：透明度周期 0.3 秒，缩放周期 0.4 秒、终点 0.3。 */
internal object AllAppsPanelMotion {
    data class Frame(val alpha: Float, val scale: Float)

    fun exit(seconds: Float): Frame = Frame(
        alpha = remaining(seconds, 0.3f),
        scale = 0.3f + 0.7f * remaining(seconds, 0.4f),
    )

    fun enter(seconds: Float): Frame = Frame(
        alpha = 1f - remaining(seconds, 0.2f),
        scale = 1f - 0.7f * remaining(seconds, 0.3f),
    )

    private fun remaining(seconds: Float, period: Float): Float {
        val time = seconds.coerceAtLeast(0f)
        val phase = 2.0 * PI * time / period
        return ((1.0 + phase) * exp(-phase)).toFloat().coerceIn(0f, 1f)
    }

    // 与原生 alpha 的最小可见变化量及其位移阈值一致。
    const val EXIT_ALPHA_THRESHOLD = 0.00390625f * 0.75f
    const val MAX_DURATION_MS = 1_000L
}
