package io.github.mangi.flymefreeform

import android.app.Application
import io.github.mangi.flymefreeform.apps.LauncherAppRepository
import io.github.mangi.flymefreeform.framework.FrameworkConnectionRepository

class FlymeFreeformApplication : Application() {
    internal lateinit var frameworkConnectionRepository: FrameworkConnectionRepository
        private set
    internal lateinit var launcherAppRepository: LauncherAppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        frameworkConnectionRepository = FrameworkConnectionRepository().also { it.start() }
        launcherAppRepository = LauncherAppRepository(this).also { it.refresh() }
    }
}
