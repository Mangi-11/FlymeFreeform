package io.github.mangi.flymefreeform.platform.common

/** 没有可用厂商面板时的桥接：立即回调 Fallback，由协调器改用内置应用网格。 */
internal object BuiltInMorePanelDelegate : MorePanelDelegate {
    override val isPending: Boolean get() = false

    override fun open(
        beforeOpen: () -> Boolean,
        onResult: (MorePanelOutcome) -> Unit,
        onExitStarted: () -> Unit,
        onHideBackdrop: (() -> Unit) -> Unit,
        onClosed: () -> Unit,
    ): Boolean {
        if (!beforeOpen()) return false
        onResult(MorePanelOutcome.Fallback)
        return true
    }

    override fun cancel() = Unit
}