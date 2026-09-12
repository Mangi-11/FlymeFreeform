package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import io.github.mangi.flymefreeform.apps.AppSelectionPolicy
import io.github.mangi.flymefreeform.apps.AppTarget
import io.github.mangi.flymefreeform.apps.identifier
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal data class RadialAppEntry(
    val target: AppTarget,
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
            this.settings.pinnedTargets == settings.pinnedTargets
}

/** 目录查询只在后台执行；发布后的 Bitmap 与列表供手势热路径只读。 */
internal class ColorOsAppCatalog(
    private val context: Context,
    private val executor: Executor,
    private val logger: (Int, String, Throwable?) -> Unit,
    private val publish: (AppCatalogSnapshot) -> Unit,
) {
    private val iconRenderer = ColorOsRadialIconRenderer(context.resources, logger)

    private val contentRevision = AtomicLong()
    private var cachedContent: CatalogContent? = null // 仅目录工作线程访问。
    private var entryDropLogBudget = 0 // 仅目录工作线程访问。

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
            shapedRadial.forEach { it.icon.prepareToDraw() }
            publish(AppCatalogSnapshot(shapedRadial, content.panelApps, settings))
        }
    }

    private fun loadContent(settings: ModuleSettingsSnapshot, revision: Long): CatalogContent? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        entryDropLogBudget = ENTRY_DROP_LOG_BUDGET
        val activities = mutableListOf<Pair<AppTarget, LauncherActivityInfo>>()
        val profileSummary = mutableListOf<String>()
        profileUsers(launcherApps).forEach { user ->
            val loaded =
                try {
                    launcherApps.getActivityList(null, user)
                } catch (exception: SecurityException) {
                    logger(Log.WARN, "CATALOG_USER_DENIED user=${user.identifier}", exception)
                    emptyList()
                } catch (exception: RuntimeException) {
                    logger(Log.WARN, "CATALOG_USER_FAILED user=${user.identifier}", exception)
                    emptyList()
                }
            profileSummary += "${user.identifier}=${loaded.size}"
            loaded.mapTo(activities) { info -> AppTarget(info.componentName, user.identifier) to info }
        }
        logger(
            Log.INFO,
            "CATALOG_PROFILES [${profileSummary.joinToString(",")}] " +
                "pins=[${settings.pinnedTargets.joinToString(",") { target -> target.storageKey }}]",
            null,
        )
        val launcherEntries =
            activities
                .asSequence()
                .filterNot { (target, _) -> target.component.packageName == MODULE_PACKAGE }
                .mapNotNull { (target, info) -> toEntry(target, info) }
                .distinctBy { entry -> entry.target.storageKey }
                .toList()
        // 只有真正产出条目的目标才算已解析：条目被丢弃的固定项必须回退直查，否则会静默消失。
        val resolvedTargets = launcherEntries.mapTo(hashSetOf(), RadialAppEntry::target)
        val fallbackCache = HashMap<AppTarget, Pair<RadialAppEntry, Drawable>?>()
        fun fallbackFor(target: AppTarget): Pair<RadialAppEntry, Drawable>? =
            if (fallbackCache.containsKey(target)) {
                fallbackCache[target]
            } else {
                pinnedEntry(launcherApps, target).also { resolved -> fallbackCache[target] = resolved }
            }
        val entries =
            buildList {
                addAll(launcherEntries)
                settings.pinnedTargets
                    .filterNot(resolvedTargets::contains)
                    .mapNotNullTo(this) { target -> fallbackFor(target)?.first }
            }
        val byTarget = entries.associateBy(RadialAppEntry::target)
        val recents =
            recentTargets()
                .mapNotNull(byTarget::get)
                .distinctBy { entry -> entry.target.storageKey }
        val collator = Collator.getInstance(Locale.getDefault())
        val alphabetical =
            entries.sortedWith { first, second ->
                collator.compare(first.label, second.label)
            }
        val radial =
            AppSelectionPolicy.radialItems(
                pinsSaved = settings.pinsSaved,
                availablePins = settings.pinnedTargets.mapNotNull(byTarget::get),
                recent = recents,
                all = alphabetical,
                identity = RadialAppEntry::target,
                limit = io.github.mangi.flymefreeform.config.ModulePreferences.MAX_PINNED_APPS,
            )
        val excluded = radial.mapTo(HashSet(), RadialAppEntry::target)
        val panel =
            AppSelectionPolicy.panelItems(
                recent = recents,
                all = alphabetical,
                excluded = excluded,
                identity = RadialAppEntry::target,
            )
        val radialTargets = radial.mapTo(HashSet(), RadialAppEntry::target)
        settings.pinnedTargets
            .filterNot(radialTargets::contains)
            .forEach { target -> logger(Log.WARN, "CATALOG_PIN_UNRESOLVED ${target.storageKey}", null) }
        logger(
            Log.INFO,
            "CATALOG_RADIAL pinsSaved=${settings.pinsSaved} count=${radial.size} " +
                "[${radial.joinToString(",") { entry -> entry.target.storageKey }}]",
            null,
        )
        val activityByTarget = activities.toMap()
        val pinnedSources =
            settings.pinnedTargets
                .filterNot(resolvedTargets::contains)
                .mapNotNull { target -> fallbackFor(target)?.let { (_, drawable) -> target to drawable } }
                .toMap()
        val sources = radial.mapNotNull { entry ->
            val drawable =
                pinnedSources[entry.target] ?: activityByTarget[entry.target]?.let { info ->
                    try {
                        info.getIcon(context.resources.displayMetrics.densityDpi)
                    } catch (exception: RuntimeException) {
                        logger(
                            Log.WARN,
                            "CATALOG_ICON_FAILED ${entry.target.storageKey}",
                            exception,
                        )
                        null
                    }
                }
            if (drawable == null) {
                logger(Log.WARN, "CATALOG_SOURCE_MISSING ${entry.target.storageKey}", null)
                null
            } else {
                entry.target to drawable
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
        val radialSources: Map<AppTarget, Drawable>,
    )

    private fun profileUsers(launcherApps: LauncherApps): List<UserHandle> {
        val userManager = context.getSystemService(UserManager::class.java)
        return buildList {
                add(Process.myUserHandle())
                addAll(userManager?.userProfiles.orEmpty())
                addAll(
                    try {
                        launcherApps.profiles
                    } catch (_: RuntimeException) {
                        emptyList()
                    },
                )
            }
            .distinct()
    }

    private fun recentTargets(): List<AppTarget> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            activityManager.getRecentTasks(RECENT_LIMIT, ActivityManager.RECENT_WITH_EXCLUDED)
                .mapNotNull { task ->
                    val component = task.origActivity ?: task.baseIntent.component ?: return@mapNotNull null
                    AppTarget(component, taskUserIdentifier(task))
                }
                .filterNot { target -> target.component.packageName == context.packageName }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun taskUserIdentifier(task: ActivityManager.RecentTaskInfo): Int =
        try {
            task.javaClass
                .getDeclaredField("userId")
                .also { it.isAccessible = true }
                .getInt(task)
        } catch (_: ReflectiveOperationException) {
            Process.myUserHandle().identifier
        } catch (_: RuntimeException) {
            Process.myUserHandle().identifier
        }

    private fun toEntry(target: AppTarget, info: LauncherActivityInfo): RadialAppEntry? =
        try {
            val baseLabel =
                info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName }
            RadialAppEntry(
                target = target,
                label = target.displayLabel(baseLabel),
                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
            )
        } catch (exception: RuntimeException) {
            reportDroppedEntry(target, exception)
            null
        }

    private fun reportDroppedEntry(target: AppTarget, exception: RuntimeException) {
        if (entryDropLogBudget <= 0) return
        entryDropLogBudget--
        logger(Log.WARN, "CATALOG_ENTRY_DROPPED ${target.storageKey}", exception)
    }

    private fun pinnedEntry(
        launcherApps: LauncherApps,
        target: AppTarget,
    ): Pair<RadialAppEntry, Drawable>? {
        logger(
            Log.INFO,
            "CATALOG_CLONE_QUERY user=${target.userId} target=${target.component.flattenToShortString()}",
            null,
        )
        try {
            val launcherInfo =
                launcherApps
                    .getActivityList(target.component.packageName, target.user)
                    .firstOrNull { info -> info.componentName == target.component }
            if (launcherInfo != null) {
                val entry = toEntry(target, launcherInfo) ?: return null
                val drawable =
                    try {
                        launcherInfo.getIcon(context.resources.displayMetrics.densityDpi)
                    } catch (_: RuntimeException) {
                        return null
                    }
                logger(Log.INFO, "CATALOG_CLONE_LAUNCHER_OK user=${target.userId}", null)
                return entry to drawable
            }
        } catch (exception: SecurityException) {
            logger(Log.WARN, "CATALOG_CLONE_LAUNCHER_DENIED user=${target.userId}", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "CATALOG_CLONE_LAUNCHER_FAILED user=${target.userId}", exception)
        }

        try {
            val packageManager = context.packageManager
            val method =
                packageManager.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == "getActivityInfoAsUser" &&
                        candidate.parameterTypes.contentEquals(
                            arrayOf(
                                ComponentName::class.java,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                            ),
                        )
                }
                    ?: throw NoSuchMethodException("getActivityInfoAsUser")
            val info = method.invoke(packageManager, target.component, 0, target.userId) as ActivityInfo
            if (!info.enabled || !info.applicationInfo.enabled) return null
            val drawable = info.loadIcon(packageManager)
            val entry = createEntry(target, info.loadLabel(packageManager), drawable) ?: return null
            logger(Log.INFO, "CATALOG_CLONE_PACKAGE_OK user=${target.userId}", null)
            return entry to drawable
        } catch (exception: PackageManager.NameNotFoundException) {
            logger(Log.WARN, "CATALOG_CLONE_PACKAGE_MISSING user=${target.userId}", exception)
            return null
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "CATALOG_CLONE_PACKAGE_UNAVAILABLE user=${target.userId}", exception)
            return null
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "CATALOG_CLONE_PACKAGE_FAILED user=${target.userId}", exception)
            return null
        }
    }

    private fun createEntry(
        target: AppTarget,
        label: CharSequence?,
        drawable: Drawable,
    ): RadialAppEntry? =
        try {
            val baseLabel =
                label?.toString()?.trim().orEmpty().ifEmpty {
                    target.component.packageName
                }
            RadialAppEntry(target, target.displayLabel(baseLabel), drawable.toBitmap())
        } catch (exception: RuntimeException) {
            reportDroppedEntry(target, exception)
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
        const val ENTRY_DROP_LOG_BUDGET = 24
    }
}
