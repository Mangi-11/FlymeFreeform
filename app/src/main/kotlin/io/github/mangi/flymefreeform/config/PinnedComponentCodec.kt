package io.github.mangi.flymefreeform.config

/** 不依赖 Android 运行时的协议文本规范化，供配置迁移与 JVM 测试共用。 */
internal object PinnedComponentCodec {
    fun decodeRaw(value: String): List<String> =
        value
            .lineSequence()
            .map(String::trim)
            .filter { encoded ->
                val component = componentPart(encoded)
                val separator = component.indexOf('/')
                separator > 0 && separator < component.lastIndex
            }
            .distinct()
            .take(ModulePreferences.MAX_PINNED_APPS)
            .toList()

    fun parseUserSuffix(encoded: String): Int? =
        encoded
            .substringAfterLast(USER_SEPARATOR, missingDelimiterValue = "")
            .takeIf(String::isNotEmpty)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }

    fun componentPart(encoded: String): String = encoded.substringBefore(USER_SEPARATOR)

    const val USER_SEPARATOR = '#'
}
