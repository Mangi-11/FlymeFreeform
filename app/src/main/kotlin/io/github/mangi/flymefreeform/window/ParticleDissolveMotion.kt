package io.github.mangi.flymefreeform.window

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 一颗粒子：位置相对采样位图原点，speed 类参数由固定种子的随机数给出。 */
internal data class DissolveParticle(
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int,
    val delay: Float,
    val drift: Float,
    val lift: Float,
    val phase: Float,
)

/** 一次溶解所需的全部粒子与它们相对悬浮层的位置原点。 */
internal data class ParticleDissolve(
    val particles: List<DissolveParticle>,
    val originX: Float,
    val originY: Float,
    val direction: Float,
)

/**
 * 从快照像素采样出的溶解粒子（参数取值固定）。
 * 采样与每帧运动都是纯函数，便于在 JVM 单测里核对。
 */
internal object ParticleDissolveMotion {
    const val DURATION_MILLIS = 1400L
    const val CONTROL_X1 = 0.16f
    const val CONTROL_Y1 = 0.62f
    const val CONTROL_X2 = 0.24f
    const val CONTROL_Y2 = 1f

    /**
     * 按网格扫描快照：每格取中心像素，透明度超过阈值才成为粒子，颜色沿用该像素。
     * 固定种子意味着同一次界面状态的粒子分布每次一致。
     */
    fun sample(
        width: Int,
        height: Int,
        cellPx: Int,
        alphaThreshold: Int,
        pixelAt: (Int, Int) -> Int,
    ): List<DissolveParticle> {
        if (width <= 0 || height <= 0 || cellPx <= 0) return emptyList()
        val random = Random(SEED)
        repeat(RANDOM_WARMUP) { random.nextFloat() }
        val particles = ArrayList<DissolveParticle>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val sampleX = (x + cellPx / 2).coerceAtMost(width - 1)
                val sampleY = (y + cellPx / 2).coerceAtMost(height - 1)
                val color = pixelAt(sampleX, sampleY)
                if (color ushr 24 > alphaThreshold) {
                    particles +=
                        DissolveParticle(
                            x = x.toFloat(),
                            y = y.toFloat(),
                            size = (random.nextFloat() * 0.28f + 0.82f) * cellPx,
                            color = color,
                            delay = x.toFloat() / width * 0.12f + random.nextFloat() * 0.34f,
                            drift = (random.nextFloat() * 5.8f + 2.8f) * cellPx,
                            lift = (random.nextFloat() * 4.2f + 1.2f) * cellPx,
                            phase = random.nextFloat() * (2f * PI.toFloat()),
                        )
                }
                x += cellPx
            }
            y += cellPx
        }
        return particles
    }

    /** 粒子自己的时间轴：进度越过自身延迟后归一化到 0..1。 */
    fun particleTime(progress: Float, delay: Float): Float =
        ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)

    /** 上升缓出：1-(1-t)²。 */
    fun rise(time: Float): Float {
        val remaining = 1f - time
        return 1f - remaining * remaining
    }

    /** 透明度系数：(1-t)²。 */
    fun fade(time: Float): Float {
        val remaining = 1f - time
        return remaining * remaining
    }

    /** 尺寸系数：1-0.38t。 */
    fun shrink(time: Float): Float = 1f - time * 0.38f

    /** 上浮途中的横向摆动。 */
    fun wobble(rise: Float, phase: Float, size: Float): Float =
        sin(rise * 5f + phase) * size * 0.45f

    private const val SEED = 7319
    private const val RANDOM_WARMUP = 64
}