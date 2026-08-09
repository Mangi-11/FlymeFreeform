package io.github.mangi.flymefreeform.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.mangi.flymefreeform.apps.InstalledLauncherApp
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
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
    apps: List<InstalledLauncherApp>,
    onModuleEnabledChange: (Boolean) -> Unit,
    onLeftCornerEnabledChange: (Boolean) -> Unit,
    onRightCornerEnabledChange: (Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialCircularIconsEnabledChange: (Boolean) -> Unit,
    onRadialIconContentScaleChange: (Int) -> Unit,
    onRadialIconMaskScaleChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedComponentsChange: (List<ComponentName>) -> Unit,
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
                        onLeftCornerEnabledChange = onLeftCornerEnabledChange,
                        onRightCornerEnabledChange = onRightCornerEnabledChange,
                        onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                        onRadialCircularIconsEnabledChange =
                            onRadialCircularIconsEnabledChange,
                        onRadialIconContentScaleChange = onRadialIconContentScaleChange,
                        onRadialIconMaskScaleChange = onRadialIconMaskScaleChange,
                        onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                        onHandleSwipeUpToMiniEnabledChange =
                            onHandleSwipeUpToMiniEnabledChange,
                        onRequestScopes = onRequestScopes,
                        onNavigateToPinnedApps = navigateToPinnedApps,
                    )
                }
                entry<AppRoute.PinnedApps> {
                    PinnedAppsScreen(
                        state = state,
                        apps = apps,
                        onBack = popBackStack,
                        onPinnedComponentsChange = onPinnedComponentsChange,
                    )
                }
            },
    )
}
