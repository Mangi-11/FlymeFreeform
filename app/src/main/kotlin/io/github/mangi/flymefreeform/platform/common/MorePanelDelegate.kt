package io.github.mangi.flymefreeform.platform.common

/** 平台「更多」面板桥接；平台不可用时立即回调 Fallback，由协调器改用内置应用网格。 */
internal interface MorePanelDelegate {
    val isPending: Boolean

    fun open(
        beforeOpen: () -> Boolean,
        onResult: (MorePanelOutcome) -> Unit,
        onExitStarted: () -> Unit,
        onHideBackdrop: (() -> Unit) -> Unit,
        onClosed: () -> Unit,
    ): Boolean

    fun cancel()
}

internal enum class MorePanelOutcome { Shown, Fallback, Abandoned }