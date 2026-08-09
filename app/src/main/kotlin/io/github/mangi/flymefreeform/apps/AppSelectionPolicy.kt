package io.github.mangi.flymefreeform.apps

internal object AppSelectionPolicy {
    fun <T> radialItems(
        pinsSaved: Boolean,
        availablePins: List<T>,
        recent: List<T>,
        all: List<T>,
        identity: (T) -> Any,
        limit: Int,
    ): List<T> =
        if (pinsSaved) {
            availablePins.distinctBy(identity).take(limit)
        } else {
            (recent + all).distinctBy(identity).take(limit)
        }

    fun <T> panelItems(
        recent: List<T>,
        all: List<T>,
        excluded: Set<Any>,
        identity: (T) -> Any,
    ): List<T> =
        (recent + all).distinctBy(identity).filterNot { identity(it) in excluded }
}
