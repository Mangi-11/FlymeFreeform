package io.github.mangi.flymefreeform.gesture

/** 只把普通小窗底部手柄的原生上滑模式替换为原生 MINI 模式。 */
internal object HandleSwipeUpModeRemapper {
    fun remap(
        enabled: Boolean,
        actionFlag: Int,
        originalMode: Int,
        flexibleState: Int,
    ): Int =
        if (
            enabled &&
                actionFlag == BOTTOM_HANDLE_FLAG &&
                originalMode == SWIPE_UP_MODE &&
                flexibleState == ORDINARY_FLEXIBLE_STATE
        ) {
            SWITCH_TO_MINI_MODE
        } else {
            originalMode
        }

    private const val BOTTOM_HANDLE_FLAG = 10
    private const val SWIPE_UP_MODE = 0
    private const val SWITCH_TO_MINI_MODE = 4
    private const val ORDINARY_FLEXIBLE_STATE = 1
}
