package io.github.mangi.flymefreeform.gesture

import java.util.concurrent.atomic.AtomicLong

/** 输入回调只转发仍可能属于角落手势的流；主线程判断后可忽略其余事件。 */
internal class PointerStreamGate {
    private val currentStream = AtomicLong()
    @Volatile private var ignored = 0L

    fun begin(): Long = currentStream.incrementAndGet()

    fun current(): Long = currentStream.get()

    fun shouldForward(stream: Long): Boolean = stream != ignored

    fun ignore(stream: Long) {
        if (currentStream.get() == stream) ignored = stream
    }

    fun reset() {
        ignored = currentStream.incrementAndGet()
    }
}
