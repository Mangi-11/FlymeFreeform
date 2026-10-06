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
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.PinnedTarget
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal data class RadialEntry(
    val target: PinnedTarget,
    val label: String,
    val icon: Bitmap,
    val badge: Bitmap? = null,
    val badgeLeftOffsetFraction: Float = 0f,
)

internal data class AppCatalogSnapshot(
    val radialApps: List<RadialEntry> = emptyList(),
    val panelApps: List<RadialEntry> = emptyList(),
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
) {
    fun matches(settings: ModuleSettingsSnapshot): Boolean =
        this.settings.pinsSaved == settings.pinsSaved &&
            this.settings.pinnedTargets == settings.pinnedTargets
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

    @Volatile private var sidebarEntries: List<SelectableEntry> = emptyList()

    fun updateSidebarEntries(entries: List<SelectableEntry>, settings: ModuleSettingsSnapshot) {
        sidebarEntries = entries.toList()
        refresh(settings)
    }

    fun refresh(settings: ModuleSettingsSnapshot, reloadApps: Boolean = true) {
        if (reloadApps) contentRevision.incrementAndGet()
        executor.execute {
            val revision = contentRevision.get()
            val cached = cachedContent
            val content =
                if (cached != null && cached.revision == revision &&
                    cached.settings.pinsSaved == settings.pinsSaved &&
                    cached.settings.pinnedTargets == settings.pinnedTargets
                ) {
                    cached
                } else {
                    loadContent(settings, revision) ?: return@execute
                }
            cachedContent = content
            val shapedRadial = content.radialApps.map { entry ->
                val drawable = content.radialSources[entry.target]
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
            shapedRadial.forEach { entry ->
                entry.icon.prepareToDraw()
                entry.badge?.prepareToDraw()
            }
            if (revision == contentRevision.get()) publish(AppCatalogSnapshot(shapedRadial, content.panelApps, settings))
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
                .distinctBy(RadialEntry::target)
                .toList()
        val sidebar = sidebarEntries
        val nativeEntries = sidebar.map {
            RadialEntry(it.target, it.label, it.icon, it.badge, it.badge?.leftOffsetFraction() ?: 0f)
        }
        val byComponent = (entries + nativeEntries).associateBy(RadialEntry::target)
        val recents = recentComponents().map { it.toTarget() }.mapNotNull(byComponent::get).distinctBy(RadialEntry::target)
        val collator = Collator.getInstance(Locale.getDefault())
        val alphabetical =
            entries.sortedWith { first, second ->
                collator.compare(first.label, second.label)
            }
        val radial =
            AppSelectionPolicy.radialItems(
                pinsSaved = settings.pinsSaved,
                availablePins = settings.pinnedTargets.mapNotNull(byComponent::get),
                recent = recents,
                all = alphabetical,
                identity = RadialEntry::target,
                limit = io.github.mangi.flymefreeform.config.ModulePreferences.MAX_PINNED_APPS,
            )
        val excluded = radial.mapTo(HashSet(), RadialEntry::target)
        val panel =
            AppSelectionPolicy.panelItems(
                recent = recents,
                all = alphabetical,
                excluded = excluded,
                identity = RadialEntry::target,
            )
        val activityByComponent = activities.associateBy { it.componentName.toTarget() }
        // 分身与主应用共用图标资源；包内只有一个入口时复用主用户的自适应图标，与普通应用同样原生塑形。
        val cloneActivities = sidebar
            .filter { (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp }
            .mapNotNull { entry ->
                activities.filter { it.componentName.packageName == entry.packageName }
                    .singleOrNull()?.let { entry.target to it }
            }.toMap()
        val sources = radial.mapNotNull { entry ->
            val info = activityByComponent[entry.target] ?: cloneActivities[entry.target]
            if (info == null) return@mapNotNull entry.target to BitmapDrawable(context.resources, entry.icon)
            try {
                entry.target to info.getIcon(context.resources.displayMetrics.densityDpi)
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
        val radialApps: List<RadialEntry>,
        val panelApps: List<RadialEntry>,
        val radialSources: Map<PinnedTarget, Drawable>,
    )

    /** 在目录工作线程测量可见范围；左侧只平移角标，避免翻转其中的数字。 */
    private fun Bitmap.leftOffsetFraction(): Float {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var right = 0
        pixels.forEachIndexed { index, pixel ->
            if (pixel ushr 24 != 0) {
                val x = index % width
                left = minOf(left, x)
                right = maxOf(right, x + 1)
            }
        }
        return if (left < right) (width - left - right).toFloat() / width else 0f
    }

    private fun ComponentName.toTarget() = PinnedTarget.App(packageName, className)

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

    private fun toEntry(info: LauncherActivityInfo): RadialEntry? =
        try {
            RadialEntry(
                target = info.componentName.toTarget(),
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
