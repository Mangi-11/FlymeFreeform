package io.github.mangi.flymefreeform.platform.coloros

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.mangi.flymefreeform.window.AllAppsPanelGeometry
import io.github.mangi.flymefreeform.window.AllAppsPanelMotion

/** 独立窗口只承载本次新建的全部内容；不挂接原侧栏父视图，也不改变其状态机。 */
internal class ColorOsAllAppsWindow(
    private val context: Context,
    val content: ColorOsAllAppsContent,
    private val onShown: () -> Unit,
    private val onClosed: () -> Unit,
    private val onExitStarted: () -> Unit,
    private val beforeTool: (() -> Unit) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val card = content.createCard()
    private var attached = false
    private var closed = false
    private var exiting = false
    private var entering = true
    private var animationStart = 0L
    private var durationScale = 1f
    private var notified = false
    private var hiddenAction: (() -> Unit)? = null
    private val dimensions = content.dimensions()
    private val mode = content.mode()
    private val leftSide = content.leftSide()
    private var insets: WindowInsets? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback { back() }
    private val animation = Runnable { animateFrame() }
    private val timeout = Runnable { dismiss() }
    private val firstFrame = ViewTreeObserver.OnPreDrawListener { prepareFirstFrame() }
    private var waitingForFirstFrame = true
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (exiting || entering) return true
            resetTimeout()
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            backDispatcher = findOnBackInvokedDispatcher()?.also {
                it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
            }
        }

        override fun onDetachedFromWindow() {
            backDispatcher?.unregisterOnBackInvokedCallback(backCallback)
            backDispatcher = null
            super.onDetachedFromWindow()
            if (!closed) dispose()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            placeCard()
        }
    }

    init {
        root.setBackgroundColor(0)
        root.isFocusableInTouchMode = true
        root.clipChildren = false
        card.alpha = 0f
        card.visibility = View.INVISIBLE
        content.view.alpha = 0f
        card.isClickable = true
        val metrics = manager.currentWindowMetrics
        val safe = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val initial = AllAppsPanelGeometry.calculate(
            metrics.bounds.width(), metrics.bounds.height(), dimensions, mode, leftSide,
            safe.left, safe.top, safe.right, safe.bottom,
        )
        root.addView(card, FrameLayout.LayoutParams(initial.width, initial.height).apply {
            leftMargin = initial.left
            topMargin = initial.top
            gravity = Gravity.TOP or Gravity.LEFT
        })
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) dismiss()
            true
        }
        root.setOnApplyWindowInsetsListener { _, value ->
            insets = value
            placeCard()
            value
        }
        content.bindClose { dismiss() }
    }

    fun show() {
        check(!attached && !closed)
        val params = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
            title = "FlymeFreeformAllApps"
        }
        manager.addView(root, params)
        attached = true
        root.viewTreeObserver.addOnPreDrawListener(firstFrame)
        root.requestApplyInsets()
        root.requestFocus()
    }

    private fun prepareFirstFrame(): Boolean {
        if (closed) return true
        if (!waitingForFirstFrame) return true
        return try {
            val layout = card.layoutParams as FrameLayout.LayoutParams
            if (root.width <= 0 || root.height <= 0 || insets == null ||
                card.isLayoutRequested || card.width != layout.width || card.height != layout.height ||
                card.left != layout.leftMargin || card.top != layout.topMargin || !content.attachBlur()
            ) return false
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            val initial = AllAppsPanelMotion.enter(0f)
            card.scaleX = initial.scale
            card.scaleY = initial.scale
            content.view.alpha = initial.alpha
            card.alpha = 1f
            card.visibility = View.VISIBLE
            waitingForFirstFrame = false
            root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
            startAnimation()
            resetTimeout()
            true
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
            true
        }
    }

    fun isShown(): Boolean = attached && !closed && !exiting && !entering &&
        root.isShown && card.width > 0 && content.view.childCount > 0

    fun beginItemClick(afterHidden: (() -> Unit)? = null): Boolean {
        if (!isShown()) return false
        hiddenAction = afterHidden
        dismiss()
        return true
    }

    fun dismiss() {
        if (closed || exiting) return
        exiting = true
        entering = false
        onExitStarted()
        root.removeCallbacks(timeout)
        if (waitingForFirstFrame) {
            dispose()
            return
        }
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        startAnimation()
    }

    fun dispose() {
        if (closed) return
        closed = true
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
        hiddenAction = null
        root.removeCallbacks(animation)
        root.removeCallbacks(timeout)
        try {
            content.close()
        } catch (exception: Exception) {
            onFailure(exception)
        } finally {
            if (attached) {
                attached = false
                try {
                    manager.removeViewImmediate(root)
                } catch (_: IllegalArgumentException) {
                    // 系统已移除窗口。
                }
            }
            root.removeAllViews()
            onClosed()
        }
    }

    private fun back() = safely {
        if (!exiting && !content.leaveSearch()) dismiss()
    }

    private fun placeCard() {
        if (closed || root.width <= 0 || root.height <= 0) return
        val safe = insets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        val bounds = AllAppsPanelGeometry.calculate(
            root.width, root.height, dimensions, mode, leftSide,
            safe?.left ?: 0, safe?.top ?: 0, safe?.right ?: 0, maxOf(safe?.bottom ?: 0, ime),
        )
        val params = card.layoutParams as FrameLayout.LayoutParams
        if (params.width == bounds.width && params.height == bounds.height &&
            params.leftMargin == bounds.left && params.topMargin == bounds.top
        ) return
        params.width = bounds.width
        params.height = bounds.height
        params.leftMargin = bounds.left
        params.topMargin = bounds.top
        params.gravity = Gravity.TOP or Gravity.LEFT
        card.layoutParams = params
    }

    private fun startAnimation() {
        root.removeCallbacks(animation)
        durationScale = ValueAnimator.getDurationScale()
        animationStart = SystemClock.uptimeMillis()
        if (!ValueAnimator.areAnimatorsEnabled() || durationScale <= 0f) {
            if (exiting) finishExit() else finishEnter()
            return
        }
        root.postOnAnimation(animation)
    }

    private fun animateFrame(): Unit = safely {
        if (closed) return@safely
        val elapsed = (SystemClock.uptimeMillis() - animationStart) / durationScale
        val frame = if (exiting) AllAppsPanelMotion.exit(elapsed / 1_000f) else AllAppsPanelMotion.enter(elapsed / 1_000f)
        // 材质底色不参与入场渐变，只有内容淡入；避免暗色桌面透入后再变亮。
        card.alpha = if (exiting) frame.alpha else 1f
        content.view.alpha = if (exiting) 1f else frame.alpha
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f
        card.scaleX = frame.scale
        card.scaleY = frame.scale
        content.updateBlur(if (exiting) frame.alpha else 1f)
        if (exiting && (frame.alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishExit()
        } else if (!exiting && ((frame.alpha >= 0.997f && frame.scale >= 0.997f) || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishEnter()
        } else {
            root.postOnAnimation(animation)
        }
    }

    private fun finishEnter() {
        entering = false
        card.alpha = 1f
        content.view.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
        content.updateBlur(1f)
        if (!notified) {
            notified = true
            onShown()
        }
    }

    private fun finishExit() {
        card.alpha = 0f
        root.setBackgroundColor(0)
        content.updateBlur(0f)
        val action = hiddenAction
        hiddenAction = null
        if (action == null) {
            dispose()
            return
        }
        // 先提交不可见帧，再执行原厂工具；短暂保留窗口所有权供系统判断前台发起者。
        root.postOnAnimation {
            safely {
                if (closed) return@safely
                beforeTool {
                    safely {
                        if (closed) return@safely
                        action()
                        root.postOnAnimation { if (!closed) dispose() }
                    }
                }
            }
        }
    }

    private fun resetTimeout() {
        root.removeCallbacks(timeout)
        root.postDelayed(timeout, 120_000L)
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
        }
    }
}
