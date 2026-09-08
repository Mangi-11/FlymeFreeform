package io.github.mangi.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/** 仅启用已核对的系统包；包名相同或方法名相似不足以证明兼容。 */
internal object ColorOsSidebarTarget {
    const val PACKAGE_NAME = "com.coloros.smartsidebar"
    const val PROCESS_NAME = "$PACKAGE_NAME:ui"
    const val SERVICE_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.UIService"
    const val BIND_ACTION = "io.github.mangi.flymefreeform.action.BIND_ALL_APPS_V1"
    const val REQUIRED_PERMISSION = "oppo.permission.OPPO_COMPONENT_SAFE"
    const val PROVIDER_AUTHORITY = "com.coloros.sidebar"
    const val PREPARE_METHOD = "bindServiceForTransferDock"

    fun supportedUid(context: Context): Int? {
        if (Build.VERSION.SDK_INT != 36) return null
        val manager = context.packageManager
        return try {
            val info = manager.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
            if (info.longVersionCode != 160014006L || info.versionName != "16.14.6") return null
            val service =
                manager.getServiceInfo(
                    ComponentName(PACKAGE_NAME, SERVICE_CLASS),
                    PackageManager.ComponentInfoFlags.of(0),
                )
            val app = service.applicationInfo
            val provider = manager.resolveContentProvider(PROVIDER_AUTHORITY, PackageManager.ComponentInfoFlags.of(0))
                ?: return null
            if (provider.packageName != PACKAGE_NAME ||
                provider.name != "com.coloros.edgepanel.api.SideBarProvider" ||
                provider.applicationInfo.uid != app.uid || !provider.enabled || !provider.exported ||
                provider.readPermission != "com.coloros.sidebar.permission.data" ||
                provider.writePermission != "com.coloros.sidebar.permission.data"
            ) return null
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            if (!app.enabled || !service.enabled || !service.exported ||
                app.flags and systemFlags == 0 ||
                app.flags and ApplicationInfo.FLAG_SUSPENDED != 0 ||
                service.permission != REQUIRED_PERMISSION || service.processName != PROCESS_NAME
            ) {
                null
            } else {
                app.uid
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
