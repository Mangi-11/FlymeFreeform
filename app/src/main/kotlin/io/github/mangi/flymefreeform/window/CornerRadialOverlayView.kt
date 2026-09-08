package io.github.mangi.flymefreeform.window

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.RadialGeometry
import io.github.mangi.flymefreeform.gesture.RadialLayout
import io.github.mangi.flymefreeform.platform.coloros.AppCatalogSnapshot
import io.github.mangi.flymefreeform.platform.coloros.RadialAppEntry
import io.github.mangi.flymefreeform.ui.theme.CornerOverlayTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class CornerRadialOverlayView(
    context: Context,
    private val listener: Listener,
) : AbstractComposeView(context), LifecycleOwner, SavedStateRegistryOwner {
    interface Listener {
        fun onAppCommitted(entry: RadialAppEntry)

        fun onMorePanelRequested()

        fun onDismissRequested()

        fun onCleanupFailed(throwable: Throwable)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val metricsState = mutableStateOf<AdaptiveOverlayMetrics?>(null)
    private val layoutState = mutableStateOf(EMPTY_LAYOUT)
    private val selectedIndexState = mutableIntStateOf(NO_SELECTION)
    private val panelModeState = mutableStateOf(false)
    private val backdropOnlyState = mutableStateOf(false)
    private val backdropAlpha = mutableFloatStateOf(1f)
    private var backdropAnimator: ValueAnimator? = null
    private val exitRequestState = mutableStateOf<ExitRequest?>(null)
    private val entryProgress = Animatable(0f)
    private val handoffEntryProgress = mutableFloatStateOf(0f)
    private val handoffLayoutState = mutableStateOf(EMPTY_LAYOUT)
    private val handoffMetricsState = mutableStateOf<RadialVisualMetrics?>(null)
    private var appliedWindowInsets: WindowInsets? = null
    private var catalog = AppCatalogSnapshot()
    private val radialClipPath = Path()
    private val radialImages = mutableStateOf<List<ImageBitmap>>(emptyList())
    private var panelImages: Map<android.content.ComponentName, ImageBitmap> = emptyMap()
    private var side = CornerSide.Right
    private var latestX = 0f
    private var latestY = 0f
    private var selectedIndex: Int? = null
    private var dismissing = false
    private var dismissNotified = false
    private var disposed = false
    private var lastTickUptime = 0L
    private val timeout = Runnable(::requestDismiss)
    private val dismissFallback = Runnable(::completeRadialExit)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun updateRadialAppearance(next: AppCatalogSnapshot) {
        if (disposed || dismissing || panelModeState.value) return
        // 只替换同一批应用的图像，不在滑选途中交换目标或重置入场动画。
        if (catalog.radialApps.map { it.component } != next.radialApps.map { it.component }) return
        radialImages.value = next.radialApps.map { it.icon.asImageBitmap() }
    }

    fun begin(
        side: CornerSide,
        catalog: AppCatalogSnapshot,
        x: Float,
        y: Float,
    ) {
        check(!isAttachedToWindow) { "Overlay must be initialized before it is attached" }
        this.side = side
        this.catalog = catalog
        latestX = x
        latestY = y
        radialImages.value = catalog.radialApps.map { entry -> entry.icon.asImageBitmap() }
        panelImages = catalog.panelApps.associate { entry -> entry.component to entry.icon.asImageBitmap() }
        selectedIndex = null
        selectedIndexState.intValue = NO_SELECTION
        handoffEntryProgress.floatValue = 0f
        handoffLayoutState.value = EMPTY_LAYOUT
        handoffMetricsState.value = null
        panelModeState.value = false
        exitRequestState.value = null
        dismissing = false
        dismissNotified = false
        val displayMetrics = resources.displayMetrics
        updateLayout(
            requestedWidth = displayMetrics.widthPixels,
            requestedHeight = displayMetrics.heightPixels,
            safeInsets = OverlaySafeInsets(),
        )
        removeCallbacks(timeout)
        postDelayed(timeout, GESTURE_TIMEOUT_MS)
    }

    fun updateGesture(x: Float, y: Float): Int? {
        latestX = x
        latestY = y
        if (panelModeState.value || dismissing) return selectedIndex
        removeCallbacks(timeout)
        postDelayed(timeout, GESTURE_TIMEOUT_MS)
        updateGestureFromLatestPoint()
        return selectedIndex
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

    fun dismissAnimated() = dismissAnimated(pendingCommit = null)

    fun disposeOverlay(): Throwable? {
        if (disposed) return null
        disposed = true
        backdropAnimator?.cancel()
        backdropAnimator = null
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        var failure: Throwable? = null
        try {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            disposeComposition()
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        return failure
    }

    @Composable
    override fun Content() {
        CornerOverlayTheme {
            val metrics = metricsState.value
            val layout = layoutState.value
            if (metrics != null) {
                OverlayContent(metrics = metrics, layout = layout)
            }
        }
    }

    @Composable
    private fun OverlayContent(
        metrics: AdaptiveOverlayMetrics,
        layout: RadialLayout,
    ) {
        val panelProgress = remember { Animatable(0f) }
        val panelContentProgress = remember { Animatable(0f) }
        val radialHandoffProgress = remember { Animatable(0f) }
        val exitProgress = remember { Animatable(0f) }
        val panelInputEnabled = remember { mutableStateOf(false) }
        val itemRings =
            remember(layout.itemCenters.size) {
                List(layout.itemCenters.size) { Animatable(0f) }
            }
        val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }

        val exitRequest = exitRequestState.value
        LaunchedEffect(panelModeState.value, exitRequest, backdropOnlyState.value) {
            if (panelModeState.value || exitRequest != null || backdropOnlyState.value) return@LaunchedEffect
            if (!animationsEnabled) {
                entryProgress.snapTo(1f)
            } else {
                entryProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(RadialEntryMotion.DURATION_MILLIS.toInt(), easing = LinearEasing),
                )
            }
        }
        LaunchedEffect(selectedIndexState.intValue, itemRings) {
            val selected = selectedIndexState.intValue
            coroutineScope {
                itemRings.forEachIndexed { index, ring ->
                    launch {
                        val target = if (index == selected) 1f else 0f
                        if (!animationsEnabled) {
                            ring.snapTo(target)
                        } else {
                            ring.animateTo(
                                targetValue = target,
                                animationSpec = tween(
                                    RadialEntryMotion.SELECTION_DURATION_MILLIS.toInt(),
                                    easing = { RadialEntryMotion.selectionProgress(it) },
                                ),
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(panelModeState.value) {
            if (panelModeState.value) {
                panelInputEnabled.value = false
                if (!animationsEnabled) {
                    radialHandoffProgress.snapTo(1f)
                    panelProgress.snapTo(1f)
                    panelContentProgress.snapTo(1f)
                    panelInputEnabled.value = true
                } else {
                    coroutineScope {
                        launch {
                            radialHandoffProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = RadialHandoffMotion.DURATION_MILLIS.toInt(),
                                        easing = LinearEasing,
                                    ),
                            )
                        }
                        launch {
                            panelProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    folmeSpring(
                                        damping = PANEL_EXPAND_DAMPING,
                                        response = PANEL_EXPAND_RESPONSE_SECONDS,
                                        visibilityThreshold = PANEL_VISIBILITY_THRESHOLD,
                                    ),
                            )
                        }
                        launch {
                            panelContentProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = PANEL_CONTENT_FADE_MILLIS,
                                        delayMillis = PANEL_CONTENT_DELAY_MILLIS,
                                        easing = LinearOutSlowInEasing,
                                    ),
                            )
                            panelInputEnabled.value = true
                        }
                    }
                }
            }
        }
        LaunchedEffect(exitRequest) {
            if (exitRequest != null) {
                exitProgress.snapTo(0f)
                exitProgress.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = RadialDismissMotion.DURATION_MILLIS.toInt(),
                            easing = LinearEasing,
                        ),
                )
                completeRadialExit()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val radialLayout = if (panelModeState.value) handoffLayoutState.value else layout
            val radialMetrics =
                if (panelModeState.value) {
                    handoffMetricsState.value ?: metrics.radial
                } else {
                    metrics.radial
                }
            RadialCanvas(
                metrics = radialMetrics,
                layout = radialLayout,
                panelProgress = panelProgress,
                radialHandoffProgress = radialHandoffProgress,
                exitProgress = exitProgress,
                itemRings = itemRings,
                animationsEnabled = animationsEnabled,
            )
            if (panelModeState.value) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    requestDismiss()
                                }
                            },
                )
                MoreAppsPanel(
                    metrics = metrics.panel,
                    panelProgress = panelProgress,
                    contentProgress = panelContentProgress,
                    inputEnabled = panelInputEnabled.value,
                    animationsEnabled = animationsEnabled,
                )
            }
        }
    }

    @Composable
    private fun RadialCanvas(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        panelProgress: Animatable<Float, *>,
        radialHandoffProgress: Animatable<Float, *>,
        exitProgress: Animatable<Float, *>,
        itemRings: List<Animatable<Float, *>>,
        animationsEnabled: Boolean,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            if (backdropOnlyState.value) {
                drawRect(Color.Black, alpha = OverlayBackdrop.MAX_ALPHA * backdropAlpha.floatValue)
                return@Canvas
            }
            val panel =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    panelProgress.value.coerceIn(0f, 1f)
                }
            val handoff =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    radialHandoffProgress.value
                }
            val exitRequest = exitRequestState.value
            val entrySample = RadialEntryMotion.sample(
                when {
                    !animationsEnabled -> 1f
                    exitRequest != null -> exitRequest.entryProgress
                    panelModeState.value -> handoffEntryProgress.floatValue
                    else -> entryProgress.value
                },
            )
            val exitSample = RadialDismissMotion.sample(exitProgress.value)
            val handoffVisuals = RadialHandoffMotion.sample(
                frozenRevealProgress = entrySample.contentAlpha,
                handoffProgress = handoff,
                panelProgress = panel,
            )
            // 提早松手时从当前入场帧衔接退出，不跳到完整展开位置。
            val dismissalBlend = exitProgress.value.coerceIn(0f, 1f)
            val dismissal = if (exitRequest == null) null else RadialEntryVisuals(
                contentAlpha = entrySample.contentAlpha * exitSample.contentAlpha,
                radialProgress = exitSample.radialProgress + (entrySample.radialProgress - 1f) * (1f - dismissalBlend),
                horizontalOvershoot = exitSample.horizontalOvershoot + entrySample.horizontalOvershoot * (1f - dismissalBlend),
                rotationDegrees = exitSample.rotationDegrees + entrySample.rotationDegrees * (1f - dismissalBlend),
                iconScale = exitSample.iconScale + (entrySample.iconScale - 1f) * (1f - dismissalBlend),
            )
            val visual = dismissal ?: entrySample
            val scrimAlpha = if (panelModeState.value) handoffVisuals.scrimProgress else visual.contentAlpha
            drawRect(Color.Black, alpha = OverlayBackdrop.MAX_ALPHA * scrimAlpha)
            val alpha = visual.contentAlpha * if (panelModeState.value) handoffVisuals.contentAlpha else 1f
            if (alpha <= 0f) return@Canvas
            drawRadialItems(
                metrics = metrics,
                layout = layout,
                motion = visual,
                contentAlpha = alpha,
                contentScale = if (panelModeState.value) handoffVisuals.contentScale else 1f,
                itemRings = itemRings,
            )
        }
    }

    private fun DrawScope.drawRadialItems(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        motion: RadialEntryVisuals,
        contentAlpha: Float,
        contentScale: Float,
        itemRings: List<Animatable<Float, *>>,
    ) {
        val direction = if (layout.side == CornerSide.Left) 1f else -1f
        val overshoot = direction * RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP * metrics.pixelsPerBaseDp * motion.horizontalOvershoot
        layout.itemCenters.forEachIndexed { index, destination ->
            val centerX = layout.origin.x + (destination.x - layout.origin.x) * motion.radialProgress + overshoot
            val centerY = layout.origin.y + (destination.y - layout.origin.y) * motion.radialProgress
            val scale = motion.iconScale * contentScale
            val ringProgress = itemRings.getOrNull(index)?.value ?: 0f
            val diameter = metrics.iconDiameter * scale
            rotate(motion.rotationDegrees, pivot = Offset(centerX, centerY)) {
                if (index < catalog.radialApps.size) {
                    radialImages.value.getOrNull(index)?.let { image ->
                        drawSystemImage(image, centerX, centerY, diameter, contentAlpha)
                    }
                } else {
                    drawMoreItem(centerX, centerY, diameter, contentAlpha)
                }
                val strokeWidth = metrics.itemPadding * scale * ringProgress
                if (strokeWidth > 0f) {
                    drawCircle(
                        color = Color.White,
                        radius = diameter / 2f + strokeWidth / 2f,
                        center = Offset(centerX, centerY),
                        alpha = RadialEntryMotion.SELECTION_RING_ALPHA * contentAlpha,
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
        }
    }

    private fun DrawScope.drawMoreItem(
        centerX: Float,
        centerY: Float,
        diameter: Float,
        alpha: Float,
    ) {
        drawCircle(
            color = Color.White,
            radius = diameter / 2f,
            center = Offset(centerX, centerY),
            alpha = alpha * PLATE_ALPHA,
        )
        val dotColor = Color(0xFF37373C)
        for (offset in -1..1) {
            drawCircle(
                color = dotColor,
                radius = diameter * MORE_DOT_RADIUS_FRACTION,
                center = Offset(centerX + offset * diameter * MORE_DOT_SPACING_FRACTION, centerY),
                alpha = alpha,
            )
        }
    }

    private fun DrawScope.drawSystemImage(
        image: ImageBitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float,
    ) {
        if (image.width <= 0 || image.height <= 0 || size <= 0f) return
        val aspectRatio = image.width.toFloat() / image.height
        val drawWidth = if (aspectRatio >= 1f) size else size * aspectRatio
        val drawHeight = if (aspectRatio >= 1f) size / aspectRatio else size
        val destinationSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1))
        radialClipPath.rewind()
        radialClipPath.addOval(Rect(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f))
        clipPath(radialClipPath) {
            drawImage(
                image = image,
                dstOffset =
                    IntOffset(
                        (centerX - destinationSize.width / 2f).roundToInt(),
                        (centerY - destinationSize.height / 2f).roundToInt(),
                    ),
                dstSize = destinationSize,
                alpha = alpha,
                filterQuality = FilterQuality.High,
            )
        }
    }

    @Composable
    private fun MoreAppsPanel(
        metrics: PanelVisualMetrics,
        panelProgress: Animatable<Float, *>,
        contentProgress: Animatable<Float, *>,
        inputEnabled: Boolean,
        animationsEnabled: Boolean,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val squircleEnabled = isSquircleEnabled()
        val panelColor = MiuixTheme.colorScheme.surface.copy(alpha = PANEL_SURFACE_ALPHA)
        val labelColor = MiuixTheme.colorScheme.onSurface
        val bounds = metrics.bounds
        val width = with(density) { bounds.width.toDp() }
        val height = with(density) { bounds.height.toDp() }
        val cornerRadius = with(density) { metrics.cornerRadius.toDp() }
        val horizontalPadding = with(density) { metrics.contentHorizontalPadding.toDp() }
        val verticalPadding = with(density) { metrics.topPadding.toDp() }
        val cellHeight = with(density) { metrics.cellHeight.toDp() }
        val iconSize = with(density) { metrics.iconDiameter.toDp() }
        val labelGap = with(density) { (metrics.labelTopOffset - metrics.iconDiameter / 2f).toDp() }
        val labelSize = with(density) { metrics.labelTextSize.toSp() }

        Box(
            modifier =
                Modifier
                    .absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .requiredSize(width = width, height = height)
                    .panelReveal(
                        progress = {
                            if (animationsEnabled) panelProgress.value else 1f
                        },
                        seedSize = metrics.iconDiameter,
                        cornerRadius = metrics.cornerRadius,
                        anchorOnLeft = side == CornerSide.Left,
                        squircleEnabled = squircleEnabled,
                    )
                    .squircleSurface(color = panelColor, cornerRadius = cornerRadius)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            resetPanelTimeout()
                            waitForUpOrCancellation()
                        }
                    },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(metrics.columns),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress =
                                if (animationsEnabled) {
                                    contentProgress.value.coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                            alpha = progress
                            translationY =
                                (1f - progress) *
                                    metrics.iconDiameter *
                                    PANEL_CONTENT_TRANSLATION_FRACTION
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                contentPadding =
                    PaddingValues(
                        horizontal = horizontalPadding,
                        vertical = verticalPadding,
                    ),
                verticalArrangement = Arrangement.Top,
                userScrollEnabled = inputEnabled,
            ) {
                items(
                    items = catalog.panelApps,
                    key = { entry -> entry.component.flattenToShortString() },
                ) { entry ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(cellHeight)
                                .clickable(enabled = inputEnabled) {
                                    listener.onAppCommitted(entry)
                                },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        panelImages[entry.component]?.let { image ->
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize),
                                filterQuality = FilterQuality.High,
                            )
                        }
                        Spacer(modifier = Modifier.height(labelGap))
                        Text(
                            text = entry.label,
                            modifier = Modifier.fillMaxWidth(),
                            color = labelColor,
                            fontSize = labelSize,
                            letterSpacing = 0.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!disposed && !lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        post {
            if (!disposed && isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }
        }
    }

    override fun onDetachedFromWindow() {
        val cleanupFailure = disposeOverlay()
        super.onDetachedFromWindow()
        cleanupFailure?.let(listener::onCleanupFailed)
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
        updateLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        appliedWindowInsets = insets
        val result = super.onApplyWindowInsets(insets)
        updateLayout()
        return result
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEventPreIme(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(event: KeyEvent): Boolean {
        if (!panelModeState.value || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_UP) requestDismiss()
        return true
    }

    private fun updateLayout() {
        if (width <= 0 || height <= 0 || disposed) return
        updateLayout(
            requestedWidth = width,
            requestedHeight = height,
            safeInsets = currentSafeInsets(),
        )
    }

    private fun updateLayout(
        requestedWidth: Int,
        requestedHeight: Int,
        safeInsets: OverlaySafeInsets,
    ) {
        if (requestedWidth <= 0 || requestedHeight <= 0 || disposed) return
        val radialInsets = currentRadialInsets()
        val safeWidth = (requestedWidth - radialInsets.left - radialInsets.right).coerceAtLeast(1f)
        val safeHeight = (requestedHeight - radialInsets.top - radialInsets.bottom).coerceAtLeast(1f)
        val systemIconDiameter = systemIconSize()
        val metrics =
            AdaptiveOverlayGeometry.calculate(
                width = requestedWidth.toFloat(),
                height = requestedHeight.toFloat(),
                safeInsets = safeInsets,
                systemIconSize = systemIconDiameter,
                density = resources.displayMetrics.density,
                radialItemCount = catalog.radialApps.size + 1,
                radialInsets = radialInsets,
                fontScale = resources.configuration.fontScale,
                panelItemCount = catalog.panelApps.size,
                anchorOnLeft = side == CornerSide.Left,
            )
        metricsState.value = metrics
        layoutState.value =
            RadialGeometry.layout(
                side = side,
                width = safeWidth,
                height = safeHeight,
                offsetX = radialInsets.left,
                offsetY = radialInsets.top,
                radius = metrics.radial.radius,
                itemCount = catalog.radialApps.size + 1,
            )
        if (!panelModeState.value && !dismissing) updateGestureFromLatestPoint()
    }

    private fun updateGestureFromLatestPoint() {
        val metrics = metricsState.value ?: return
        val layout = layoutState.value
        if (layout.itemCenters.isEmpty()) return
        val next =
            RadialGeometry.selection(
                layout = layout,
                x = latestX,
                y = latestY,
                previous = selectedIndex,
                enterRadius = metrics.radial.selectionEnterRadius,
                keepRadius = metrics.radial.selectionKeepRadius,
            )
        if (next == selectedIndex) return
        selectedIndex = next
        selectedIndexState.intValue = next ?: NO_SELECTION
        val now = SystemClock.uptimeMillis()
        if (next != null && now - lastTickUptime >= TICK_INTERVAL_MS) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastTickUptime = now
        }
    }

    private fun showMorePanel() {
        if (dismissing || panelModeState.value) return
        selectedIndex = null
        handoffEntryProgress.floatValue = entryProgress.value
        handoffLayoutState.value = layoutState.value
        handoffMetricsState.value = metricsState.value?.radial
        removeCallbacks(timeout)
        listener.onMorePanelRequested()
    }

    fun showBuiltInMorePanel() {
        if (disposed || !isAttachedToWindow) return
        backdropOnlyState.value = false
        panelModeState.value = true
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
        requestFocus()
    }

    fun retainBackdropForPanel() {
        backdropAlpha.floatValue = 1f
        backdropOnlyState.value = true
    }

    fun beginBackdropExit() {
        if (disposed || !backdropOnlyState.value) return
        backdropAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            backdropAlpha.floatValue = 0f
            return
        }
        backdropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            addUpdateListener {
                val alpha = AllAppsPanelMotion.exit(it.currentPlayTime / 1000f).alpha
                backdropAlpha.floatValue = alpha
                if (alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD) {
                    backdropAlpha.floatValue = 0f
                    cancel()
                }
            }
            start()
        }
    }

    fun hideBackdropAfterFrame(onHidden: () -> Unit) {
        backdropAnimator?.cancel()
        backdropAnimator = null
        if (disposed || !isAttachedToWindow) {
            onHidden()
            return
        }
        // 必须等透明帧提交后才让截图等工具执行，不能只确认状态变量已经更新。
        if (isHardwareAccelerated) {
            viewTreeObserver.registerFrameCommitCallback { post { onHidden() } }
        } else {
            postOnAnimation { postOnAnimation { onHidden() } }
        }
        backdropAlpha.floatValue = 0f
        invalidate()
    }

    private fun dismissAnimated(pendingCommit: RadialAppEntry?) {
        if (dismissing || disposed) return
        dismissing = true
        removeCallbacks(timeout)
        exitRequestState.value = ExitRequest(pendingCommit, entryProgress.value)
        removeCallbacks(dismissFallback)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            completeRadialExit()
        } else {
            val durationScale = ValueAnimator.getDurationScale().coerceAtLeast(1f)
            val fallbackDelay =
                (RadialDismissMotion.DURATION_MILLIS * durationScale + DISMISS_FALLBACK_GRACE_MS)
                    .roundToInt()
                    .toLong()
            postDelayed(dismissFallback, fallbackDelay)
        }
    }

    private fun completeRadialExit() {
        if (dismissNotified || disposed) return
        removeCallbacks(dismissFallback)
        val pendingCommit = exitRequestState.value?.pendingCommit
        exitRequestState.value = null
        if (pendingCommit == null) {
            requestDismiss()
        } else {
            dismissNotified = true
            removeCallbacks(timeout)
            listener.onAppCommitted(pendingCommit)
        }
    }

    private fun resetPanelTimeout() {
        if (!panelModeState.value || disposed) return
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
    }

    private fun requestDismiss() {
        if (dismissNotified || disposed) return
        dismissNotified = true
        removeCallbacks(timeout)
        removeCallbacks(dismissFallback)
        listener.onDismissRequested()
    }

    private fun mergeCleanupFailure(
        current: Throwable?,
        next: Throwable,
    ): Throwable =
        current?.also { previous -> previous.addSuppressed(next) } ?: next

    private fun currentRadialInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        // 扇形属于角落手势本身；系统手势保留区及屏幕圆角不是整页绘制边距。
        // 只避让实际系统栏、缺口和键盘，正常竖屏圆心落在物理侧边与导航栏顶边。
        val insets = windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
        )
        return OverlaySafeInsets(insets.left.toFloat(), insets.top.toFloat(), insets.right.toFloat(), insets.bottom.toFloat())
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
            left = maxOf(drawingInsets.left, gestureInsets.left).toFloat(),
            top =
                maxOf(
                    drawingInsets.top,
                    gestureInsets.top,
                    topLeft?.radius ?: 0,
                    topRight?.radius ?: 0,
                ).toFloat(),
            right = maxOf(drawingInsets.right, gestureInsets.right).toFloat(),
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

    private data class ExitRequest(val pendingCommit: RadialAppEntry?, val entryProgress: Float)

    private companion object {
        val EMPTY_LAYOUT =
            RadialLayout(
                side = CornerSide.Right,
                origin = io.github.mangi.flymefreeform.gesture.GesturePoint(0f, 0f),
                radius = 0f,
                itemCenters = emptyList(),
            )
        const val NO_SELECTION = -1
        const val PLATE_ALPHA = 235f / 255f
        const val PANEL_SURFACE_ALPHA = 253f / 255f
        const val MORE_DOT_RADIUS_FRACTION = 0.052f
        const val MORE_DOT_SPACING_FRACTION = 0.17f
        const val PANEL_EXPAND_DAMPING = 0.9f
        const val PANEL_EXPAND_RESPONSE_SECONDS = 0.3f
        const val PANEL_VISIBILITY_THRESHOLD = 0.0001f
        const val PANEL_CONTENT_DELAY_MILLIS = 45
        const val PANEL_CONTENT_FADE_MILLIS = 180
        const val PANEL_CONTENT_TRANSLATION_FRACTION = 0.12f
        const val TICK_INTERVAL_MS = 80L
        const val GESTURE_TIMEOUT_MS = 5_000L
        const val PANEL_TIMEOUT_MS = 15_000L
        const val DISMISS_FALLBACK_GRACE_MS = 260L
    }
}

private fun Modifier.panelReveal(
    progress: () -> Float,
    seedSize: Float,
    cornerRadius: Float,
    anchorOnLeft: Boolean,
    squircleEnabled: Boolean,
): Modifier =
    drawWithCache {
        val revealPath = Path()
        onDrawWithContent {
            val revealProgress = progress()
            val revealWidth = PanelRevealMotion.extent(size.width, seedSize, revealProgress)
            val revealHeight = PanelRevealMotion.extent(size.height, seedSize, revealProgress)
            val revealLeft =
                PanelRevealMotion.horizontalOffset(size.width, revealWidth, anchorOnLeft)
            val revealTop = PanelRevealMotion.verticalOffset(size.height, revealHeight)
            revealPath.rewind()
            revealPath.addSquircleRect(
                width = revealWidth,
                height = revealHeight,
                cornerRadius = cornerRadius,
                squircleEnabled = squircleEnabled,
            )
            translate(left = revealLeft, top = revealTop) {
                clipPath(revealPath) {
                    translate(left = -revealLeft, top = -revealTop) {
                        this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
    }
