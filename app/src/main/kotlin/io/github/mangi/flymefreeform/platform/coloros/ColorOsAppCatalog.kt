package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import io.github.mangi.flymefreeform.apps.AppSelectionPolicy
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal data class RadialAppEntry(
    val component: ComponentName,
    val label: String,
    val icon: Bitmap,
)

internal data class AppCatalogSnapshot(
    val radialApps: List<RadialAppEntry> = emptyList(),
    val panelApps: List<RadialAppEntry> = emptyList(),
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
) {
    fun matches(settings: ModuleSettingsSnapshot): Boolean =
        this.settings.pinsSaved == settings.pinsSaved &&
            this.settings.pinnedComponents == settings.pinnedComponents
}

/** 目录查询只在后台执行；发布后的 Bitmap 与列表供手势热路径只读。 */
internal class ColorOsAppCatalog(
    private val context: Context,
    private val executor: Executor,
    logger: (Int, String, Throwable?) -> Unit,
    private val publish: (AppCatalogSnapshot) -> Unit,
) {
    private val iconRenderer = ColorOsRadialIconRenderer(context.resources, logger)

    private val contentRevision = AtomicLong()
    private var cachedContent: CatalogContent? = null // 仅目录工作线程访问。

    fun refresh(settings: ModuleSettingsSnapshot, reloadApps: Boolean = true) {
        if (reloadApps) contentRevision.incrementAndGet()
        executor.execute {
            val revision = contentRevision.get()
            val cached = cachedContent
            val content =
                if (cached != null && cached.revision == revision &&
                    cached.settings.pinsSaved == settings.pinsSaved &&
                    cached.settings.pinnedComponents == settings.pinnedComponents
                ) {
                    cached
                } else {
                    loadContent(settings, revision) ?: return@execute
                }
            cachedContent = content
            val shapedRadial = content.radialApps.map { entry ->
                val drawable = content.radialSources[entry.component]
                if (drawable == null) entry else {
                    try {
                        entry.copy(
                            icon = iconRenderer
                                .shapedIcon(drawable)
                                .toBitmap(),
                        )
                    } catch (_: RuntimeException) {
                        entry
                    }
                }
            }
            shapedRadial.forEach { it.icon.prepareToDraw() }
            publish(AppCatalogSnapshot(shapedRadial, content.panelApps, settings))
        }
    }

    private fun loadContent(settings: ModuleSettingsSnapshot, revision: Long): CatalogContent? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val user = Process.myUserHandle()
        val activities = launcherApps.getActivityList(null, user)
        val entries =
            activities
                .asSequence()
                .filterNot { info -> info.componentName.packageName == MODULE_PACKAGE }
                .mapNotNull(::toEntry)
                .distinctBy(RadialAppEntry::component)
                .toList()
        val byComponent = entries.associateBy(RadialAppEntry::component)
        val recents = recentComponents().mapNotNull(byComponent::get).distinctBy(RadialAppEntry::component)
        val collator = Collator.getInstance(Locale.getDefault())
        val alphabetical =
            entries.sortedWith { first, second ->
                collator.compare(first.label, second.label)
            }
        val radial =
            AppSelectionPolicy.radialItems(
                pinsSaved = settings.pinsSaved,
                availablePins = settings.pinnedComponents.mapNotNull(byComponent::get),
                recent = recents,
                all = alphabetical,
                identity = RadialAppEntry::component,
                limit = io.github.mangi.flymefreeform.config.ModulePreferences.MAX_PINNED_APPS,
            )
        val excluded = radial.mapTo(HashSet(), RadialAppEntry::component)
        val panel =
            AppSelectionPolicy.panelItems(
                recent = recents,
                all = alphabetical,
                excluded = excluded,
                identity = RadialAppEntry::component,
            )
        val activityByComponent = activities.associateBy(LauncherActivityInfo::getComponentName)
        val sources = radial.mapNotNull { entry ->
            val info = activityByComponent[entry.component] ?: return@mapNotNull null
            try {
                entry.component to info.getIcon(context.resources.displayMetrics.densityDpi)
            } catch (_: RuntimeException) {
                null
            }
        }.toMap()
        panel.forEach { it.icon.prepareToDraw() }
        return CatalogContent(revision, settings, radial, panel, sources)
    }

    private data class CatalogContent(
        val revision: Long,
        val settings: ModuleSettingsSnapshot,
        val radialApps: List<RadialAppEntry>,
        val panelApps: List<RadialAppEntry>,
        val radialSources: Map<ComponentName, Drawable>,
    )

    private fun recentComponents(): List<ComponentName> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            activityManager.getRecentTasks(RECENT_LIMIT, ActivityManager.RECENT_WITH_EXCLUDED)
                .mapNotNull { task -> task.origActivity ?: task.baseIntent?.component }
                .filterNot { component -> component.packageName == context.packageName }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun toEntry(info: LauncherActivityInfo): RadialAppEntry? =
        try {
            RadialAppEntry(
                component = info.componentName,
                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
            )
        } catch (_: RuntimeException) {
            null
        }

    private fun Drawable.toBitmap(): Bitmap {
        val systemIconSize =
            context.getSystemService(ActivityManager::class.java)?.launcherLargeIconSize ?: 0
        val targetSize =
            (systemIconSize.takeIf { it > 0 }
                ?: maxOf(intrinsicWidth, intrinsicHeight, 1)).coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        return Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }

    private companion object {
        const val RECENT_LIMIT = 48
        const val MODULE_PACKAGE = "io.github.mangi.flymefreeform"
    }
}
