package io.github.mangi.flymefreeform.apps

import android.graphics.Bitmap
import io.github.mangi.flymefreeform.config.PinnedTarget

/** `badge` 是与 `icon` 同尺寸的透明叠加层，绘制时不随图标形状裁剪。 */
internal data class SelectableEntry(
    val target: PinnedTarget,
    val label: String,
    val packageName: String,
    val icon: Bitmap,
    val badge: Bitmap? = null,
)
