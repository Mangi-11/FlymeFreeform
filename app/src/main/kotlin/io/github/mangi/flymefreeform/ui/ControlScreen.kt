package io.github.mangi.flymefreeform.ui

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.flymefreeform.R
import io.github.mangi.flymefreeform.framework.FrameworkConnectionIssue
import io.github.mangi.flymefreeform.framework.FrameworkConnectionState
import io.github.mangi.flymefreeform.framework.FrameworkConnectionStatus
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ControlScreen(
    state: FrameworkConnectionState,
    onModuleEnabledChange: (Boolean) -> Unit,
    onLeftCornerEnabledChange: (Boolean) -> Unit,
    onRightCornerEnabledChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onManageApps: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val isWideScreen = windowWidth >= WideWindowMinWidth
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                if (isWideScreen) {
                    SmallTopAppBar(
                        title = stringResource(R.string.app_name),
                        subtitle = stringResource(R.string.screen_subtitle),
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    TopAppBar(
                        title = stringResource(R.string.app_name),
                        subtitle = stringResource(R.string.screen_subtitle),
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val safeStart = innerPadding.calculateStartPadding(layoutDirection)
            val safeEnd = innerPadding.calculateEndPadding(layoutDirection)
            val safeWidth = (windowWidth - safeStart - safeEnd).coerceAtLeast(0.dp)
            val centeredSide = maxOf(ScreenHorizontalMargin, (safeWidth - ScreenContentMaxWidth) / 2)
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(innerPadding)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding =
                    PaddingValues(
                        start = safeStart + centeredSide,
                        top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                        end = safeEnd + centeredSide,
                        bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                    ),
            ) {
                item(key = "runtime") { RuntimeSection(state, onRequestScopes) }
                item(key = "settings") {
                    SettingsSection(
                        state,
                        onModuleEnabledChange,
                        onLeftCornerEnabledChange,
                        onRightCornerEnabledChange,
                        onManageApps,
                    )
                }
                item(key = "about") { AboutSection() }
            }
        }
    }
}

@Composable
private fun RuntimeSection(state: FrameworkConnectionState, onRequestScopes: () -> Unit) {
    val presentation = frameworkPresentation(state)
    Section(title = stringResource(R.string.section_runtime)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = stringResource(R.string.framework_service_title),
                summary = presentation.summary,
                endActions = {
                    Text(
                        text = presentation.label,
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.body2,
                    )
                },
            )
            if (state.status == FrameworkConnectionStatus.Connected && state.missingScopes.isNotEmpty()) {
                ArrowPreference(
                    title = stringResource(R.string.scope_title),
                    summary =
                        if (state.isRequestingScope) stringResource(R.string.scope_requesting)
                        else if (state.issue == FrameworkConnectionIssue.ScopeRequestFailed) {
                            stringResource(R.string.scope_request_failed)
                        } else {
                            stringResource(R.string.scope_missing, state.missingScopes.joinToString(" · "))
                        },
                    onClick = onRequestScopes,
                    enabled = state.canRequestScope,
                )
            } else {
                BasicComponent(
                    title = stringResource(R.string.scope_title),
                    summary =
                        if (state.status == FrameworkConnectionStatus.Connected) {
                            stringResource(R.string.scope_complete)
                        } else {
                            stringResource(R.string.scope_waiting)
                        },
                )
            }
            BasicComponent(
                title = stringResource(R.string.implementation_state_title),
                summary = stringResource(R.string.implementation_state_summary),
                endActions = {
                    Text(
                        text = stringResource(R.string.implementation_state_value),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                },
            )
        }
    }
}

@Composable
private fun SettingsSection(
    state: FrameworkConnectionState,
    onModuleEnabledChange: (Boolean) -> Unit,
    onLeftCornerEnabledChange: (Boolean) -> Unit,
    onRightCornerEnabledChange: (Boolean) -> Unit,
    onManageApps: () -> Unit,
) {
    val moduleSummary =
        when {
            state.isUpdating -> stringResource(R.string.module_enabled_summary_updating)
            !state.canChangeSettings && state.status != FrameworkConnectionStatus.Connected ->
                stringResource(R.string.module_enabled_summary_waiting)
            state.missingScopes.isNotEmpty() -> stringResource(R.string.module_enabled_summary_scope)
            state.settings.enabled -> stringResource(R.string.module_enabled_summary_on)
            else -> stringResource(R.string.module_enabled_summary_off)
        }
    val appsSummary =
        when {
            !state.settings.pinsSaved -> stringResource(R.string.radial_apps_recent_summary)
            state.settings.pinnedComponents.isEmpty() -> stringResource(R.string.radial_apps_empty_summary)
            else -> stringResource(R.string.radial_apps_count_summary, state.settings.pinnedComponents.size)
        }
    Section(title = stringResource(R.string.section_settings)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = state.settings.enabled,
                onCheckedChange = onModuleEnabledChange,
                title = stringResource(R.string.module_enabled_title),
                summary = moduleSummary,
                enabled = state.canChangeSettings,
            )
            SwitchPreference(
                checked = state.settings.leftCornerEnabled,
                onCheckedChange = onLeftCornerEnabledChange,
                title = stringResource(R.string.left_corner_title),
                summary = stringResource(R.string.left_corner_summary),
                enabled = state.canChangeSettings,
            )
            SwitchPreference(
                checked = state.settings.rightCornerEnabled,
                onCheckedChange = onRightCornerEnabledChange,
                title = stringResource(R.string.right_corner_title),
                summary = stringResource(R.string.right_corner_summary),
                enabled = state.canChangeSettings,
            )
            ArrowPreference(
                title = stringResource(R.string.radial_apps_title),
                summary = appsSummary,
                onClick = onManageApps,
                enabled = state.canChangeSettings,
            )
        }
    }
}

@Composable
private fun AboutSection() {
    Section(title = stringResource(R.string.section_about), last = true) {
        Card(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = stringResource(R.string.implementation_principle_title),
                summary = stringResource(R.string.implementation_principle_summary),
            )
            BasicComponent(
                title = stringResource(R.string.independent_project_title),
                summary = stringResource(R.string.independent_project_summary),
            )
        }
    }
}

@Composable
private fun Section(title: String, last: Boolean = false, content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = if (last) 0.dp else SectionSpacing),
    ) {
        Text(
            text = title,
            modifier =
                Modifier
                    .padding(start = SectionTitleStart, bottom = SectionTitleBottom)
                    .semantics { heading() },
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.subtitle,
        )
        content()
    }
}

@Composable
private fun frameworkPresentation(state: FrameworkConnectionState): FrameworkPresentation =
    when (state.status) {
        FrameworkConnectionStatus.Waiting -> FrameworkPresentation(stringResource(R.string.framework_status_waiting), stringResource(R.string.framework_waiting_summary))
        FrameworkConnectionStatus.Connected -> {
            val unknown = stringResource(R.string.framework_unknown_value)
            FrameworkPresentation(
                stringResource(R.string.framework_status_connected),
                stringResource(
                    R.string.framework_connected_summary,
                    state.frameworkName ?: unknown,
                    state.frameworkVersion ?: unknown,
                    state.apiVersion?.toString() ?: unknown,
                ),
            )
        }
        FrameworkConnectionStatus.Incompatible -> FrameworkPresentation(stringResource(R.string.framework_status_incompatible), stringResource(state.issue.incompatibleSummary()))
        FrameworkConnectionStatus.Error -> FrameworkPresentation(stringResource(R.string.framework_status_error), stringResource(state.issue.errorSummary()))
    }

@StringRes
private fun FrameworkConnectionIssue?.incompatibleSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.ServiceApiTooOld -> R.string.framework_api_too_old_summary
        FrameworkConnectionIssue.RemoteCapabilityMissing -> R.string.framework_remote_missing_summary
        FrameworkConnectionIssue.SystemCapabilityMissing -> R.string.framework_system_missing_summary
        FrameworkConnectionIssue.MultipleServices -> R.string.framework_multiple_services_summary
        else -> R.string.framework_connection_failed_summary
    }

@StringRes
private fun FrameworkConnectionIssue?.errorSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.WriteFailed -> R.string.framework_write_failed_summary
        else -> R.string.framework_connection_failed_summary
    }

private data class FrameworkPresentation(val label: String, val summary: String)

internal val ScreenHorizontalMargin = 12.dp
internal val ScreenContentMaxWidth = 600.dp
internal val WideWindowMinWidth = 600.dp
internal val ScreenTopSpacing = 8.dp
internal val ScreenBottomSpacing = 20.dp
private val SectionSpacing = 18.dp
private val SectionTitleStart = 16.dp
private val SectionTitleBottom = 8.dp
