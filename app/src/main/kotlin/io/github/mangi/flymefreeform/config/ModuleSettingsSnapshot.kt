package io.github.mangi.flymefreeform.config

import android.content.ComponentName
import android.content.SharedPreferences

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    val pinsSaved: Boolean = false,
    val pinnedComponents: List<ComponentName> = emptyList(),
    val radialIconContentScalePercent: Int =
        ModulePreferences.DEFAULT_RADIAL_ICON_CONTENT_SCALE_PERCENT,
    val radialIconMaskScalePercent: Int =
        ModulePreferences.DEFAULT_RADIAL_ICON_MASK_SCALE_PERCENT,
    val radialCircularIconsEnabled: Boolean =
        ModulePreferences.DEFAULT_RADIAL_CIRCULAR_ICONS_ENABLED,
) {
    fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor {
        editor
            .putBoolean(ModulePreferences.KEY_MODULE_ENABLED, enabled)
            .putBoolean(ModulePreferences.KEY_LEFT_CORNER_ENABLED, leftCornerEnabled)
            .putBoolean(ModulePreferences.KEY_RIGHT_CORNER_ENABLED, rightCornerEnabled)
            .putInt(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                ModulePreferences.coerceCornerTriggerRangeDp(cornerTriggerRangeDp),
            )
            .putBoolean(
                ModulePreferences.KEY_RADIAL_CIRCULAR_ICONS_ENABLED,
                radialCircularIconsEnabled,
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_ICON_CONTENT_SCALE_PERCENT,
                ModulePreferences.coerceRadialIconScalePercent(radialIconContentScalePercent),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_ICON_MASK_SCALE_PERCENT,
                ModulePreferences.coerceRadialIconScalePercent(radialIconMaskScalePercent),
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
            val radialCircularIconsEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_CIRCULAR_ICONS_ENABLED,
                    ModulePreferences.DEFAULT_RADIAL_CIRCULAR_ICONS_ENABLED,
                )
            val radialIconContentScalePercent =
                ModulePreferences.coerceRadialIconScalePercent(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_ICON_CONTENT_SCALE_PERCENT,
                        ModulePreferences.DEFAULT_RADIAL_ICON_CONTENT_SCALE_PERCENT,
                    ),
                )
            val radialIconMaskScalePercent =
                ModulePreferences.coerceRadialIconScalePercent(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_ICON_MASK_SCALE_PERCENT,
                        ModulePreferences.DEFAULT_RADIAL_ICON_MASK_SCALE_PERCENT,
                    ),
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
            return ModuleSettingsSnapshot(
                enabled = enabled,
                leftCornerEnabled = leftEnabled,
                rightCornerEnabled = rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                pinsSaved = pinsSaved,
                pinnedComponents = pins,
                radialIconContentScalePercent = radialIconContentScalePercent,
                radialIconMaskScalePercent = radialIconMaskScalePercent,
                radialCircularIconsEnabled = radialCircularIconsEnabled,
            )
        }

        fun encodePinnedComponents(components: List<ComponentName>): String =
            components
                .asSequence()
                .distinct()
                .take(ModulePreferences.MAX_PINNED_APPS)
                .joinToString("\n", transform = ComponentName::flattenToString)

        fun decodePinnedComponents(value: String): List<ComponentName> =
            PinnedComponentCodec
                .decodeRaw(value)
                .asSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .toList()
    }
}
