package io.github.mangi.flymefreeform.config

import android.content.ComponentName
import android.content.SharedPreferences

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    val radialMenu: RadialMenuSettings = RadialMenuSettings(),
    val pinsSaved: Boolean = false,
    val pinnedComponents: List<ComponentName> = emptyList(),
    val outsideTapCloseMode: OutsideTapCloseMode =
        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE,
    val handleSwipeUpToMiniEnabled: Boolean =
        ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
    val handleDoubleTapCloseEnabled: Boolean =
        ModulePreferences.DEFAULT_HANDLE_DOUBLE_TAP_CLOSE_ENABLED,
    val pauseInLandscape: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
    val pauseInGameMode: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
    val preventMistouchInLandscape: Boolean = ModulePreferences.DEFAULT_PREVENT_MISTOUCH,
    val preventMistouchInGameMode: Boolean = ModulePreferences.DEFAULT_PREVENT_MISTOUCH,
) {
    fun requiresGestureConfirmation(landscape: Boolean, gameMode: Boolean): Boolean =
        !isPausedByEnvironment(landscape, gameMode) &&
            ((preventMistouchInLandscape && landscape) || (preventMistouchInGameMode && gameMode))

    fun isPausedByEnvironment(landscape: Boolean, gameMode: Boolean): Boolean =
        (pauseInLandscape && landscape) || (pauseInGameMode && gameMode)

    fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor {
        val radial = radialMenu.sanitized()
        editor
            .putInt(ModulePreferences.KEY_RADIAL_ICON_SIZE_DP, radial.iconSizeDp)
            .putInt(ModulePreferences.KEY_RADIAL_RADIUS_DP, radial.radiusDp)
            .putInt(ModulePreferences.KEY_RADIAL_RING_GAP_DP, radial.ringGapDp)
            .putInt(ModulePreferences.KEY_RADIAL_RING_COUNT, radial.ringCount)
            .putInt(ModulePreferences.KEY_RADIAL_ITEM_GAP_DP, radial.itemGapDp)
            .putBoolean(ModulePreferences.KEY_MODULE_ENABLED, enabled)
            .putBoolean(ModulePreferences.KEY_LEFT_CORNER_ENABLED, leftCornerEnabled)
            .putBoolean(ModulePreferences.KEY_RIGHT_CORNER_ENABLED, rightCornerEnabled)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_LANDSCAPE, pauseInLandscape)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_GAME_MODE, pauseInGameMode)
            .putBoolean(ModulePreferences.KEY_PREVENT_MISTOUCH_IN_LANDSCAPE, preventMistouchInLandscape)
            .putBoolean(ModulePreferences.KEY_PREVENT_MISTOUCH_IN_GAME_MODE, preventMistouchInGameMode)
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
            .putBoolean(
                ModulePreferences.KEY_HANDLE_DOUBLE_TAP_CLOSE_ENABLED,
                handleDoubleTapCloseEnabled,
            )
        if (pinsSaved) {
            editor.putString(
                ModulePreferences.KEY_CORNER_PINS,
                encodePinnedComponents(pinnedComponents),
            )
        } else {
            editor.remove(ModulePreferences.KEY_CORNER_PINS)
        }
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
            val pinsSaved = preferences.contains(ModulePreferences.KEY_CORNER_PINS)
            val pins =
                if (pinsSaved) {
                    decodePinnedComponents(
                        preferences.getString(ModulePreferences.KEY_CORNER_PINS, "") ?: "",
                    )
                } else {
                    emptyList()
                }
            val radialDefaults = RadialMenuSettings()
            return ModuleSettingsSnapshot(
                enabled = enabled,
                leftCornerEnabled = leftEnabled,
                rightCornerEnabled = rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                radialMenu = RadialMenuSettings(
                    iconSizeDp = preferences.getInt(ModulePreferences.KEY_RADIAL_ICON_SIZE_DP, radialDefaults.iconSizeDp),
                    radiusDp = preferences.getInt(ModulePreferences.KEY_RADIAL_RADIUS_DP, radialDefaults.radiusDp),
                    ringGapDp = preferences.getInt(ModulePreferences.KEY_RADIAL_RING_GAP_DP, radialDefaults.ringGapDp),
                    ringCount = preferences.getInt(ModulePreferences.KEY_RADIAL_RING_COUNT, radialDefaults.ringCount),
                    itemGapDp = preferences.getInt(ModulePreferences.KEY_RADIAL_ITEM_GAP_DP, radialDefaults.itemGapDp),
                ).sanitized(),
                pinsSaved = pinsSaved,
                pinnedComponents = pins,
                outsideTapCloseMode = outsideTapCloseMode,
                handleSwipeUpToMiniEnabled = handleSwipeUpToMiniEnabled,
                handleDoubleTapCloseEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_HANDLE_DOUBLE_TAP_CLOSE_ENABLED,
                    ModulePreferences.DEFAULT_HANDLE_DOUBLE_TAP_CLOSE_ENABLED,
                ),
                preventMistouchInLandscape = preferences.getBoolean(
                    ModulePreferences.KEY_PREVENT_MISTOUCH_IN_LANDSCAPE,
                    ModulePreferences.DEFAULT_PREVENT_MISTOUCH,
                ),
                preventMistouchInGameMode = preferences.getBoolean(
                    ModulePreferences.KEY_PREVENT_MISTOUCH_IN_GAME_MODE,
                    ModulePreferences.DEFAULT_PREVENT_MISTOUCH,
                ),
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

        fun encodePinnedComponents(components: List<ComponentName>): String =
            components
                .asSequence()
                .distinct()
                .joinToString("\n", transform = ComponentName::flattenToString)

        fun decodePinnedComponents(value: String): List<ComponentName> =
            PinnedComponentCodec
                .decodeRaw(value)
                .asSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .toList()
    }
}
