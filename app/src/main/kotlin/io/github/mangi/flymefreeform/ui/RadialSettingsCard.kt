package io.github.mangi.flymefreeform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.flymefreeform.R
import io.github.mangi.flymefreeform.config.RadialMenuSettings
import io.github.mangi.flymefreeform.framework.FrameworkConnectionState
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.RadialGeometry
import io.github.mangi.flymefreeform.gesture.RadialMenuGeometry
import io.github.mangi.flymefreeform.window.OverlaySafeInsets
import io.github.mangi.flymefreeform.window.RadialIconGeometry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RadialSettingsCard(
    state: FrameworkConnectionState,
    shortEdgeDp: Int,
    onPreviewChange: (RadialMenuSettings?) -> Unit,
    onCommit: (RadialMenuSettings) -> Unit,
) {
    val confirmed = state.settings.radialMenu.normalized(shortEdgeDp)
    var draft by remember(confirmed) { mutableStateOf<RadialMenuSettings?>(null) }
    val shown = draft ?: confirmed
    val menu = RadialMenuGeometry.calculate(shown, shortEdgeDp)
    DisposableEffect(Unit) {
        onDispose { onPreviewChange(null) }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.radial_layout_title),
            summary = stringResource(R.string.radial_capacity_summary, menu.pinnedCapacity),
        )
        @Composable
        fun Setting(
            title: String,
            summary: String,
            value: Int,
            range: IntRange,
            unitIsDp: Boolean = true,
            change: (RadialMenuSettings, Int) -> RadialMenuSettings,
        ) {
            // 仅一圈可用时不创建零长度滑块，避免进度计算出现 NaN。
            if (range.first == range.last) {
                BasicComponent(title = title, summary = summary)
            } else {
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Straighten,
                    confirmedValue = value,
                    isUpdating = state.isUpdating,
                    enabled = state.canChangeSettings,
                    title = title,
                    summary = summary,
                    range = range,
                    unitIsDp = unitIsDp,
                    onPreviewChange = { next ->
                        draft = next?.let { change(confirmed, it).normalized(shortEdgeDp) }
                        onPreviewChange(draft)
                    },
                    onCommit = { next -> onCommit(change(confirmed, next).normalized(shortEdgeDp)) },
                )
            }
        }
        Setting(
            title = stringResource(R.string.radial_icon_size_title),
            summary = stringResource(R.string.radial_icon_size_summary),
            value = confirmed.iconSizeDp,
            range = RadialMenuSettings.MIN_ICON_DP..RadialMenuSettings.MAX_ICON_DP,
        ) { settings, value -> settings.copy(iconSizeDp = value) }
        Setting(
            title = stringResource(R.string.radial_radius_title),
            summary = stringResource(R.string.radial_radius_summary, shortEdgeDp),
            value = confirmed.radiusDp,
            range = confirmed.minimumRadiusDp().coerceAtMost(shortEdgeDp)..shortEdgeDp,
        ) { settings, value -> settings.copy(radiusDp = value) }
        Setting(
            title = stringResource(R.string.radial_ring_gap_title),
            summary = stringResource(R.string.radial_ring_gap_summary),
            value = confirmed.ringGapDp,
            range = 0..RadialMenuSettings.MAX_GAP_DP,
        ) { settings, value -> settings.copy(ringGapDp = value) }
        Setting(
            title = stringResource(R.string.radial_item_gap_title),
            summary = stringResource(R.string.radial_item_gap_summary),
            value = confirmed.itemGapDp,
            range = 0..RadialMenuSettings.MAX_GAP_DP,
        ) { settings, value -> settings.copy(itemGapDp = value) }
        Setting(
            title = stringResource(R.string.radial_ring_count_title),
            summary = stringResource(R.string.radial_ring_count_summary, shown.ringCount, shown.maxRingCount()),
            value = confirmed.ringCount,
            range = 1..shown.maxRingCount(),
            unitIsDp = false,
        ) { settings, value -> settings.copy(ringCount = value) }
    }
}

@Composable
internal fun RadialSettingsPreview(settings: RadialMenuSettings, shortEdgeDp: Int, onLeft: Boolean) {
    val color = MiuixTheme.colorScheme.primary
    val iconColor = MiuixTheme.colorScheme.onPrimary
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
    val insets = OverlaySafeInsets(
        windowInsets.getLeft(density, direction).toFloat(), windowInsets.getTop(density).toFloat(),
        windowInsets.getRight(density, direction).toFloat(), windowInsets.getBottom(density).toFloat(),
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val menu = RadialMenuGeometry.calculate(settings, shortEdgeDp)
        val metrics = RadialIconGeometry.fit(size.width, size.height, this.density, insets, menu.capacity, settings, shortEdgeDp)
        val side = if (onLeft) CornerSide.Left else CornerSide.Right
        val layout = RadialGeometry.layout(
            side, size.width - insets.left - insets.right, size.height - insets.top - insets.bottom,
            metrics.radius, menu.capacity, insets.left, insets.top, metrics.rings,
        )
        val radius = menu.settings.radiusDp * metrics.pixelsPerBaseDp
        val origin = Offset(layout.origin.x, layout.origin.y)
        val topLeft = origin - Offset(radius, radius)
        val start = if (onLeft) 270f else 180f
        drawArc(color.copy(alpha = 0.16f), start, 90f, true, topLeft, Size(radius * 2f, radius * 2f))
        drawArc(color.copy(alpha = 0.8f), start, 90f, false, topLeft, Size(radius * 2f, radius * 2f), style = Stroke(2.dp.toPx()))
        metrics.rings.forEach { ring ->
            drawArc(color.copy(alpha = 0.4f), start, 90f, false,
                origin - Offset(ring.radius, ring.radius), Size(ring.radius * 2f, ring.radius * 2f),
                style = Stroke(1.dp.toPx()))
        }
        layout.itemCenters.forEachIndexed { index, center ->
            val point = Offset(center.x, center.y)
            drawCircle(color.copy(alpha = 0.88f), metrics.iconDiameter / 2f, point)
            if (index == layout.itemCenters.lastIndex) {
                for (dot in -1..1) drawCircle(iconColor, metrics.iconDiameter * 0.04f,
                    point + Offset(dot * metrics.iconDiameter * 0.18f, 0f))
            } else {
                val half = metrics.iconDiameter * 0.15f
                drawRoundRect(iconColor.copy(alpha = 0.8f), point - Offset(half, half), Size(half * 2f, half * 2f))
            }
        }
    }
}
