package io.github.mangi.flymefreeform

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.flymefreeform.ui.ControlScreen
import io.github.mangi.flymefreeform.ui.PinnedAppsScreen
import io.github.mangi.flymefreeform.ui.theme.FlymeFreeformTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        val repository =
            (application as FlymeFreeformApplication).frameworkConnectionRepository
        val appRepository =
            (application as FlymeFreeformApplication).launcherAppRepository
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val apps = appRepository.apps.collectAsStateWithLifecycle().value
            var screen by rememberSaveable { mutableStateOf(Screen.Control) }
            BackHandler(enabled = screen == Screen.PinnedApps) { screen = Screen.Control }
            FlymeFreeformTheme {
                when (screen) {
                    Screen.Control ->
                        ControlScreen(
                            state = state,
                            onModuleEnabledChange = repository::setModuleEnabled,
                            onLeftCornerEnabledChange = repository::setLeftCornerEnabled,
                            onRightCornerEnabledChange = repository::setRightCornerEnabled,
                            onCornerTriggerRangeChange = repository::setCornerTriggerRangeDp,
                            onRadialCircularIconsEnabledChange =
                                repository::setRadialCircularIconsEnabled,
                            onRadialIconContentScaleChange =
                                repository::setRadialIconContentScalePercent,
                            onRadialIconMaskScaleChange =
                                repository::setRadialIconMaskScalePercent,
                            onOutsideTapCloseModeChange = repository::setOutsideTapCloseMode,
                            onHandleSwipeUpToMiniEnabledChange =
                                repository::setHandleSwipeUpToMiniEnabled,
                            onRequestScopes = repository::requestMissingScopes,
                            onManageApps = { screen = Screen.PinnedApps },
                        )
                    Screen.PinnedApps ->
                        PinnedAppsScreen(
                            state = state,
                            apps = apps,
                            onBack = { screen = Screen.Control },
                            onPinnedComponentsChange = repository::setPinnedComponents,
                        )
                }
            }
        }
    }

    private enum class Screen {
        Control,
        PinnedApps,
    }
}
