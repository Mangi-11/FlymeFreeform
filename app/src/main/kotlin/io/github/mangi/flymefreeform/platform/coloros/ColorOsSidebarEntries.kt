package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.IInterface
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** 16.14.6 的目录及执行适配；不保存原生条目，不自行初始化厂商数据所有者。 */
internal class ColorOsSidebarEntries(private val context: Context, loader: ClassLoader) {
    private val helperClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper")
    private val active = helperClass.getMethod("getActiveInstance")
    private val tools = helperClass.getMethod("getAllAppListForTool")
    private val apps = helperClass.getMethod("getShownShortcuts")
    private val beanClass = loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean")
    private val type = beanClass.getMethod("getType")
    private val category = beanClass.getMethod("getCategory")
    private val alias = beanClass.getMethod("getAlias")
    private val label = beanClass.getMethod("getLabel")
    private val pkg = beanClass.getMethod("getPkg")
    private val cloned = beanClass.getMethod("isCloned")
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
    fun load(userId: Int): List<SelectableEntry> = entries().mapNotNull { bean ->
        val target = target(bean, userId) ?: return@mapNotNull null
        val title = (label.call(bean) as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val packageName = pkg.call(bean) as? String ?: ""
        SelectableEntry(target, title.take(160), packageName.take(256), icon(bean, target, packageName))
    }.distinctBy(SelectableEntry::target).also { require(it.size <= SidebarEntryProtocol.MAX_ENTRIES) }

    /** 主线程只从已加载目录定位目标并调用原生路由；路由自行持有其后续任务。 */
    fun launch(target: PinnedTarget.Sidebar): Boolean {
        val current = entries().firstOrNull { target(it, target.userId) == target } ?: return false
        when (target.kind) {
            PinnedTarget.Kind.Tool -> launchTool
            PinnedTarget.Kind.Shortcut -> launchShortcut
            PinnedTarget.Kind.AppEntry -> launchApp
        }.call(null, current)
        return true
    }

    private fun entries(): List<Any> {
        val owner = active.call(null) ?: throw IllegalStateException("SIDEBAR_ENTRIES_NOT_READY")
        val result = (tools.call(owner) as? List<*>).orEmpty() + (apps.call(owner) as? List<*>).orEmpty()
        return result.filterNotNull().filter(beanClass::isInstance)
    }

    private fun target(bean: Any, userId: Int): PinnedTarget.Sidebar? {
        if (cloned.call(bean) == true || hidden.call(bean) == true) return null
        val kind = when (type.call(bean)) {
            0 -> PinnedTarget.Kind.Tool
            1 -> PinnedTarget.Kind.Shortcut
            // 原生目录把小布等特定包的桌面入口归为类别 11，仍需用其应用路由启动。
            2 -> if (category.call(bean) == 11) PinnedTarget.Kind.AppEntry else return null
            else -> return null
        }
        val value = alias.call(bean) as? String ?: return null
        val result = PinnedTarget.Sidebar(kind, value, userId)
        return result.takeIf { PinnedTargetCodec.decode(PinnedTargetCodec.encode(it)) == it }
    }

    private fun icon(bean: Any, target: PinnedTarget.Sidebar, packageName: String): Bitmap {
        val drawable: Drawable? = if (target.kind == PinnedTarget.Kind.Tool) {
            val id = iconRes.call(bean) as Int
            if (id == 0) null else context.getDrawable(id)
        } else {
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
            bitmap?.let { BitmapDrawable(context.resources, it) }
        }
        val source = drawable ?: try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            context.packageManager.defaultActivityIcon
        }
        return Bitmap.createBitmap(SidebarEntryProtocol.ICON_SIZE, SidebarEntryProtocol.ICON_SIZE, Bitmap.Config.ARGB_8888).also {
            source.setBounds(0, 0, it.width, it.height)
            source.draw(Canvas(it))
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
