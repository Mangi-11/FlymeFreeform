package io.github.mangi.flymefreeform.window

/** 只有收到原生侧清理确认才允许回退；提交确认与取消交错时不能打开第二个面板。 */
internal class MorePanelSession(val id: String, var deadline: Long) {
    enum class Phase { Preparing, Opening, Confirming, Cancelling, Finished }
    enum class Effect { None, Open, Confirm, Cancel, Fallback, Shown, Abandon }

    var phase = Phase.Preparing
        private set
    private var fallbackAllowed = true

    fun ready(requestId: String, now: Long, openingDeadline: Long): Effect {
        if (requestId != id || phase != Phase.Preparing) return Effect.None
        if (now >= deadline) return finish(Effect.Fallback)
        deadline = openingDeadline
        phase = Phase.Opening
        return Effect.Open
    }

    fun shown(requestId: String, now: Long): Effect {
        if (requestId != id || phase != Phase.Opening) return Effect.None
        if (now >= deadline) return cancel(allowFallback = true)
        phase = Phase.Confirming
        return Effect.Confirm
    }

    fun committed(requestId: String): Effect {
        if (requestId != id || phase !in setOf(Phase.Confirming, Phase.Cancelling)) return Effect.None
        return finish(Effect.Shown)
    }

    fun cleaned(requestId: String): Effect {
        if (requestId != id || phase == Phase.Finished) return Effect.None
        return finish(if (fallbackAllowed) Effect.Fallback else Effect.Abandon)
    }

    fun abort(requestId: String): Effect {
        if (requestId != id || phase == Phase.Finished) return Effect.None
        return finish(Effect.Abandon)
    }

    fun cancel(allowFallback: Boolean): Effect {
        if (phase == Phase.Finished) return Effect.None
        fallbackAllowed = fallbackAllowed && allowFallback
        if (phase == Phase.Preparing) return cleaned(id)
        if (phase == Phase.Cancelling) return Effect.None
        phase = Phase.Cancelling
        return Effect.Cancel
    }

    fun timeout(): Effect =
        if (phase == Phase.Cancelling) abort(id) else cancel(allowFallback = true)

    private fun finish(effect: Effect): Effect {
        phase = Phase.Finished
        return effect
    }
}
