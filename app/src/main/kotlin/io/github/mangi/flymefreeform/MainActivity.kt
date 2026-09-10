package io.github.mangi.flymefreeform

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.flymefreeform.ui.FlymeFreeformNavHost
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
            FlymeFreeformTheme {
                FlymeFreeformNavHost(
                    state = state,
                    apps = apps,
                    onModuleEnabledChange = repository::setModuleEnabled,
                    onLeftCornerEnabledChange = repository::setLeftCornerEnabled,
                    onRightCornerEnabledChange = repository::setRightCornerEnabled,
                    onCornerTriggerRangeChange = repository::setCornerTriggerRangeDp,
                    onOutsideTapCloseModeChange = repository::setOutsideTapCloseMode,
                    onHandleSwipeUpToMiniEnabledChange =
                        repository::setHandleSwipeUpToMiniEnabled,
                    onPauseInLandscapeChange = repository::setPauseInLandscape,
                    onPauseInGameModeChange = repository::setPauseInGameMode,
                    onRequestScopes = repository::requestMissingScopes,
                    onPinnedComponentsChange = repository::setPinnedComponents,
                )
            }
        }
    }
}
