package io.github.mangi.flymefreeform.config

import android.content.SharedPreferences

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    val pinsSaved: Boolean = false,
    val pinnedTargets: List<PinnedTarget> = emptyList(),
    val outsideTapCloseMode: OutsideTapCloseMode =
        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE,
    val handleSwipeUpToMiniEnabled: Boolean =
        ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
    val pauseInLandscape: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
    val pauseInGameMode: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
) {
    fun isPausedByEnvironment(landscape: Boolean, gameMode: Boolean): Boolean =
        (pauseInLandscape && landscape) || (pauseInGameMode && gameMode)

    fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor {
        editor
            .putBoolean(ModulePreferences.KEY_MODULE_ENABLED, enabled)
            .putBoolean(ModulePreferences.KEY_LEFT_CORNER_ENABLED, leftCornerEnabled)
            .putBoolean(ModulePreferences.KEY_RIGHT_CORNER_ENABLED, rightCornerEnabled)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_LANDSCAPE, pauseInLandscape)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_GAME_MODE, pauseInGameMode)
            .putInt(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                ModulePreferences.coerceCornerTriggerRangeDp(cornerTriggerRangeDp),
            )
            .putInt(
                ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                outsideTapCloseMode.storedValue,
            )
            .putBoolean(
                ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                handleSwipeUpToMiniEnabled,
            )
        if (pinsSaved) {
            editor.putString(
                ModulePreferences.KEY_PINNED_TARGETS,
                PinnedTargetCodec.encodeList(pinnedTargets),
            )
        } else {
            editor.remove(ModulePreferences.KEY_PINNED_TARGETS)
        }
        editor.remove(ModulePreferences.KEY_CORNER_PINS)
        return editor
    }

    companion object {
        fun readFrom(preferences: SharedPreferences): ModuleSettingsSnapshot {
            val enabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_MODULE_ENABLED,
                    ModulePreferences.DEFAULT_ENABLED,
                )
            val leftEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_LEFT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val rightEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_RIGHT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val cornerTriggerRangeDp =
                ModulePreferences.coerceCornerTriggerRangeDp(
                    preferences.getInt(
                        ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                        ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
                    ),
                )
            val outsideTapCloseMode =
                OutsideTapCloseMode.fromStoredValue(
                    preferences.getInt(
                        ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE.storedValue,
                    ),
                )
            val handleSwipeUpToMiniEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                    ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                )
            val hasTargets = preferences.contains(ModulePreferences.KEY_PINNED_TARGETS)
            val pinsSaved = hasTargets || preferences.contains(ModulePreferences.KEY_CORNER_PINS)
            val pins = if (hasTargets) {
                PinnedTargetCodec.decodeList(preferences.getString(ModulePreferences.KEY_PINNED_TARGETS, "").orEmpty())
            } else {
                PinnedTargetCodec.decodeLegacy(preferences.getString(ModulePreferences.KEY_CORNER_PINS, "").orEmpty())
            }
            val bothCornersDisabled = !leftEnabled && !rightEnabled
            return ModuleSettingsSnapshot(
                enabled = enabled,
                leftCornerEnabled = if (bothCornersDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothCornersDisabled) true else rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                pinsSaved = pinsSaved,
                pinnedTargets = pins,
                outsideTapCloseMode = outsideTapCloseMode,
                handleSwipeUpToMiniEnabled = handleSwipeUpToMiniEnabled,
                pauseInLandscape = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_LANDSCAPE,
                    ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
                ),
                pauseInGameMode = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_GAME_MODE,
                    ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
                ),
            )
        }
    }
}
