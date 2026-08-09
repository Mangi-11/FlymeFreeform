package io.github.mangi.flymefreeform.config

/** 不依赖 Android 运行时的协议文本规范化，供配置迁移与 JVM 测试共用。 */
internal object PinnedComponentCodec {
    fun decodeRaw(value: String): List<String> =
        value
            .lineSequence()
            .map(String::trim)
            .filter { flattened ->
                val separator = flattened.indexOf('/')
                separator > 0 && separator < flattened.lastIndex
            }
            .distinct()
            .take(ModulePreferences.MAX_PINNED_APPS)
            .toList()
}
