package io.github.mangi.flymefreeform.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class InstalledLauncherApp(
    val target: AppTarget,
    val label: String,
    val icon: Bitmap,
)

internal class LauncherAppRepository(private val context: Context) {
    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val mutableApps = MutableStateFlow<List<InstalledLauncherApp>>(emptyList())
    val apps: StateFlow<List<InstalledLauncherApp>> = mutableApps.asStateFlow()

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
            }
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        // Repository 由 Application 持有到进程结束，因此接收器也只注册一次。
        context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun refresh() {
        worker.execute {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return@execute
            val userManager = context.getSystemService(UserManager::class.java)
            val profiles =
                buildList {
                    add(Process.myUserHandle())
                    addAll(userManager?.userProfiles.orEmpty())
                    addAll(
                        try {
                            launcherApps.profiles
                        } catch (_: RuntimeException) {
                            emptyList()
                        },
                    )
                }.distinct()
            val collator = Collator.getInstance(Locale.getDefault())
            mutableApps.value =
                profiles
                    .asSequence()
                    .flatMap { user ->
                        try {
                            launcherApps
                                .getActivityList(null, user)
                                .asSequence()
                                .map { info -> user to info }
                        } catch (_: SecurityException) {
                            emptySequence()
                        } catch (_: RuntimeException) {
                            emptySequence()
                        }
                    }
                    .filterNot { (_, info) -> info.componentName.packageName == context.packageName }
                    .mapNotNull { (user, info) ->
                        try {
                            InstalledLauncherApp(
                                target = AppTarget(info.componentName, user.identifier),
                                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
                            )
                        } catch (_: RuntimeException) {
                            null
                        }
                    }
                    .distinctBy { app -> app.target.storageKey }
                    .sortedWith { first, second -> collator.compare(first.label, second.label) }
                    .toList()
        }
    }

    private fun Drawable.toBitmap(): Bitmap {
        val targetSize = (ICON_CACHE_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return result
    }

    private companion object {
        const val THREAD_NAME = "FlymeFreeform-AppCatalog"
        const val ICON_CACHE_SIZE_DP = 48f
    }
}
