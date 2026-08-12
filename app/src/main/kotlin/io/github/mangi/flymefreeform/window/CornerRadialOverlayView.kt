package io.github.mangi.flymefreeform.window

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.OverScroller
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.CriticalDampedSpring
import io.github.mangi.flymefreeform.gesture.RadialGeometry
import io.github.mangi.flymefreeform.gesture.RadialLayout
import io.github.mangi.flymefreeform.platform.coloros.AppCatalogSnapshot
import io.github.mangi.flymefreeform.platform.coloros.RadialAppEntry
import kotlin.math.abs
import kotlin.math.pow

internal class CornerRadialOverlayView(
    context: Context,
    private val listener: Listener,
) : View(context), Choreographer.FrameCallback {
    interface Listener {
        fun onAppCommitted(entry: RadialAppEntry)
        fun onMorePanelRequested()
        fun onDismissRequested()
    }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 255, 255, 255) }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(253, 248, 248, 250) }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 38)
            textAlign = Paint.Align.CENTER
        }
    private val morePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 55, 60)
            style = Paint.Style.FILL
        }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconDestination = RectF()
    private val iconClipPath = Path()
    private val reveal = CriticalDampedSpring(responseSeconds = REVEAL_RESPONSE_SECONDS)
    private val panel = CriticalDampedSpring()
    private val itemScales = MutableList(MAX_RADIAL_ITEMS) { CriticalDampedSpring(1f, 0.28f) }
    private var catalog = AppCatalogSnapshot()
    private var radialIconStyle = RadialIconStyle.Default
    private var side = CornerSide.Right
    private var layout = RadialLayout(side, io.github.mangi.flymefreeform.gesture.GesturePoint(0f, 0f), 0f, emptyList())
    private var visualMetrics: AdaptiveOverlayMetrics? = null
    private var appliedWindowInsets: WindowInsets? = null
    private var selectedIndex: Int? = null
    private var panelMode = false
    private var panelRect = RectF()
    private var panelScroll = 0f
    private var panelMaxScroll = 0f
    private var panelLabels: List<String> = emptyList()
    private var panelLabelBaselineOffset = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastTouchY = 0f
    private var moved = false
    private var lastFrameNanos = 0L
    private var frameScheduled = false
    private var lastTickUptime = 0L
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
    private val panelScroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var dismissing = false
    private var dismissNotified = false
    private var pendingRadialCommit: RadialAppEntry? = null
    private var radialExitRunning = false
    private var radialExitElapsedSeconds = 0f
    private var radialExitVisuals = RadialExitMotion.sample(0f)
    private val timeout = Runnable(::requestDismiss)
    private val dismissFallback = Runnable(::completeRadialExit)

    init {
        updateColors(resources.configuration)
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun begin(
        side: CornerSide,
        catalog: AppCatalogSnapshot,
        iconStyle: RadialIconStyle,
        x: Float,
        y: Float,
    ) {
        this.side = side
        this.catalog = catalog
        radialIconStyle = iconStyle
        panelScroller.abortAnimation()
        recycleVelocityTracker()
        panelMode = false
        dismissing = false
        dismissNotified = false
        pendingRadialCommit = null
        radialExitRunning = false
        radialExitElapsedSeconds = 0f
        radialExitVisuals = RadialExitMotion.sample(0f)
        selectedIndex = null
        panelScroll = 0f
        updateLayout()
        reveal.snapTo(0.08f)
        reveal.retarget(0.08f)
        panel.snapTo(0f)
        itemScales.forEach { it.snapTo(1f) }
        post {
            if (isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }
        }
        removeCallbacks(timeout)
        postDelayed(timeout, GESTURE_TIMEOUT_MS)
        updateGesture(x, y)
        scheduleFrame()
    }

    fun updateGesture(x: Float, y: Float): Int? {
        if (panelMode || layout.itemCenters.isEmpty()) return selectedIndex
        removeCallbacks(timeout)
        postDelayed(timeout, GESTURE_TIMEOUT_MS)
        val revealDistance = layout.radius * 0.72f
        reveal.retarget(RadialGeometry.progress(layout, x, y, revealDistance))
        val next =
            RadialGeometry.selection(
                layout = layout,
                x = x,
                y = y,
                previous = selectedIndex,
                enterRadius = visualMetrics?.radial?.selectionEnterRadius ?: return selectedIndex,
                keepRadius = visualMetrics?.radial?.selectionKeepRadius ?: return selectedIndex,
            )
        if (next != selectedIndex) {
            selectedIndex = next
            itemScales.forEachIndexed { index, spring -> spring.retarget(if (index == next) 1.2f else 1f) }
            val now = SystemClock.uptimeMillis()
            if (next != null && now - lastTickUptime >= TICK_INTERVAL_MS) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastTickUptime = now
            }
        }
        invalidate()
        scheduleFrame()
        return next
    }

    fun finishGesture() {
        val selection = selectedIndex
        when {
            selection == null -> dismissAnimated()
            selection < catalog.radialApps.size -> dismissAnimated(catalog.radialApps[selection])
            else -> showMorePanel()
        }
    }

    fun cancelGesture() {
        dismissAnimated()
    }

    fun showMorePanel() {
        panelMode = true
        selectedIndex = null
        reveal.retarget(0f)
        panel.retarget(1f)
        listener.onMorePanelRequested()
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
        requestFocus()
        scheduleFrame()
    }

    fun dismissAnimated() = dismissAnimated(pendingCommit = null)

    private fun dismissAnimated(pendingCommit: RadialAppEntry?) {
        if (dismissing) return
        dismissing = true
        pendingRadialCommit = pendingCommit
        removeCallbacks(timeout)
        reveal.snapTo(reveal.value)
        itemScales.forEach { it.snapTo(it.value) }
        radialExitElapsedSeconds = 0f
        radialExitVisuals = RadialExitMotion.sample(0f)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            completeRadialExit()
        } else {
            radialExitRunning = true
            removeCallbacks(dismissFallback)
            postDelayed(dismissFallback, DISMISS_FALLBACK_MS)
            scheduleFrame()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && (oldw != w || oldh != h)) {
            requestDismiss()
            return
        }
        updateLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateColors(newConfig)
        updateLayout()
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        appliedWindowInsets = insets
        val result = super.onApplyWindowInsets(insets)
        updateLayout()
        invalidate()
        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visualProgress = maxOf(reveal.value, panel.value)
        val scrimExitAlpha = if (panelMode) 1f else radialExitVisuals.scrimAlpha
        scrimPaint.alpha =
            (MAX_SCRIM_ALPHA * visualProgress.coerceIn(0f, 1f) * scrimExitAlpha).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        if (reveal.value > 0.001f && radialExitVisuals.contentAlpha > 0.001f) drawRadial(canvas)
        if (panel.value > 0.001f) drawPanel(canvas)
    }

    private fun drawRadial(canvas: Canvas) {
        val radialMetrics = visualMetrics?.radial ?: return
        val plate = radialMetrics.plateDiameter
        val exitAlpha = radialExitVisuals.contentAlpha.coerceIn(0f, 1f)
        val previousPlateAlpha = platePaint.alpha
        val previousSelectedAlpha = selectedPaint.alpha
        val previousMoreAlpha = morePaint.alpha
        val previousIconAlpha = iconPaint.alpha
        platePaint.alpha = (previousPlateAlpha * exitAlpha).toInt()
        selectedPaint.alpha = (previousSelectedAlpha * exitAlpha).toInt()
        morePaint.alpha = (previousMoreAlpha * exitAlpha).toInt()
        iconPaint.alpha = (previousIconAlpha * exitAlpha).toInt()
        try {
            layout.itemCenters.forEachIndexed { index, destination ->
                val revealSlot = if (index == layout.itemCenters.lastIndex) 0 else index + 1
                val stagger = (reveal.value * 1.38f - revealSlot * 0.055f).coerceIn(0f, 1f)
                if (stagger <= 0f) return@forEachIndexed
                val eased = 1f - (1f - stagger).pow(3)
                val x = layout.origin.x + (destination.x - layout.origin.x) * eased
                val y = layout.origin.y + (destination.y - layout.origin.y) * eased
                val scale =
                    itemScales[index].value *
                        (0.55f + 0.45f * eased) *
                        radialExitVisuals.contentScale
                val size = plate * scale
                if (index < catalog.radialApps.size) {
                    if (radialIconStyle.circularEnabled) {
                        drawRadialAppIcon(
                            canvas = canvas,
                            bitmap = catalog.radialApps[index].icon,
                            x = x,
                            y = y,
                            plateDiameter = size,
                            selected = index == selectedIndex,
                        )
                    } else {
                        drawSystemBitmap(
                            canvas = canvas,
                            bitmap = catalog.radialApps[index].icon,
                            x = x,
                            y = y,
                            size = radialMetrics.iconDiameter * scale,
                        )
                    }
                } else {
                    val moreDiameter = radialIconStyle.moreDiameter(size)
                    canvas.drawCircle(
                        x,
                        y,
                        moreDiameter / 2,
                        if (index == selectedIndex) selectedPaint else platePaint,
                    )
                    val dotRadius = moreDiameter * MORE_DOT_RADIUS_FRACTION
                    for (offset in -1..1) {
                        canvas.drawCircle(
                            x + offset * moreDiameter * MORE_DOT_SPACING_FRACTION,
                            y,
                            dotRadius,
                            morePaint,
                        )
                    }
                }
            }
        } finally {
            platePaint.alpha = previousPlateAlpha
            selectedPaint.alpha = previousSelectedAlpha
            morePaint.alpha = previousMoreAlpha
            iconPaint.alpha = previousIconAlpha
        }
    }

    private fun drawPanel(canvas: Canvas) {
        val panelMetrics = visualMetrics?.panel ?: return
        val pivotX = if (side == CornerSide.Left) panelRect.left else panelRect.right
        val pivotY = panelRect.bottom
        canvas.save()
        canvas.scale(panel.value, panel.value, pivotX, pivotY)
        panelPaint.alpha = (253 * panel.value).toInt()
        canvas.drawRoundRect(
            panelRect,
            panelMetrics.cornerRadius,
            panelMetrics.cornerRadius,
            panelPaint,
        )
        canvas.clipRect(panelRect)
        val contentWidth = panelRect.width() - panelMetrics.contentHorizontalPadding * 2
        val cellWidth = contentWidth / panelMetrics.columns
        val cellHeight = panelMetrics.cellHeight
        val iconSize = panelMetrics.iconDiameter
        for (index in panelMetrics.visibleItemRange(catalog.panelApps.size, panelScroll)) {
            val entry = catalog.panelApps[index]
            val row = index / panelMetrics.columns
            val column = index % panelMetrics.columns
            val centerX =
                panelRect.left + panelMetrics.contentHorizontalPadding + cellWidth * (column + 0.5f)
            val centerY = panelRect.top + panelMetrics.topPadding + row * cellHeight + iconSize / 2 - panelScroll
            drawSystemBitmap(canvas, entry.icon, centerX, centerY, iconSize)
            val label = panelLabels.getOrElse(index) { entry.label }
            canvas.drawText(label, centerX, centerY + panelLabelBaselineOffset, labelPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!panelMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(timeout)
                postDelayed(timeout, PANEL_TIMEOUT_MS)
                panelScroller.abortAnimation()
                recycleVelocityTracker()
                if (!panelRect.contains(event.x, event.y)) {
                    requestDismiss()
                    return true
                }
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastTouchY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!moved) {
                    val verticalDistance = abs(event.y - downY)
                    val horizontalDistance = abs(event.x - downX)
                    if (verticalDistance <= touchSlop || verticalDistance < horizontalDistance) return true
                    moved = true
                    lastTouchY = downY + if (event.y > downY) touchSlop else -touchSlop
                }
                val delta = lastTouchY - event.y
                panelScroll = (panelScroll + delta).coerceIn(0f, panelMaxScroll)
                lastTouchY = event.y
                invalidatePanelOnAnimation()
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                if (!moved && panelRect.contains(event.x, event.y)) {
                    panelIndexAt(event.x, event.y)?.let { index ->
                        catalog.panelApps.getOrNull(index)?.let(listener::onAppCommitted)
                    }
                } else if (moved && panelMaxScroll > 0f) {
                    velocityTracker?.computeCurrentVelocity(1_000, maximumFlingVelocity.toFloat())
                    val scrollVelocity = -(velocityTracker?.yVelocity ?: 0f)
                    if (abs(scrollVelocity) >= minimumFlingVelocity.toFloat()) {
                        panelScroller.fling(
                            0,
                            panelScroll.toInt(),
                            0,
                            scrollVelocity.toInt(),
                            0,
                            0,
                            0,
                            panelMaxScroll.toInt(),
                        )
                        invalidatePanelOnAnimation()
                    }
                }
                recycleVelocityTracker()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                recycleVelocityTracker()
                return true
            }
        }
        return true
    }

    override fun computeScroll() {
        super.computeScroll()
        if (!panelScroller.computeScrollOffset()) return
        panelScroll = panelScroller.currY.toFloat().coerceIn(0f, panelMaxScroll)
        invalidatePanelOnAnimation()
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (panelMode && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            requestDismiss()
            return true
        }
        return super.dispatchKeyEventPreIme(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (panelMode && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            requestDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        val delta =
            if (lastFrameNanos == 0L) FIRST_FRAME_SECONDS
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameNanos = frameTimeNanos
        var radialExitCompleted = false
        if (ValueAnimator.areAnimatorsEnabled()) {
            if (radialExitRunning) {
                radialExitElapsedSeconds += delta
                radialExitVisuals =
                    RadialExitMotion.sample(
                        radialExitElapsedSeconds / RadialExitMotion.DURATION_SECONDS,
                    )
                if (radialExitElapsedSeconds >= RadialExitMotion.DURATION_SECONDS) {
                    radialExitRunning = false
                    radialExitCompleted = true
                }
            } else {
                reveal.step(delta)
                panel.step(delta)
                itemScales.forEach { it.step(delta) }
            }
        } else {
            if (radialExitRunning) {
                radialExitVisuals = RadialExitMotion.sample(1f)
                radialExitRunning = false
                radialExitCompleted = true
            } else {
                reveal.snapTo(reveal.target)
                panel.snapTo(panel.target)
                itemScales.forEach { it.snapTo(it.target) }
            }
        }
        invalidate()
        if (radialExitCompleted) {
            completeRadialExit()
            return
        }
        val running =
            radialExitRunning ||
                !reveal.isAtRest ||
                !panel.isAtRest ||
                itemScales.any { !it.isAtRest }
        if (running) {
            scheduleFrame()
        } else {
            lastFrameNanos = 0L
            if (reveal.value == 0f && panel.value == 0f) requestDismiss()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        panelScroller.abortAnimation()
        recycleVelocityTracker()
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    private fun updateLayout() {
        if (width <= 0 || height <= 0) return
        val metrics =
            AdaptiveOverlayGeometry.calculate(
                width = width.toFloat(),
                height = height.toFloat(),
                safeInsets = currentSafeInsets(),
                systemIconSize = systemIconSize(),
                fontScale = resources.configuration.fontScale,
                panelItemCount = catalog.panelApps.size,
                anchorOnLeft = side == CornerSide.Left,
            )
        visualMetrics = metrics
        layout =
            RadialGeometry.layout(
                side,
                width.toFloat(),
                height.toFloat(),
                metrics.radial.radius,
                catalog.radialApps.size + 1,
            )
        val bounds = metrics.panel.bounds
        panelRect.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
        panelMaxScroll = metrics.panel.maxScroll
        panelScroll = panelScroll.coerceIn(0f, panelMaxScroll)
        labelPaint.textSize = metrics.panel.labelTextSize
        panelLabelBaselineOffset = metrics.panel.labelTopOffset - labelPaint.fontMetrics.ascent
        val contentWidth = panelRect.width() - metrics.panel.contentHorizontalPadding * 2
        val labelMaxWidth =
            contentWidth / metrics.panel.columns - metrics.panel.horizontalTextPadding * 2
        panelLabels = catalog.panelApps.map { entry -> ellipsize(entry.label, labelPaint, labelMaxWidth) }
    }

    private fun currentSafeInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        val drawingInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime(),
            )
        val gestureInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemGestures() or
                    WindowInsets.Type.mandatorySystemGestures(),
            )
        val topLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        val topRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
        val bottomLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
        val bottomRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
        return OverlaySafeInsets(
            left =
                maxOf(
                    drawingInsets.left,
                    gestureInsets.left,
                ).toFloat(),
            top =
                maxOf(
                    drawingInsets.top,
                    gestureInsets.top,
                    topLeft?.radius ?: 0,
                    topRight?.radius ?: 0,
                ).toFloat(),
            right =
                maxOf(
                    drawingInsets.right,
                    gestureInsets.right,
                ).toFloat(),
            bottom =
                maxOf(
                    drawingInsets.bottom,
                    gestureInsets.bottom,
                    bottomLeft?.radius ?: 0,
                    bottomRight?.radius ?: 0,
                ).toFloat(),
        )
    }

    private fun systemIconSize(): Float =
        try {
            context
                .getSystemService(ActivityManager::class.java)
                ?.launcherLargeIconSize
                ?.toFloat()
                ?: 0f
        } catch (_: RuntimeException) {
            0f
        }

    private fun panelIndexAt(x: Float, y: Float): Int? {
        val panelMetrics = visualMetrics?.panel ?: return null
        val contentLeft = panelRect.left + panelMetrics.contentHorizontalPadding
        val contentRight = panelRect.right - panelMetrics.contentHorizontalPadding
        if (x < contentLeft || x > contentRight) return null
        val cellWidth = (contentRight - contentLeft) / panelMetrics.columns
        val cellHeight = panelMetrics.cellHeight
        val column = ((x - contentLeft) / cellWidth).toInt().coerceIn(0, panelMetrics.columns - 1)
        val contentY = y - panelRect.top - panelMetrics.topPadding + panelScroll
        if (contentY < 0f) return null
        val row = (contentY / cellHeight).toInt()
        return (row * panelMetrics.columns + column).takeIf { it in catalog.panelApps.indices }
    }

    private fun drawSystemBitmap(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, size: Float) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val aspectRatio = bitmap.width.toFloat() / bitmap.height
        val drawWidth = if (aspectRatio >= 1f) size else size * aspectRatio
        val drawHeight = if (aspectRatio >= 1f) size / aspectRatio else size
        iconDestination.set(
            x - drawWidth / 2,
            y - drawHeight / 2,
            x + drawWidth / 2,
            y + drawHeight / 2,
        )
        canvas.drawBitmap(bitmap, null, iconDestination, iconPaint)
    }

    private fun drawRadialAppIcon(
        canvas: Canvas,
        bitmap: Bitmap,
        x: Float,
        y: Float,
        plateDiameter: Float,
        selected: Boolean,
    ) {
        val maskDiameter = radialIconStyle.maskDiameter(plateDiameter)
        val maskRadius = maskDiameter / 2f
        canvas.drawCircle(x, y, maskRadius, if (selected) selectedPaint else platePaint)
        iconClipPath.rewind()
        iconClipPath.addCircle(x, y, maskRadius, Path.Direction.CW)
        val checkpoint = canvas.save()
        canvas.clipPath(iconClipPath)
        drawSystemBitmap(
            canvas = canvas,
            bitmap = bitmap,
            x = x,
            y = y,
            size = radialIconStyle.contentDiameter(plateDiameter),
        )
        canvas.restoreToCount(checkpoint)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun invalidatePanelOnAnimation() {
        postInvalidateOnAnimation(
            panelRect.left.toInt(),
            panelRect.top.toInt(),
            panelRect.right.toInt() + 1,
            panelRect.bottom.toInt() + 1,
        )
    }

    private fun updateColors(configuration: Configuration) {
        val dark =
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        panelPaint.color = if (dark) Color.rgb(36, 36, 39) else Color.rgb(248, 248, 250)
        labelPaint.color = if (dark) Color.rgb(242, 242, 244) else Color.rgb(35, 35, 38)
    }

    private fun requestDismiss() {
        if (dismissNotified) return
        dismissNotified = true
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        listener.onDismissRequested()
    }

    private fun completeRadialExit() {
        if (dismissNotified) return
        radialExitRunning = false
        removeCallbacks(dismissFallback)
        val pendingCommit = pendingRadialCommit
        pendingRadialCommit = null
        if (pendingCommit == null) {
            requestDismiss()
        } else {
            dismissNotified = true
            removeCallbacks(timeout)
            listener.onAppCommitted(pendingCommit)
        }
    }

    private companion object {
        const val MAX_RADIAL_ITEMS = 7
        const val MAX_SCRIM_ALPHA = 105
        const val MORE_DOT_RADIUS_FRACTION = 0.052f
        const val MORE_DOT_SPACING_FRACTION = 0.17f
        const val TICK_INTERVAL_MS = 80L
        const val GESTURE_TIMEOUT_MS = 5_000L
        const val PANEL_TIMEOUT_MS = 15_000L
        const val DISMISS_FALLBACK_MS = 400L
        const val REVEAL_RESPONSE_SECONDS = 0.12f
        const val FIRST_FRAME_SECONDS = 1f / 120f
    }
}
