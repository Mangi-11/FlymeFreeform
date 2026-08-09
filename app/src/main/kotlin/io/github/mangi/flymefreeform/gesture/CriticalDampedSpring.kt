package io.github.mangi.flymefreeform.gesture

import kotlin.math.exp

/** 可打断的解析式临界阻尼弹簧；新目标从当前显示值和速度继续。 */
internal class CriticalDampedSpring(
    initialValue: Float = 0f,
    private val responseSeconds: Float = 0.34f,
) {
    var value: Float = initialValue
        private set
    var velocity: Float = 0f
        private set
    var target: Float = initialValue
        private set

    fun retarget(target: Float) {
        this.target = target
    }

    fun snapTo(value: Float) {
        this.value = value
        target = value
        velocity = 0f
    }

    fun step(deltaSeconds: Float): Float {
        if (deltaSeconds <= 0f) return value
        val omega = 2f * Math.PI.toFloat() / responseSeconds
        val displacement = value - target
        val decay = exp(-omega * deltaSeconds)
        val nextDisplacement = (displacement + (velocity + omega * displacement) * deltaSeconds) * decay
        velocity = (velocity - omega * (velocity + omega * displacement) * deltaSeconds) * decay
        value = target + nextDisplacement
        if (kotlin.math.abs(value - target) < 0.0005f && kotlin.math.abs(velocity) < 0.0005f) {
            snapTo(target)
        }
        return value
    }

    val isAtRest: Boolean
        get() = value == target && velocity == 0f
}
