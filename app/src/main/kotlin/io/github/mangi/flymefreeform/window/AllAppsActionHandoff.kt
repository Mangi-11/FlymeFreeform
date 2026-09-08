package io.github.mangi.flymefreeform.window

/** 一次选择最多执行一次；取屏类工具必须等面板不可见，取消后不执行迟到动作。 */
internal class AllAppsActionHandoff {
    enum class Action { Ignore, ExecuteNow, ExecuteWhenHidden }
    private var selected = false
    private var cancelled = false
    private var waiting = false

    fun select(tool: Boolean): Action {
        if (selected || cancelled) return Action.Ignore
        selected = true
        waiting = tool
        return if (tool) Action.ExecuteWhenHidden else Action.ExecuteNow
    }

    fun hidden(): Boolean {
        if (!waiting || cancelled) return false
        waiting = false
        return true
    }

    fun cancel() { cancelled = true; waiting = false }
}
