package io.github.mangi.flymefreeform.platform.coloros

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.mangi.flymefreeform.apps.AppTarget
import io.github.mangi.flymefreeform.apps.identifier
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.GestureAction
import io.github.mangi.flymefreeform.hook.ModuleEnvironmentState
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.window.CornerRadialOverlayView
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** system_server 中的唯一长期所有者：WMS 指针监听、Overlay、目录缓存与启动适配。 */
internal class ColorOsFreeformCoordinator(
    private val controller: Any,
    private val classLoader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val environmentState: ModuleEnvironmentState,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) : CornerRadialOverlayView.Listener {
    private val context = readField(controller, "mContext") as Context
    private val handler = Handler(Looper.getMainLooper())
    private val catalogExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, CATALOG_THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val gestureEngine = CornerGestureEngine()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val launcher = ColorOsFreeformLauncher(context)
    private val sidebar = ColorOsSidebarClient(context, handler, logger)
    private val appCatalog =
        ColorOsAppCatalog(context, catalogExecutor, logger) { snapshot ->
            handler.post {
                // 后台任务完成时配置可能已再次变化，旧结果不得覆盖新外观。
                if (snapshot.matches(lastSettings)) {
                    catalogSnapshot = snapshot
                    overlay?.updateRadialAppearance(snapshot)
                }
            }
        }
    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (lastSettings.enabled) appCatalog.refresh(lastSettings)
            }
        }

    @Volatile
    private var catalogSnapshot = AppCatalogSnapshot()
    private var pointerListener: Any? = null
    @Volatile
    private var pointerRegistered = false
    @Volatile
    private var pointerGeneration = 0L
    private var overlay: CornerRadialOverlayView? = null
    private var morePanelActive = false
    private var lastSettings = ModuleSettingsSnapshot(enabled = false)
    private var activeEnvironmentApproved = false
    private var activeGestureConfig: CornerGestureConfig? = null
    private val pointerQueueLock = Any()
    private var pendingMove: QueuedPointerEvent? = null
    private var movePosted = false
    private var lastOverlayFailureLogAt = -OVERLAY_FAILURE_LOG_INTERVAL_MS

    fun start() {
        handler.post {
            environmentState.start(context)
            environmentState.observe { applySettings(configuration.snapshot) }
            registerPackageObserver()
            configuration.observe {
                handler.post { applySettings(configuration.snapshot) }
            }
        }
        logger(Log.INFO, "SYSTEM_GESTURE_COORDINATOR_READY", null)
    }

    private fun registerPackageObserver() {
        try {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
            // Coordinator 与 system_server 同生命周期；热重载被拒绝，因此只注册一次。
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PACKAGE_OBSERVER_UNAVAILABLE", exception)
        }
    }

    private fun applySettings(settings: ModuleSettingsSnapshot) {
        val selectionChanged =
            settings.pinsSaved != lastSettings.pinsSaved ||
                settings.pinnedTargets != lastSettings.pinnedTargets
        lastSettings = settings
        if (environmentState.isGestureAllowed(refreshKeyguard = true) && (settings.leftCornerEnabled || settings.rightCornerEnabled)) {
            val resuming = !pointerRegistered
            registerPointerListener()
            if (resuming || selectionChanged || !catalogSnapshot.matches(settings) || catalogSnapshot.radialApps.isEmpty()) {
                appCatalog.refresh(settings, reloadApps = resuming || selectionChanged || catalogSnapshot.radialApps.isEmpty())
            }
        } else {
            sidebar.cancel()
            unregisterPointerListener()
            activeEnvironmentApproved = false
            activeGestureConfig = null
            gestureEngine.cancel()
            removeOverlay()
        }
    }

    private fun registerPointerListener() {
        if (pointerRegistered) return
        val generation = pointerGeneration
        try {
            val listenerInterface =
                Class.forName(
                    "android.view.WindowManagerPolicyConstants\$PointerEventListener",
                    false,
                    classLoader,
                )
            val listener =
                Proxy.newProxyInstance(classLoader, arrayOf(listenerInterface)) { proxy, method, args ->
                    when (method.name) {
                        "onPointerEvent" -> {
                            val event = args?.firstOrNull() as? MotionEvent
                            if (event != null) enqueuePointerEvent(event, generation)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "FlymeFreeformPointerListener"
                        else -> null
                    }
                }
            val windowManagerService = readWindowManagerService()
            val register = findMethod(windowManagerService.javaClass, "registerPointerEventListener", 2)
            register.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
            pointerListener = listener
            pointerRegistered = true
            logger(Log.INFO, "SYSTEM_POINTER_LISTENER_REGISTERED", null)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_FAILED", exception)
        }
    }

    private fun enqueuePointerEvent(event: MotionEvent, generation: Long) {
        if (!pointerRegistered || generation != pointerGeneration) return
        val copy = QueuedPointerEvent(MotionEvent.obtain(event), generation)
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            val precedingMove =
                synchronized(pointerQueueLock) {
                    pendingMove.also { pendingMove = null }
                }
            handler.post {
                precedingMove?.let(::processPointerEvent)
                processPointerEvent(copy)
            }
            return
        }
        var shouldPost = false
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = copy
            if (!movePosted) {
                movePosted = true
                shouldPost = true
            }
        }
        if (shouldPost) handler.post(::drainPendingMove)
    }

    private fun drainPendingMove() {
        val event =
            synchronized(pointerQueueLock) {
                movePosted = false
                pendingMove.also { pendingMove = null }
            } ?: return
        processPointerEvent(event)
    }

    private fun processPointerEvent(queued: QueuedPointerEvent) {
        val event = queued.event
        try {
            if (queued.generation != pointerGeneration || !pointerRegistered) return
            handlePointerEvent(event)
        } catch (exception: RuntimeException) {
            gestureEngine.cancel()
            removeOverlay()
            unregisterPointerListener()
            logger(Log.ERROR, "SYSTEM_POINTER_PROCESSING_FAILED", exception)
        } finally {
            event.recycle()
        }
    }

    private fun unregisterPointerListener() {
        pointerGeneration++
        clearPendingMove()
        val listener = pointerListener ?: return
        pointerRegistered = false
        try {
            val windowManagerService = readWindowManagerService()
            val unregister = findMethod(windowManagerService.javaClass, "unregisterPointerEventListener", 2)
            unregister.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } finally {
            pointerListener = null
        }
    }

    private fun clearPendingMove() {
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = null
            movePosted = false
        }
    }

    private fun handlePointerEvent(event: MotionEvent) {
        if (morePanelActive) return
        val settings = lastSettings
        if (!pointerRegistered || !environmentState.isGestureAllowed()) {
            cancelActiveGesture()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activeEnvironmentApproved = isGestureEnvironmentAllowed()
            if (!activeEnvironmentApproved) {
                activeGestureConfig = null
                return
            }
        } else if (!activeEnvironmentApproved) {
            return
        }
        if (overlay != null && !isDynamicEnvironmentAllowed()) {
            activeEnvironmentApproved = false
            cancelActiveGesture()
            return
        }
        val metrics = context.resources.displayMetrics
        val config =
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                AdaptiveCornerGestureConfig.create(
                    displayWidth = metrics.widthPixels.toFloat(),
                    displayHeight = metrics.heightPixels.toFloat(),
                    touchSlop = touchSlop,
                    density = metrics.density,
                    triggerRangeDp = settings.cornerTriggerRangeDp,
                    leftEnabled = settings.leftCornerEnabled,
                    rightEnabled = settings.rightCornerEnabled,
                ).also { activeGestureConfig = it }
            } else {
                activeGestureConfig ?: return
            }
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    gestureEngine.down(event.getPointerId(0), event.x, event.y, config)
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
                    gestureEngine.move(
                        event.getPointerId(index),
                        event.pointerCount,
                        event.getX(index),
                        event.getY(index),
                        config,
                    )
                }
                MotionEvent.ACTION_UP -> gestureEngine.up(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> gestureEngine.cancel()
                else -> if (gestureEngine.isClaimed) gestureEngine.cancel() else GestureAction.Ignore
            }
        when (action) {
            is GestureAction.Activate ->
                showOverlay(
                    side = action.side,
                    x = action.x,
                    y = action.y,
                )
            is GestureAction.Update -> {
                val selected = overlay?.updateGesture(action.x, action.y)
                gestureEngine.setSelection(selected)
            }
            is GestureAction.Commit -> overlay?.finishGesture()
            GestureAction.Cancel -> overlay?.cancelGesture()
            GestureAction.Ignore, GestureAction.PassThrough -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeEnvironmentApproved = false
            activeGestureConfig = null
        }
    }

    private fun showOverlay(
        side: io.github.mangi.flymefreeform.gesture.CornerSide,
        x: Float,
        y: Float,
    ) {
        removeOverlay()
        morePanelActive = false
        val params = createOverlayParams(focusable = false)
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this)
            view.begin(
                side = side,
                catalog = catalogSnapshot,
                x = x,
                y = y,
            )
            overlay = view
            windowManager.addView(view, params)
        } catch (exception: RuntimeException) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", exception)
        } catch (error: LinkageError) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", error)
        }
    }

    private fun handleOverlayFailure(
        view: CornerRadialOverlayView?,
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        gestureEngine.cancel()
        if (view != null) {
            overlay = view
            removeOverlay()
        } else {
            overlay = null
        }
        logOverlayFailure(diagnosticCode, throwable)
    }

    private fun logOverlayFailure(
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayFailureLogAt >= OVERLAY_FAILURE_LOG_INTERVAL_MS) {
            lastOverlayFailureLogAt = now
            logger(Log.WARN, diagnosticCode, throwable)
        }
    }

    override fun onAppCommitted(entry: RadialAppEntry) {
        val generation = pointerGeneration
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
        handler.post {
            if (generation == pointerGeneration) launchCommittedApp(entry)
        }
    }

    private fun launchCommittedApp(entry: RadialAppEntry) {
        if (!isGestureEnvironmentAllowed()) return
        val decision = resolveParallelDecision(entry.target)
        val enabled = ParallelWindowPolicy.ENABLED
        val flexible = enabled && decision != ParallelWindowPolicy.Decision.Normal
        var newInstance = enabled && decision == ParallelWindowPolicy.Decision.FlexibleNewInstance
        var reuseTaskId: Int? = null
        if (enabled && decision == ParallelWindowPolicy.Decision.FlexibleReuse) {
            // 复用路径不能靠普通启动：系统会把"当前聚焦的二级界面 task"缩成小窗，而不是复用主界面。
            // 这里直接把启动锁进已有的主界面 task（按 userId 查，主应用与分身各自独立），
            // 系统就不会再新建实例，多任务里也不会越堆越多。
            reuseTaskId = findMainSurfaceTaskId(entry.target)
            if (reuseTaskId == null) {
                logger(Log.WARN, "PARALLEL_REUSE_TASK_MISSING user=${entry.target.userId}", null)
                newInstance = true
            }
        }
        if (flexible) {
            // 小窗把主界面从这个 task 里搬走/复制走之后，二级界面留下的 task 变空仍会占着
            // 多任务里的一张卡片（系统默认不回收空 task）。这里把它标记成"空了就回收"，
            // 与原生入口的表现对齐。字段名按可用性探测，取不到只记日志、不影响启动。
            logger(
                Log.INFO,
                "PARALLEL_RECYCLE user=${entry.target.userId} field=${markFocusedTaskRecyclable()}",
                null,
            )
        }
        logger(
            Log.INFO,
            "FREEFORM_LAUNCH_REQUEST user=${entry.target.userId} " +
                "mode=${if (flexible) "flexible" else "normal"} " +
                "newInstance=$newInstance reuseTaskId=${reuseTaskId ?: NO_SOURCE_TASK} " +
                "target=${entry.target.component.flattenToShortString()}",
            null,
        )
        when (
            val result =
                launcher.launch(
                    entry.target,
                    newInstance = newInstance,
                    flexible = flexible,
                    reuseTaskId = reuseTaskId,
                )
        ) {
            is FreeformLaunchResult.Started ->
                logger(
                    Log.INFO,
                    "FREEFORM_LAUNCH_STARTED user=${entry.target.userId} route=${result.route}",
                    null,
                )
            FreeformLaunchResult.TargetUnavailable ->
                logger(Log.WARN, "FREEFORM_LAUNCH_TARGET_UNAVAILABLE", null)
            is FreeformLaunchResult.Failed ->
                logger(Log.WARN, result.diagnosticCode, result.cause)
        }
    }

    /**
     * 微信停在二级界面（朋友圈、视频号、转发等）时，决定这次启动怎么开小窗。
     * 一级/聊天界面、其他应用、以及前台状态读不到时都返回 [ParallelWindowPolicy.Decision.Normal]。
     */
    private fun resolveParallelDecision(target: AppTarget): ParallelWindowPolicy.Decision =
        try {
            if (!ParallelWindowPolicy.isTarget(target.component.packageName)) {
                ParallelWindowPolicy.Decision.Normal
            } else {
                val focused = focusedTopActivity(target.component)
                val decision =
                    ParallelWindowPolicy.decide(
                        targetPackage = target.component.packageName,
                        targetUserId = target.userId,
                        targetLauncherClass = target.component.className,
                        focusedPackage = focused?.packageName,
                        focusedUserId = focused?.userId,
                        focusedClassName = focused?.className,
                        focusedTaskHasLauncher = focused?.taskContainsLauncher == true,
                    )
                logger(
                    Log.INFO,
                    "PARALLEL_GATE target=${target.storageKey} focused=${focused?.logLabel ?: "none"} " +
                        "userIdSource=${focused?.userIdSource ?: "none"} " +
                        "taskActivities=${focused?.activityCount ?: -1} " +
                        "taskHasLauncher=${focused?.taskContainsLauncher ?: false} " +
                        "decision=$decision enabled=${ParallelWindowPolicy.ENABLED}",
                    null,
                )
                decision
            }
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "PARALLEL_GATE_UNAVAILABLE", exception)
            ParallelWindowPolicy.Decision.Normal
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PARALLEL_GATE_UNAVAILABLE", exception)
            ParallelWindowPolicy.Decision.Normal
        }

    /** 显示器聚焦 root task 的栈顶 activity；分身用户的 task 同样取得到。 */
    private fun focusedTopActivity(launcherComponent: ComponentName): FocusedTop? {
        val atms = readField(controller, "mAtms") ?: return null
        val root = readField(atms, "mRootWindowContainer") ?: return null
        val task = findMethod(root.javaClass, "getTopDisplayFocusedRootTask", 0).invoke(root) ?: return null
        val activity = findMethod(task.javaClass, "topRunningActivity", 0).invoke(task) ?: return null
        val component = readField(activity, "mActivityComponent") as? ComponentName
        val (userId, userIdSource) = focusedUserId(activity, task)
        val activities = taskActivities(task)
        return FocusedTop(
            packageName = component?.packageName,
            className = component?.className,
            userId = userId,
            userIdSource = userIdSource,
            activityCount = activities.size,
            taskContainsLauncher = activities.any { record ->
                (readField(record, "mActivityComponent") as? ComponentName) == launcherComponent
            },
        )
    }

    /**
     * 聚焦 task 里的 activity 列表：各版本字段名不一致（`mActivities` / `mChildren` / …），
     * 因此先试已知名字，再扫描所有集合字段挑出真正装着 ActivityRecord 的那个。
     */
    private fun taskActivities(task: Any): List<Any> {
        listOf("mActivities", "mChildren").forEach { name ->
            val value = readField(task, name) as? List<*> ?: return@forEach
            val records = value.filterNotNull().filter { item -> readField(item, "mActivityComponent") != null }
            if (records.isNotEmpty()) return records
        }
        var current: Class<*>? = task.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                val value =
                    try {
                        field.isAccessible = true
                        field.get(task) as? List<*>
                    } catch (_: ReflectiveOperationException) {
                        null
                    } catch (_: RuntimeException) {
                        null
                    } ?: continue
                val records = value.filterNotNull().filter { item -> readField(item, "mActivityComponent") != null }
                if (records.isNotEmpty()) return records
            }
            current = current.superclass
        }
        return emptyList()
    }

    /** `ActivityRecord` 没有稳定的 userId 字段名，按可用性依次尝试并记录来源。 */
    private fun focusedUserId(activity: Any, task: Any): Pair<Int?, String> {
        val uid = (readField(activity, "info") as? ActivityInfo)?.applicationInfo?.uid
        if (uid != null) {
            return UserHandle.getUserHandleForUid(uid).identifier to USER_ID_SOURCE_ACTIVITY_INFO
        }
        (readField(activity, "mUserId") as? Int)?.let { return it to USER_ID_SOURCE_ACTIVITY_FIELD }
        (readField(task, "mUserId") as? Int)?.let { return it to USER_ID_SOURCE_TASK_FIELD }
        return null to USER_ID_SOURCE_UNKNOWN
    }

    /**
     * 把当前聚焦 task 标记成"变空即回收"，并返回真正写入的字段名（供日志核对）。
     * 取不到字段时返回 `none`，调用方不受影响。
     */
    private fun markFocusedTaskRecyclable(): String =
        try {
            val atms = readField(controller, "mAtms") ?: return "no-atms"
            val root = readField(atms, "mRootWindowContainer") ?: return "no-root"
            val task = findMethod(root.javaClass, "getTopDisplayFocusedRootTask", 0).invoke(root) ?: return "no-task"
            TASK_RECYCLABLE_FIELDS.firstNotNullOfOrNull { name ->
                val field = findField(task.javaClass, name) ?: return@firstNotNullOfOrNull null
                try {
                    field.setBoolean(task, true)
                    name
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: "none"
        } catch (_: ReflectiveOperationException) {
            "reflection-failed"
        } catch (_: RuntimeException) {
            "runtime-failed"
        }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    /** 已有的主界面 task id：按 `userId` 在窗口容器树里找，主应用与分身各找各的。
     * 找不到时返回 null，调用方退回"新开实例"以保证小窗至少能出现。
     */
    private fun findMainSurfaceTaskId(target: AppTarget): Int? =
        try {
            val task = findMainSurfaceTask(target) ?: return null
            val taskId = readField(task, "mTaskId") as? Int
            logger(
                Log.INFO,
                "PARALLEL_REUSE_TASK user=${target.userId} taskId=${taskId ?: NO_SOURCE_TASK}",
                null,
            )
            taskId
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PARALLEL_REUSE_TASK_FAILED user=${target.userId}", exception)
            null
        }

    /**
     * 在窗口容器树里找"目标用户 + 仍含主界面组件"的 WeChat task。
     * 走到 Task 层用 `mTaskId` / `mUserId` 判定，跨用户（含分身）都能找到。
     */
    private fun findMainSurfaceTask(target: AppTarget): Any? {
        val atms = readField(controller, "mAtms") ?: return null
        val root = readField(atms, "mRootWindowContainer") ?: return null
        val pending = ArrayDeque<Any>()
        pending += root
        val visited = HashSet<Int>()
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!visited.add(System.identityHashCode(node))) continue
            if (readField(node, "mTaskId") is Int && readField(node, "mUserId") == target.userId) {
                val containsLauncher =
                    taskActivities(node).any { record ->
                        (readField(record, "mActivityComponent") as? ComponentName) == target.component
                    }
                if (containsLauncher) return node
            }
            (readField(node, "mChildren") as? List<*>)?.forEach { child ->
                if (child != null) pending += child
            }
        }
        return null
    }

    private data class FocusedTop(
        val packageName: String?,
        val className: String?,
        val userId: Int?,
        val userIdSource: String,
        val activityCount: Int,
        val taskContainsLauncher: Boolean,
    ) {
        val logLabel: String
            get() = "${packageName ?: "?"}/${className ?: "?"}@u${userId ?: UNKNOWN_USER}"
    }

    override fun onMorePanelRequested() {
        if (!isGestureEnvironmentAllowed()) {
            removeOverlay()
            return
        }
        val view = overlay ?: return
        morePanelActive = true
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        if (!sidebar.open(
                beforeOpen = {
                    if (overlay === view && lastSettings.enabled && isGestureEnvironmentAllowed()) {
                        view.retainBackdropForPanel()
                        true
                    } else false
                },
                onResult = { result ->
                    if (overlay === view) {
                        if (result == ColorOsSidebarClient.Outcome.Fallback && lastSettings.enabled && isGestureEnvironmentAllowed() &&
                            context.getSystemService(android.os.UserManager::class.java)?.isUserForeground == true
                        ) {
                            view.visibility = android.view.View.VISIBLE
                            showBuiltInMorePanel(view)
                        } else if (result != ColorOsSidebarClient.Outcome.Shown) removeOverlay()
                    }
                },
                onExitStarted = { if (overlay === view) view.beginBackdropExit() },
                onHideBackdrop = { hidden ->
                    if (overlay === view) view.hideBackdropAfterFrame(hidden) else hidden()
                },
                onClosed = { if (overlay === view) removeOverlay() },
            )
        ) removeOverlay()
    }

    private fun showBuiltInMorePanel(view: CornerRadialOverlayView) {
        val params = createOverlayParams(focusable = true)
        try {
            windowManager.updateViewLayout(view, params)
            view.showBuiltInMorePanel()
            view.requestFocus()
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_FOCUS_FAILED", exception)
            removeOverlay()
        }
    }

    override fun onDismissRequested() {
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
    }

    override fun onCleanupFailed(throwable: Throwable) {
        logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
    }

    private fun createOverlayParams(focusable: Boolean): WindowManager.LayoutParams {
        // 非聚焦窗不带 ALT 时位于输入法上方；聚焦面板则需要 ALT 保持相同层级。
        val inputFlags =
            if (focusable) {
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                inputFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformCornerOverlay"
        }
    }

    private fun cancelActiveGesture() {
        activeGestureConfig = null
        gestureEngine.cancel()
        overlay?.cancelGesture()
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        if (sidebar.isPending) sidebar.cancel()
        morePanelActive = false
        val cleanupFailure = view.disposeOverlay()
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // 已被系统移除；本地所有权仍需清空。
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_REMOVE_FAILED", exception)
        }
        cleanupFailure?.let { throwable ->
            logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
        }
    }

    private fun isGestureEnvironmentAllowed(): Boolean {
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return false
        return !isCriticalSystemUiForeground()
    }

    private fun isDynamicEnvironmentAllowed(): Boolean = environmentState.isGestureAllowed()

    private fun isCriticalSystemUiForeground(): Boolean {
        focusedWindowPackage()?.let { packageName ->
            if (packageName in CRITICAL_PACKAGES) return true
        }
        return try {
            val atms = readField(controller, "mAtms") ?: return false
            val root = readField(atms, "mRootWindowContainer") ?: return false
            val task = findMethod(root.javaClass, "getTopDisplayFocusedRootTask", 0).invoke(root) ?: return false
            val activity =
                findMethod(task.javaClass, "topRunningActivity", 0).invoke(task) ?: return false
            val packageName = readField(activity, "packageName") as? String ?: return false
            packageName in CRITICAL_PACKAGES
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun focusedWindowPackage(): String? =
        try {
            val windowManagerService = readWindowManagerService()
            val root = readField(windowManagerService, "mRoot") ?: return null
            val display =
                findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root) ?: return null
            val window = readField(display, "mCurrentFocus") ?: return null
            findMethod(window.javaClass, "getOwningPackage", 0).invoke(window) as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun readWindowManagerService(): Any {
        val atms = readField(controller, "mAtms") ?: throw NoSuchFieldException("mAtms")
        return readField(atms, "mWindowManager") ?: throw NoSuchFieldException("mWindowManager")
    }

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == parameterCount
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException(name)
    }

    private data class QueuedPointerEvent(val event: MotionEvent, val generation: Long)

    private companion object {
        const val CATALOG_THREAD_NAME = "FlymeFreeform-Catalog"
        const val OVERLAY_FAILURE_LOG_INTERVAL_MS = 10_000L
        const val UNKNOWN_USER = -1
        const val NO_SOURCE_TASK = -1
        val TASK_RECYCLABLE_FIELDS = listOf("autoRemoveRecents", "mAutoRemoveRecents")
        const val USER_ID_SOURCE_ACTIVITY_INFO = "activityInfo"
        const val USER_ID_SOURCE_ACTIVITY_FIELD = "activityField"
        const val USER_ID_SOURCE_TASK_FIELD = "taskField"
        const val USER_ID_SOURCE_UNKNOWN = "unknown"
        val CRITICAL_PACKAGES =
            setOf(
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
            )
    }
}
