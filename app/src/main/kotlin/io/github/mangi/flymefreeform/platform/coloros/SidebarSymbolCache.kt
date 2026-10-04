package io.github.mangi.flymefreeform.platform.coloros

import java.io.File
import java.io.IOException
import java.util.Properties

internal object SidebarSymbolCache {
    fun read(file: File, identity: String): Map<String, String>? {
        if (!file.isFile || file.length() > 16_384) return null
        val properties = Properties()
        try { file.inputStream().use { properties.load(it) } } catch (_: IllegalArgumentException) { return null }
        if (properties.remove("identity") != identity) return null
        val map = properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        val required = setOf("resource", "landscape", "portrait", "hover", "unit", "shadowKind", "blurKind")
        val shadow = when (map["shadowKind"]) {
            "available" -> setOf("shadowSetup", "shadowUpdate")
            "unavailable" -> emptySet()
            else -> return null
        }
        val blur = when (map["blurKind"]) {
            "platform", "platform-default" -> setOf("blurAttach", "blurUpdate", "blurClose")
            "legacy" -> setOf("blurAttach", "blurUpdate", "blurSupports")
            "unavailable" -> emptySet()
            else -> return null
        }
        val expected = required + shadow + blur
        if (map.keys != expected) return null
        // 在交给 DexKit 描述符解析器之前拒绝损坏的缓存，避免其格式错误 Error。
        val objectType = "L[A-Za-z0-9_/$]+;"
        val type = "\\[*(?:[ZBCSIJFD]|$objectType)"
        val member = "$objectType->[A-Za-z0-9_$]+"
        val field = Regex("$member:$type")
        val method = Regex("$member\\((?:$type)*\\)(?:V|$type)")
        if (map.filterKeys { it != "shadowKind" && it != "blurKind" }.any { (key, value) ->
                !(if (key in setOf("resource", "unit")) field else method).matches(value)
            }) return null
        return map
    }

    fun write(file: File, identity: String, symbols: Map<String, String>) {
        val directory = checkNotNull(file.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("SIDEBAR_CACHE_DIRECTORY_FAILED")
        val temporary = File.createTempFile("sidebar-", ".tmp", directory)
        try {
            val properties = Properties().apply { putAll(symbols); setProperty("identity", identity) }
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(file)) throw IOException("SIDEBAR_CACHE_RENAME_FAILED")
        } finally {
            if (temporary.exists() && !temporary.delete()) throw IOException("SIDEBAR_CACHE_CLEANUP_FAILED")
        }
    }
}
