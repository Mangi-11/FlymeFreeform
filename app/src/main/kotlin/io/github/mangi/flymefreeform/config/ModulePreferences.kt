package io.github.mangi.flymefreeform.config

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val KEY_MODULE_ENABLED = "enabled"
    const val KEY_LEFT_CORNER_ENABLED = "corner_left_enabled"
    const val KEY_RIGHT_CORNER_ENABLED = "corner_right_enabled"
    const val KEY_CORNER_TRIGGER_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_CORNER_PINS = "corner_pins_v1"
    const val KEY_RADIAL_CIRCULAR_ICONS_ENABLED = "radial_circular_icons_enabled"
    const val KEY_RADIAL_ICON_CONTENT_SCALE_PERCENT = "radial_icon_content_scale_percent"
    const val KEY_RADIAL_ICON_MASK_SCALE_PERCENT = "radial_icon_mask_scale_percent"
    const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode_v1"
    const val KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED = "handle_swipe_up_to_mini_enabled"
    const val DEFAULT_ENABLED = false
    const val DEFAULT_CORNER_ENABLED = true
    const val DEFAULT_CORNER_TRIGGER_RANGE_DP = 84
    const val DEFAULT_RADIAL_CIRCULAR_ICONS_ENABLED = true
    const val DEFAULT_RADIAL_ICON_CONTENT_SCALE_PERCENT = 120
    const val DEFAULT_RADIAL_ICON_MASK_SCALE_PERCENT = 100
    val DEFAULT_OUTSIDE_TAP_CLOSE_MODE = OutsideTapCloseMode.SingleTap
    const val DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED = true
    const val MIN_RADIAL_ICON_SCALE_PERCENT = 80
    const val MAX_RADIAL_ICON_SCALE_PERCENT = 120
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24
    const val MAX_CORNER_TRIGGER_RANGE_DP = 160
    const val MAX_PINNED_APPS = 6

    fun coerceRadialIconScalePercent(value: Int): Int =
        value.coerceIn(MIN_RADIAL_ICON_SCALE_PERCENT, MAX_RADIAL_ICON_SCALE_PERCENT)

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)
}
