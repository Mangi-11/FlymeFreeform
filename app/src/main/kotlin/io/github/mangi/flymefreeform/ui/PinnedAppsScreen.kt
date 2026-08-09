package io.github.mangi.flymefreeform.ui

import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
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
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
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
    var selectedSlot by remember { mutableIntStateOf(state.settings.pinnedComponents.size.coerceAtMost(5)) }
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
                item(key = "slots_intro") {
                    Text(
                        text = stringResource(R.string.pinned_slots_title),
                        modifier =
                            Modifier
                                .padding(start = 16.dp, bottom = 8.dp)
                                .semantics { heading() },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                item(key = "slots") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        repeat(ModulePreferences.MAX_PINNED_APPS) { index ->
                            val component = pinned.getOrNull(index)
                            val app = component?.let(appByComponent::get)
                            PinnedSlot(
                                index = index,
                                app = app,
                                missingComponent = component?.takeIf { app == null },
                                selected = selectedSlot == index,
                                enabled = state.canChangeSettings,
                                pinnedCount = pinned.size,
                                onSelect = { selectedSlot = index },
                                onRemove = {
                                    onPinnedComponentsChange(pinned.filterIndexed { itemIndex, _ -> itemIndex != index })
                                    selectedSlot = index.coerceAtMost((pinned.size - 2).coerceAtLeast(0))
                                },
                                onMove = { target ->
                                    val reordered = pinned.toMutableList()
                                    val moved = reordered.removeAt(index)
                                    reordered.add(target, moved)
                                    selectedSlot = target
                                    onPinnedComponentsChange(reordered)
                                },
                            )
                        }
                    }
                }
                item(key = "apps_intro") {
                    Text(
                        text = stringResource(R.string.available_apps_title),
                        modifier =
                            Modifier
                                .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
                                .semantics { heading() },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                if (apps.isEmpty()) {
                    item(key = "apps_empty") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            BasicComponent(
                                title = stringResource(R.string.available_apps_loading),
                                summary = stringResource(R.string.available_apps_loading_summary),
                            )
                        }
                    }
                } else {
                    items(apps, key = { it.component.flattenToString() }) { app ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                        ) {
                            BasicComponent(
                                title = app.label,
                                summary = app.component.packageName,
                                startAction = { AppIcon(app) },
                                onClick = {
                                    val updated = pinned.toMutableList()
                                    val existing = updated.indexOf(app.component)
                                    if (existing != selectedSlot) {
                                        val target = selectedSlot.coerceAtMost(updated.size)
                                        when {
                                            existing >= 0 && target < updated.size -> {
                                                val replaced = updated[target]
                                                updated[target] = app.component
                                                updated[existing] = replaced
                                            }
                                            existing >= 0 -> {
                                                updated.removeAt(existing)
                                                updated.add(app.component)
                                            }
                                            target < updated.size -> updated[target] = app.component
                                            else -> updated.add(app.component)
                                        }
                                        onPinnedComponentsChange(updated)
                                        selectedSlot = (target + 1).coerceAtMost(ModulePreferences.MAX_PINNED_APPS - 1)
                                    }
                                },
                                onClickLabel = stringResource(R.string.assign_app_action, selectedSlot + 1),
                                role = Role.Button,
                                enabled = state.canChangeSettings,
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
private fun PinnedSlot(
    index: Int,
    app: InstalledLauncherApp?,
    missingComponent: ComponentName?,
    selected: Boolean,
    enabled: Boolean,
    pinnedCount: Int,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var dragY by remember(index) { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { 68.dp.toPx() }
    val moveModifier =
        if (app != null && enabled && pinnedCount > 1) {
            Modifier.pointerInput(index, pinnedCount) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragY = 0f },
                    onDragCancel = { dragY = 0f },
                    onDragEnd = {
                        val target = (index + (dragY / rowHeightPx).roundToInt()).coerceIn(0, pinnedCount - 1)
                        dragY = 0f
                        if (target != index) onMove(target)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragY += amount.y
                    },
                )
            }
        } else {
            Modifier
        }
    val title = app?.label ?: missingComponent?.packageName ?: stringResource(R.string.pinned_slot_empty)
    val summary =
        when {
            missingComponent != null -> stringResource(R.string.pinned_slot_missing, index + 1)
            app != null -> stringResource(R.string.pinned_slot_assigned, index + 1)
            else -> stringResource(R.string.pinned_slot_tap, index + 1)
        }
    BasicComponent(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = dragY }
                .zIndex(if (dragY == 0f) 0f else 1f)
                .then(moveModifier)
                .semantics { this.selected = selected },
        title = title,
        summary = summary,
        startAction = {
            if (app != null) AppIcon(app)
            else Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (index + 1).toString(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body1,
                )
            }
        },
        endActions = {
            if (app != null || missingComponent != null) {
                TextButton(
                    text = stringResource(R.string.remove_action),
                    onClick = onRemove,
                    enabled = enabled,
                    minWidth = 48.dp,
                    minHeight = 36.dp,
                )
                Icon(
                    imageVector = MiuixIcons.Basic.ArrowUpDown,
                    contentDescription = stringResource(R.string.drag_reorder_action),
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
        onClick = onSelect,
        onClickLabel = stringResource(R.string.select_slot_action, index + 1),
        role = Role.Button,
        holdDownState = selected,
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
