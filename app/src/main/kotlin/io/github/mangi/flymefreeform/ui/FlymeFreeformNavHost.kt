package io.github.mangi.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.framework.FrameworkConnectionState
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object PinnedApps : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    sidebarCatalog: io.github.mangi.flymefreeform.apps.SidebarCatalogState,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
) {
    val backStack = rememberNavBackStack(AppRoute.Settings)
    val popBackStack = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            Unit
        }
    }
    val navigateToPinnedApps = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PinnedApps) {
                backStack.add(AppRoute.PinnedApps)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
        entryProvider =
            entryProvider {
                entry<AppRoute.Settings> {
                    ControlScreen(
                        state = state,
                        onModuleEnabledChange = onModuleEnabledChange,
                        onCornerSidesChange = onCornerSidesChange,
                        onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                        onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                        onHandleSwipeUpToMiniEnabledChange =
                            onHandleSwipeUpToMiniEnabledChange,
                        onPauseInLandscapeChange = onPauseInLandscapeChange,
                        onPauseInGameModeChange = onPauseInGameModeChange,
                        onRequestScopes = onRequestScopes,
                        onNavigateToPinnedApps = navigateToPinnedApps,
                    )
                }
                entry<AppRoute.PinnedApps> {
                    PinnedAppsScreen(
                        state = state,
                        apps = apps,
                        sidebarCatalog = sidebarCatalog,
                        onRefreshCatalog = onRefreshCatalog,
                        onCancelCatalog = onCancelCatalog,
                        onBack = popBackStack,
                        onPinnedTargetsChange = onPinnedTargetsChange,
                    )
                }
            },
    )
}
