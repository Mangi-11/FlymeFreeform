package io.github.mangi.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process

internal data class AppShortcut(val packageName: String, val id: String, val label: String, val icon: Bitmap?)

internal class ColorOsAppShortcuts(context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val iconSize = (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val densityDpi = context.resources.displayMetrics.densityDpi

    fun forActivity(component: ComponentName): List<AppShortcut> {
        val service = launcherApps ?: return emptyList()
        return try {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(component.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            service.getShortcuts(query, Process.myUserHandle())
                .orEmpty()
                .asSequence()
                .filter { it.isEnabled && (it.activity == null || it.activity == component) }
                .sortedBy { it.rank }
                .distinctBy { it.id }
                .take(MAX_SHORTCUTS)
                .map { info ->
                    val icon = try {
                        service.getShortcutIconDrawable(info, densityDpi)?.let { drawable ->
                            Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888).also { bitmap ->
                                drawable.setBounds(0, 0, iconSize, iconSize)
                                drawable.draw(Canvas(bitmap))
                                bitmap.prepareToDraw()
                            }
                        }
                    } catch (_: RuntimeException) {
                        null
                    }
                    AppShortcut(info.`package`, info.id, info.shortLabel.toString(), icon)
                }
                .toList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    companion object {
        const val MAX_SHORTCUTS = 4
    }
}
