package io.github.mangi.flymefreeform.platform.coloros

import java.io.File
import java.io.IOException

/** 缓存仅包含符号；绑定 APK 内容与查询规则，不跨 APK 更新复用。调用方在后台执行 I/O。 */
internal object LauncherSymbolCache {
    fun identity(apk: File): String = DexApkIdentity.compute(apk, LauncherDexQuery.RULE_VERSION)

    fun read(file: File, identity: String): LauncherDexQuery.Symbols? {
        if (!file.isFile || file.length() > 4096) return null
        val lines = file.readLines()
        if (lines.size != 5 || lines[0] != identity) return null
        if (lines.drop(1).any { !it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*")) }) return null
        return LauncherDexQuery.Symbols(lines[1], lines[2], lines[3], lines[4])
    }

    fun write(file: File, identity: String, symbols: LauncherDexQuery.Symbols) {
        val directory = file.parentFile ?: throw IOException("LAUNCHER_CACHE_DIRECTORY_MISSING")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("LAUNCHER_CACHE_DIRECTORY_FAILED")
        val temporary = File.createTempFile("launcher-", ".tmp", directory)
        try {
            temporary.writeText(
                listOf(identity, symbols.inputMethod, symbols.monitorField, symbols.wrapperClass, symbols.pilferMethod)
                    .joinToString("\n"),
            )
            if (!temporary.renameTo(file)) throw IOException("LAUNCHER_CACHE_RENAME_FAILED")
        } finally {
            if (temporary.exists() && !temporary.delete()) throw IOException("LAUNCHER_CACHE_CLEANUP_FAILED")
        }
    }
}
