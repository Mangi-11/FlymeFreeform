package io.github.mangi.flymefreeform.platform.coloros

import io.github.mangi.flymefreeform.config.PinnedTarget

internal object SidebarEntryKind {
    private const val LAUNCHER_SHORTCUT_CATEGORY = 11
    private const val MAX_USER_ID = 21_474

    fun fromNative(
        type: Int,
        category: Int,
        isCloned: Boolean,
        entryUserId: Int,
        ownerUserId: Int,
    ): PinnedTarget.Kind? = when {
        type == 0 && !isCloned -> PinnedTarget.Kind.Tool
        type == 1 && !isCloned -> PinnedTarget.Kind.Shortcut
        type == 2 && !isCloned && category == LAUNCHER_SHORTCUT_CATEGORY -> PinnedTarget.Kind.AppEntry
        type == 2 && isCloned && category != LAUNCHER_SHORTCUT_CATEGORY &&
            entryUserId in 1..MAX_USER_ID && entryUserId != ownerUserId -> PinnedTarget.Kind.CloneApp
        else -> null
    }
}
