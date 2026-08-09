package io.github.mangi.flymefreeform.window

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class OverlaySafeInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

internal data class OverlayBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

internal data class RadialVisualMetrics(
    val radius: Float,
    val plateDiameter: Float,
    val iconDiameter: Float,
    val selectionEnterRadius: Float,
    val selectionKeepRadius: Float,
)

internal data class PanelVisualMetrics(
    val bounds: OverlayBounds,
    val columns: Int,
    val cellHeight: Float,
    val iconDiameter: Float,
    val labelTextSize: Float,
    val labelTopOffset: Float,
    val contentHorizontalPadding: Float,
    val horizontalTextPadding: Float,
    val topPadding: Float,
    val cornerRadius: Float,
    val maxScroll: Float,
) {
    /** 只返回当前视口及相邻缓冲行，避免滚动时遍历完整应用目录。 */
    fun visibleItemRange(itemCount: Int, scroll: Float): IntRange {
        if (itemCount <= 0) return 1..0
        val firstRow =
            floor(((scroll - topPadding) / cellHeight).coerceAtLeast(0f))
                .toInt()
        val lastRow =
            floor(((scroll + bounds.height - topPadding) / cellHeight).coerceAtLeast(0f))
                .toInt() + 1
        val firstIndex = (firstRow * columns).coerceAtMost(itemCount)
        val endExclusive = min(itemCount, (lastRow + 1) * columns)
        return if (firstIndex < endExclusive) firstIndex until endExclusive else 1..0
    }
}

internal data class AdaptiveOverlayMetrics(
    val radial: RadialVisualMetrics,
    val panel: PanelVisualMetrics,
)

/** 只依赖当前窗口与系统图标尺度；所有位置和尺寸都从可用空间推导。 */
internal object AdaptiveOverlayGeometry {
    fun calculate(
        width: Float,
        height: Float,
        safeInsets: OverlaySafeInsets,
        systemIconSize: Float,
        fontScale: Float,
        panelItemCount: Int,
        anchorOnLeft: Boolean,
    ): AdaptiveOverlayMetrics {
        require(width > 0f && height > 0f)

        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(1f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(1f)
        val shortEdge = min(width, height)
        val iconSeed = systemIconSize.takeIf { it > 0f } ?: shortEdge * FALLBACK_ICON_FRACTION
        val plateDiameter =
            (iconSeed * RADIAL_PLATE_ICON_FRACTION)
                .coerceIn(shortEdge * RADIAL_PLATE_MIN_FRACTION, shortEdge * RADIAL_PLATE_MAX_FRACTION)
        val radialRadius =
            min(shortEdge * RADIAL_WIDTH_FRACTION, safeHeight * RADIAL_HEIGHT_FRACTION)
                .coerceAtLeast(plateDiameter * RADIAL_MIN_PLATE_DISTANCE)
        val radial =
            RadialVisualMetrics(
                radius = radialRadius,
                plateDiameter = plateDiameter,
                iconDiameter = plateDiameter * RADIAL_ICON_FRACTION,
                selectionEnterRadius = plateDiameter * SELECTION_ENTER_FRACTION,
                selectionKeepRadius = plateDiameter * SELECTION_KEEP_FRACTION,
            )

        val outerMargin = plateDiameter * OUTER_MARGIN_FRACTION
        val requestedContentHorizontalPadding = plateDiameter * PANEL_HORIZONTAL_PADDING_FRACTION
        val labelTextSize = plateDiameter * PANEL_LABEL_FRACTION * fontScale.coerceAtLeast(0.1f)
        val horizontalTextPadding = labelTextSize * PANEL_LABEL_HORIZONTAL_PADDING_FRACTION
        val panelIconDiameter = plateDiameter * PANEL_ICON_FRACTION
        val minimumCellWidth =
            max(
                panelIconDiameter * PANEL_ICON_CELL_FRACTION,
                labelTextSize * PANEL_LABEL_CELL_FRACTION,
            )
        val availablePanelWidth = (safeWidth - outerMargin * 2).coerceAtLeast(1f)
        val widthBudget =
            min(
                availablePanelWidth,
                max(minimumCellWidth, radialRadius * PANEL_RADIUS_WIDTH_FRACTION),
            )
        val contentHorizontalPadding =
            min(
                requestedContentHorizontalPadding,
                widthBudget * PANEL_MAX_CONTENT_HORIZONTAL_PADDING_FRACTION,
            )
        val maximumColumns =
            floor((widthBudget - contentHorizontalPadding * 2) / minimumCellWidth)
                .toInt()
                .coerceAtLeast(1)
        val columns = min(panelItemCount.coerceAtLeast(1), maximumColumns)
        val naturalPanelWidth = minimumCellWidth * columns + contentHorizontalPadding * 2
        val panelWidth =
            if (panelItemCount > columns) widthBudget else min(widthBudget, naturalPanelWidth)

        val labelGap = labelTextSize * PANEL_LABEL_GAP_FRACTION
        val labelLineHeight = labelTextSize * PANEL_LABEL_LINE_HEIGHT_FRACTION
        val cellHeight =
            max(
                panelIconDiameter * PANEL_ICON_ROW_FRACTION,
                panelIconDiameter + labelLineHeight + labelGap,
            )
        val verticalPadding = cellHeight * PANEL_VERTICAL_PADDING_FRACTION
        val rows = ceil(panelItemCount / columns.toFloat()).toInt().coerceAtLeast(1)
        val naturalPanelHeight = rows * cellHeight + verticalPadding * 2
        val availablePanelHeight = (safeHeight - outerMargin * 2).coerceAtLeast(1f)
        val minimumUsefulHeight = min(availablePanelHeight, cellHeight + verticalPadding * 2)
        val heightBudget =
            max(
                minimumUsefulHeight,
                min(availablePanelHeight, radialRadius * PANEL_RADIUS_HEIGHT_FRACTION),
            )
        val panelHeight = min(naturalPanelHeight, heightBudget)
        val left =
            if (anchorOnLeft) {
                safeInsets.left + outerMargin
            } else {
                width - safeInsets.right - outerMargin - panelWidth
            }
        val bottom = height - safeInsets.bottom - outerMargin
        val bounds = OverlayBounds(left, bottom - panelHeight, left + panelWidth, bottom)
        val cornerRadius = min(plateDiameter * PANEL_CORNER_FRACTION, min(panelWidth, panelHeight) / 2)

        return AdaptiveOverlayMetrics(
            radial = radial,
            panel =
                PanelVisualMetrics(
                    bounds = bounds,
                    columns = columns,
                    cellHeight = cellHeight,
                    iconDiameter = panelIconDiameter,
                    labelTextSize = labelTextSize,
                    labelTopOffset = panelIconDiameter / 2 + labelGap,
                    contentHorizontalPadding = contentHorizontalPadding,
                    horizontalTextPadding = horizontalTextPadding,
                    topPadding = verticalPadding,
                    cornerRadius = cornerRadius,
                    maxScroll = (naturalPanelHeight - panelHeight).coerceAtLeast(0f),
                ),
        )
    }

    private const val FALLBACK_ICON_FRACTION = 0.13f
    private const val RADIAL_PLATE_ICON_FRACTION = 0.66f
    private const val RADIAL_PLATE_MIN_FRACTION = 0.07f
    private const val RADIAL_PLATE_MAX_FRACTION = 0.10f
    private const val RADIAL_ICON_FRACTION = 0.90f
    private const val RADIAL_WIDTH_FRACTION = 0.60f
    private const val RADIAL_HEIGHT_FRACTION = 0.34f
    private const val RADIAL_MIN_PLATE_DISTANCE = 4.8f
    private const val SELECTION_ENTER_FRACTION = 0.90f
    private const val SELECTION_KEEP_FRACTION = 1.25f
    private const val OUTER_MARGIN_FRACTION = 0.55f
    private const val PANEL_HORIZONTAL_PADDING_FRACTION = 0.40f
    private const val PANEL_MAX_CONTENT_HORIZONTAL_PADDING_FRACTION = 0.16f
    private const val PANEL_LABEL_FRACTION = 0.34f
    private const val PANEL_ICON_FRACTION = 1.07f
    private const val PANEL_ICON_CELL_FRACTION = 1.55f
    private const val PANEL_LABEL_CELL_FRACTION = 5.6f
    private const val PANEL_RADIUS_WIDTH_FRACTION = 1.75f
    private const val PANEL_LABEL_HORIZONTAL_PADDING_FRACTION = 0.20f
    private const val PANEL_LABEL_GAP_FRACTION = 0.42f
    private const val PANEL_LABEL_LINE_HEIGHT_FRACTION = 1.15f
    private const val PANEL_ICON_ROW_FRACTION = 1.78f
    private const val PANEL_VERTICAL_PADDING_FRACTION = 0.18f
    private const val PANEL_RADIUS_HEIGHT_FRACTION = 1.82f
    private const val PANEL_CORNER_FRACTION = 0.80f
}
