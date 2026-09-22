package io.github.mangi.flymefreeform.config

import kotlin.math.ceil
import kotlin.math.sqrt

/** 半径是扇形外缘；两种间距均为图标（含选中描边）之间的净空。 */
internal data class RadialMenuSettings(
    val iconSizeDp: Int = 44,
    val radiusDp: Int = 272,
    val ringGapDp: Int = 12,
    val ringCount: Int = 1,
    val itemGapDp: Int = 0,
) {
    fun sanitized(): RadialMenuSettings = copy(
        iconSizeDp = iconSizeDp.coerceIn(MIN_ICON_DP, MAX_ICON_DP),
        radiusDp = radiusDp.coerceAtLeast(1),
        ringGapDp = ringGapDp.coerceIn(0, MAX_GAP_DP),
        ringCount = ringCount.coerceAtLeast(1),
        itemGapDp = itemGapDp.coerceIn(0, MAX_GAP_DP),
    )

    fun minimumRadiusDp(): Int =
        ceil((sanitized().iconSizeDp / 2f + ITEM_PADDING_DP) * (1f + sqrt(2f)) + EDGE_MARGIN_DP).toInt()

    fun normalized(shortEdgeDp: Int): RadialMenuSettings {
        val clean = sanitized()
        val maximum = shortEdgeDp.coerceAtLeast(1)
        val radius = clean.radiusDp.coerceIn(clean.minimumRadiusDp().coerceAtMost(maximum), maximum)
        val sized = clean.copy(radiusDp = radius)
        return sized.copy(ringCount = sized.ringCount.coerceAtMost(sized.maxRingCount()))
    }

    fun maxRingCount(): Int {
        val extent = iconSizeDp / 2f + ITEM_PADDING_DP
        val outerCenter = radiusDp - extent - EDGE_MARGIN_DP
        val minimumCenter = sqrt(2f) * extent
        return (kotlin.math.floor((outerCenter - minimumCenter) / ringPitchDp()).toInt() + 1).coerceAtLeast(1)
    }

    fun ringPitchDp(): Float = iconSizeDp + ITEM_PADDING_DP * 2f + ringGapDp

    companion object {
        const val MIN_ICON_DP = 24
        const val MAX_ICON_DP = 72
        const val MAX_GAP_DP = 48
        const val ITEM_PADDING_DP = 3.25f
        const val EDGE_MARGIN_DP = 4f
    }
}
