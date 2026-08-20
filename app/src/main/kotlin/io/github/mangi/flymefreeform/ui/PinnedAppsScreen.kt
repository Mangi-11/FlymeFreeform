package io.github.mangi.flymefreeform.ui

import android.content.ComponentName
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mangi.flymefreeform.R
import io.github.mangi.flymefreeform.apps.InstalledLauncherApp
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.framework.FrameworkConnectionState
import io.github.mangi.flymefreeform.ui.component.TopBarBackdrop
import io.github.mangi.flymefreeform.ui.component.captureForTopBar
import io.github.mangi.flymefreeform.ui.component.rememberTopBarBackdrop
import io.github.mangi.flymefreeform.ui.component.topBarContainerColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PinnedAppsScreen(
    state: FrameworkConnectionState,
    apps: List<InstalledLauncherApp>,
    onBack: () -> Unit,
    onPinnedComponentsChange: (List<ComponentName>) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    // 拖动或增删后的本地顺序；远端配置回读同步前以它为准，避免列表闪回旧顺序
    var localOrder by remember { mutableStateOf<List<ComponentName>?>(null) }
    var draggingComponent by remember { mutableStateOf<ComponentName?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= WideWindowMinWidth
        val title = stringResource(R.string.radial_apps_screen_title)
        val navigationIcon: @Composable () -> Unit = { BackButton(onBack) }
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (isWide) {
                        SmallTopAppBar(
                            title = title,
                            subtitle = stringResource(R.string.radial_apps_screen_subtitle),
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = title,
                            subtitle = stringResource(R.string.radial_apps_screen_subtitle),
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { innerPadding ->
            val direction = LocalLayoutDirection.current
            val safeStart = innerPadding.calculateStartPadding(direction)
            val safeEnd = innerPadding.calculateEndPadding(direction)
            val safeWidth = (maxWidth - safeStart - safeEnd).coerceAtLeast(0.dp)
            val side = maxOf(ScreenHorizontalMargin, (safeWidth - ScreenContentMaxWidth) / 2)
            val pinned = state.settings.pinnedComponents
            LaunchedEffect(pinned) {
                if (localOrder != null && pinned == localOrder) localOrder = null
            }
            val displayed = localOrder ?: pinned
            val appByComponent = remember(apps) { apps.associateBy(InstalledLauncherApp::component) }
            Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(innerPadding)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding =
                    PaddingValues(
                        start = safeStart + side,
                        top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                        end = safeEnd + side,
                        bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                    ),
                ) {
                item(key = "added_intro") {
                    Text(
                        text = stringResource(R.string.added_apps_title),
                        modifier =
                            Modifier
                                .padding(start = 16.dp, bottom = 8.dp)
                                .semantics { heading() },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                item(key = "added") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        displayed.forEachIndexed { index, component ->
                            key(component.flattenToString()) {
                                AddedAppRow(
                                    component = component,
                                    app = appByComponent[component],
                                    index = index,
                                    enabled = state.canChangeSettings,
                                    draggable = state.canChangeSettings && displayed.size > 1,
                                    dragging = draggingComponent == component,
                                    dragOffsetY = dragOffsetY,
                                    rowHeightPx = rowHeightPx,
                                    onRowHeight = { height ->
                                        if (rowHeightPx != height) rowHeightPx = height
                                    },
                                    onRemove = {
                                        val updated = displayed.filterNot { it == component }
                                        localOrder = updated
                                        onPinnedComponentsChange(updated)
                                    },
                                    onDragStart = {
                                        localOrder = displayed
                                        draggingComponent = component
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { deltaY ->
                                        if (rowHeightPx > 0f) {
                                            dragOffsetY += deltaY
                                            val order =
                                                (localOrder ?: displayed).toMutableList()
                                            var current = order.indexOf(component)
                                            while (current >= 0 &&
                                                dragOffsetY > rowHeightPx / 2 &&
                                                current < order.size - 1
                                            ) {
                                                order.add(current + 1, order.removeAt(current))
                                                dragOffsetY -= rowHeightPx
                                                current++
                                            }
                                            while (current >= 0 &&
                                                dragOffsetY < -rowHeightPx / 2 &&
                                                current > 0
                                            ) {
                                                order.add(current - 1, order.removeAt(current))
                                                dragOffsetY += rowHeightPx
                                                current--
                                            }
                                            localOrder = order
                                        }
                                    },
                                    onDragEnd = {
                                        val order = localOrder
                                        draggingComponent = null
                                        dragOffsetY = 0f
                                        if (order != null) {
                                            if (order == pinned) {
                                                localOrder = null
                                            } else {
                                                onPinnedComponentsChange(order)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        if (displayed.isEmpty()) {
                            BasicComponent(
                                title = stringResource(R.string.added_apps_empty),
                                summary = stringResource(R.string.added_apps_empty_summary),
                                enabled = false,
                            )
                        }
                        BasicComponent(
                            title = stringResource(R.string.select_apps_action),
                            startAction = {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .squircleBackground(
                                                MiuixTheme.colorScheme.secondaryContainer,
                                                12.dp,
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            },
                            onClick = {
                                pickerQuery = ""
                                pickerOpen = true
                            },
                            onClickLabel = stringResource(R.string.select_apps_action),
                            role = Role.Button,
                            enabled = state.canChangeSettings,
                        )
                    }
                }
                }
            }
            // 勾选状态跟随 displayed，sheet 内增删即时可见
            val addedSet = remember(displayed) { displayed.toSet() }
            val candidates =
                remember(apps, pickerQuery) {
                    if (pickerQuery.isBlank()) {
                        apps
                    } else {
                        apps.filter {
                            it.label.contains(pickerQuery, ignoreCase = true) ||
                                it.component.packageName.contains(pickerQuery, ignoreCase = true)
                        }
                    }
                }
            OverlayBottomSheet(
                show = pickerOpen,
                title = stringResource(R.string.select_apps_action),
                onDismissRequest = { pickerOpen = false },
            ) {
                Column {
                    TextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.search_apps_placeholder),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = MiuixIcons.Basic.Search,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                            )
                        },
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        when {
                            apps.isEmpty() ->
                                item(key = "apps_loading") {
                                    BasicComponent(
                                        title = stringResource(R.string.available_apps_loading),
                                        summary = stringResource(R.string.available_apps_loading_summary),
                                        enabled = false,
                                    )
                                }
                            candidates.isEmpty() ->
                                item(key = "apps_no_match") {
                                    BasicComponent(
                                        title = stringResource(R.string.no_matching_apps),
                                        enabled = false,
                                    )
                                }
                            else ->
                                items(candidates, key = { it.component.flattenToString() }) { app ->
                                    val added = app.component in addedSet
                                    BasicComponent(
                                        title = app.label,
                                        summary = app.component.packageName,
                                        startAction = { AppIcon(app) },
                                        endActions =
                                            if (added) {
                                                {
                                                    Icon(
                                                        imageVector = MiuixIcons.Basic.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MiuixTheme.colorScheme.primary,
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        onClick = {
                                            val updated =
                                                if (added) {
                                                    displayed.filterNot { it == app.component }
                                                } else {
                                                    (displayed + app.component)
                                                        .take(ModulePreferences.MAX_PINNED_APPS)
                                                }
                                            localOrder = updated
                                            onPinnedComponentsChange(updated)
                                        },
                                        onClickLabel =
                                            stringResource(
                                                if (added) {
                                                    R.string.remove_named_app_action
                                                } else {
                                                    R.string.add_named_app_action
                                                },
                                                app.label,
                                            ),
                                        role = Role.Button,
                                        enabled =
                                            state.canChangeSettings &&
                                                (added || displayed.size < ModulePreferences.MAX_PINNED_APPS),
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddedAppRow(
    component: ComponentName,
    app: InstalledLauncherApp?,
    index: Int,
    enabled: Boolean,
    draggable: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    rowHeightPx: Float,
    onRowHeight: (Float) -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // 被拖行换位时，其余行先从旧视觉位置吸附再弹簧归位，形成实时让位动画
    val offsetAnim = remember { Animatable(0f) }
    var restIndex by remember { mutableIntStateOf(index) }
    LaunchedEffect(index, dragging) {
        if (dragging || index == restIndex) {
            restIndex = index
        } else {
            offsetAnim.snapTo(offsetAnim.value + (restIndex - index) * rowHeightPx)
            restIndex = index
            offsetAnim.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
        }
    }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val dragModifier =
        if (draggable) {
            Modifier.pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDragCancel = { currentOnDragEnd() },
                    onDragEnd = { currentOnDragEnd() },
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDrag(amount.y)
                    },
                )
            }
        } else {
            Modifier
        }
    BasicComponent(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> onRowHeight(size.height.toFloat()) }
                .graphicsLayer { translationY = if (dragging) dragOffsetY else offsetAnim.value }
                .zIndex(if (dragging) 1f else 0f)
                .then(dragModifier),
        title = app?.label ?: component.packageName,
        summary = if (app == null) stringResource(R.string.pinned_app_missing) else null,
        startAction = {
            if (app != null) {
                AppIcon(app)
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "?",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body1,
                    )
                }
            }
        },
        endActions = {
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                backgroundColor = MiuixTheme.colorScheme.error,
                cornerRadius = 13.dp,
                minWidth = 26.dp,
                minHeight = 26.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription =
                        stringResource(
                            R.string.remove_named_app_action,
                            app?.label ?: component.packageName,
                        ),
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onError,
                )
            }
        },
        enabled = enabled,
    )
}

@Composable
private fun AppIcon(app: InstalledLauncherApp) {
    Image(
        bitmap = app.icon.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(44.dp).squircleClip(12.dp),
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back_action),
            modifier = Modifier.size(24.dp),
        )
    }
}
