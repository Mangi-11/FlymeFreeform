package io.github.mangi.flymefreeform.apps

import android.graphics.Bitmap
import io.github.mangi.flymefreeform.config.PinnedTarget

internal data class SelectableEntry(
    val target: PinnedTarget,
    val label: String,
    val packageName: String,
    val icon: Bitmap,
)
