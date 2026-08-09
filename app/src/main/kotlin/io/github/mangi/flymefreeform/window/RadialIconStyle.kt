package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.config.ModulePreferences

/** 单次扇形展示使用的不可变图标样式；配置变化从下一次手势开始生效。 */
internal class RadialIconStyle private constructor(
    val circularEnabled: Boolean,
    private val contentScale: Float,
    private val maskScale: Float,
) {
    fun maskDiameter(plateDiameter: Float): Float = plateDiameter * maskScale

    fun contentDiameter(plateDiameter: Float): Float = maskDiameter(plateDiameter) * contentScale

    fun moreDiameter(plateDiameter: Float): Float =
        if (circularEnabled) maskDiameter(plateDiameter) else plateDiameter

    companion object {
        fun fromPercent(
            circularEnabled: Boolean,
            contentScalePercent: Int,
            maskScalePercent: Int,
        ): RadialIconStyle =
            RadialIconStyle(
                circularEnabled = circularEnabled,
                contentScale =
                    ModulePreferences.coerceRadialIconScalePercent(contentScalePercent) / 100f,
                maskScale =
                    ModulePreferences.coerceRadialIconScalePercent(maskScalePercent) / 100f,
            )

        val Default =
            fromPercent(
                circularEnabled = ModulePreferences.DEFAULT_RADIAL_CIRCULAR_ICONS_ENABLED,
                contentScalePercent = ModulePreferences.DEFAULT_RADIAL_ICON_CONTENT_SCALE_PERCENT,
                maskScalePercent = ModulePreferences.DEFAULT_RADIAL_ICON_MASK_SCALE_PERCENT,
            )
    }
}
