package io.github.mangi.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.IInterface
import android.os.UserHandle
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** 侧边栏目录及执行适配；不保存原生条目，不自行初始化厂商数据所有者。 */
internal class ColorOsSidebarEntries(private val context: Context, loader: ClassLoader) {
    private val helperClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper")
    private val active = helperClass.getMethod("getActiveInstance")
    private val tools = helperClass.getMethod("getAllAppListForTool")
    private val apps = helperClass.getMethod("getShownShortcuts")
    private val shownApps = helperClass.getMethod("getShownApps")
    private val beanClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean")
    private val type = beanClass.getMethod("getType")
    private val category = beanClass.getMethod("getCategory")
    private val alias = beanClass.getMethod("getAlias")
    private val label = beanClass.getMethod("getLabel")
    private val pkg = beanClass.getMethod("getPkg")
    private val activity = beanClass.getMethod("getActivity")
    private val cloned = beanClass.getMethod("isCloned")
    private val cloneUserId = beanClass.getMethod("getMutiUserId")
    private val hidden = beanClass.getMethod("isHidden")
    private val iconRes = beanClass.getMethod("getIconRes")
    private val routeClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils")
    private val launchTool = routeClass.getMethod("startSysTool", beanClass)
    private val launchShortcut = routeClass.getMethod("startShortcut", beanClass)
    private val launchApp = routeClass.getMethod("startNormalApp", beanClass)
    private val shortcutClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.ShortcutEntryHelper")
    private val shortcuts = shortcutClass.getMethod("getActiveInstance")
    private val shortcut = shortcutClass.getMethod("getShortcut", String::class.java)
    private val shortcutIcon = shortcut.returnType.getMethod("getIcon")
    private val handlerClass = loader.loadClass(ColorOsAllAppsContent.HANDLER_CLASS)
    private val handler = handlerClass.getField("INSTANCE").get(null)
    private val getMain = handlerClass.getMethod("getMPanelMainView")
    private val getResident = handlerClass.getMethod("getMResidentProcessHandler")
    private val getState = getMain.returnType.getMethod("getMState")

    fun ready(): Boolean = active.call(null) != null && getMain.call(handler) != null &&
        (getResident.call(handler) as? IInterface)?.asBinder()?.isBinderAlive == true

    fun sidebarHidden(): Boolean = getMain.call(handler)?.let { (getState.call(it) as? Enum<*>)?.name } == "FLOAT_BAR_SHOWING"

    /** 与原生全部面板一致，在工作线程获取目录；图标压到固定上限后才跨进程传输。 */
    fun load(userId: Int): List<SelectableEntry> = entries(includeClones = true).mapNotNull { bean ->
        val target = target(bean, userId) ?: return@mapNotNull null
        val packageName = pkg.call(bean) as? String ?: ""
        val cloneInfo = if (target.kind == PinnedTarget.Kind.CloneApp) {
            clonedActivityInfo(bean, packageName) ?: return@mapNotNull null
        } else null
        val title = (label.call(bean) as? String)?.takeIf { it.isNotBlank() }
            ?: cloneInfo?.label?.toString()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        SelectableEntry(
            target,
            title.take(160),
            packageName.take(256),
            icon(bean, target, packageName, cloneInfo),
            cloneInfo?.let(::cloneBadge),
        )
    }.distinctBy(SelectableEntry::target).also { require(it.size <= SidebarEntryProtocol.MAX_ENTRIES) }

    /** 目录查询在工作线程完成；主线程只校验已解析条目并调用原生路由。 */
    fun resolve(target: PinnedTarget.Sidebar): Any? =
        entries(includeClones = target.kind == PinnedTarget.Kind.CloneApp)
            .firstOrNull { target(it, target.userId) == target }

    fun launch(target: PinnedTarget.Sidebar, current: Any): Boolean {
        if (!beanClass.isInstance(current) || target(current, target.userId) != target) return false
        when (target.kind) {
            PinnedTarget.Kind.Tool -> launchTool
            PinnedTarget.Kind.Shortcut -> launchShortcut
            PinnedTarget.Kind.AppEntry, PinnedTarget.Kind.CloneApp -> launchApp
        }.call(null, current)
        return true
    }

    private fun entries(includeClones: Boolean): List<Any> {
        val owner = active.call(null) ?: throw IllegalStateException("SIDEBAR_ENTRIES_NOT_READY")
        val result = (tools.call(owner) as? List<*>).orEmpty() + (apps.call(owner) as? List<*>).orEmpty() +
            if (includeClones) (shownApps.call(owner) as? List<*>).orEmpty().filter { bean ->
                bean != null && beanClass.isInstance(bean) && cloned.call(bean) == true
            } else emptyList()
        return result.filterNotNull().filter(beanClass::isInstance)
    }

    private fun target(bean: Any, userId: Int): PinnedTarget.Sidebar? {
        if (hidden.call(bean) == true) return null
        val kind = SidebarEntryKind.fromNative(
            type = type.call(bean) as? Int ?: return null,
            category = category.call(bean) as? Int ?: return null,
            isCloned = cloned.call(bean) == true,
            entryUserId = cloneUserId.call(bean) as? Int ?: return null,
            ownerUserId = userId,
        ) ?: return null
        val value = alias.call(bean) as? String ?: return null
        val result = PinnedTarget.Sidebar(kind, value, userId)
        return result.takeIf { PinnedTargetCodec.decode(PinnedTargetCodec.encode(it)) == it }
    }

    private fun icon(bean: Any, target: PinnedTarget.Sidebar, packageName: String, cloneInfo: LauncherActivityInfo?): Bitmap {
        val drawable: Drawable? = when (target.kind) {
            PinnedTarget.Kind.Tool -> {
                val id = iconRes.call(bean) as Int
                if (id == 0) null else context.getDrawable(id)
            }
            // 角标单独生成，避免圆形图标裁剪时切掉右下角。
            PinnedTarget.Kind.CloneApp -> try {
                cloneInfo?.getIcon(context.resources.displayMetrics.densityDpi)
            } catch (_: RuntimeException) { null }
            else -> {
                val owner = shortcuts.call(null)
                val source = owner?.let { shortcut.call(it, target.alias) }
                val bytes = source?.let { shortcutIcon.call(it) } as? ByteArray
                val bitmap = bytes?.takeIf { it.size <= 1_048_576 }?.let {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                        val options = BitmapFactory.Options().apply {
                            // Options 默认值是 0；解码器会归一化，但这里的采样计算需要正除数。
                            inSampleSize = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > SidebarEntryProtocol.ICON_SIZE * 2) inSampleSize *= 2
                        }
                        BitmapFactory.decodeByteArray(it, 0, it.size, options)
                    }
                }
                bitmap?.toDrawable(context.resources)
            }
        }
        val source = drawable ?: try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            context.packageManager.defaultActivityIcon
        }
        return source.toIconBitmap()
    }

    /** 让系统在透明底图上绘制分身用户角标，位置与比例沿用系统徽标规则。 */
    private fun cloneBadge(info: LauncherActivityInfo): Bitmap? {
        val size = SidebarEntryProtocol.ICON_SIZE
        val blank = createBitmap(size, size, Bitmap.Config.ARGB_8888).toDrawable(context.resources)
        val badged = try {
            context.packageManager.getUserBadgedIcon(blank, info.user)
        } catch (_: RuntimeException) {
            return null
        }
        if (badged === blank) return null
        return badged.toIconBitmap().takeIf { bitmap ->
            IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }.any { it ushr 24 != 0 }
        }
    }

    private fun Drawable.toIconBitmap(): Bitmap =
        createBitmap(SidebarEntryProtocol.ICON_SIZE, SidebarEntryProtocol.ICON_SIZE, Bitmap.Config.ARGB_8888).also {
            setBounds(0, 0, it.width, it.height)
            draw(Canvas(it))
        }

    private fun clonedActivityInfo(bean: Any, packageName: String): LauncherActivityInfo? {
        val userId = cloneUserId.call(bean) as? Int ?: return null
        val activityName = activity.call(bean) as? String ?: return null
        if (userId !in 1..21_474) return null
        return try {
            context.getSystemService(LauncherApps::class.java)
                ?.getActivityList(packageName, UserHandle.getUserHandleForUid(userId * 100_000))
                ?.firstOrNull { it.componentName == ComponentName(packageName, activityName) }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun Method.call(receiver: Any?, vararg args: Any?): Any? = try {
        invoke(receiver, *args)
    } catch (exception: InvocationTargetException) {
        val cause = exception.cause
        if (cause is Error) throw cause
        throw exception
    }
}
