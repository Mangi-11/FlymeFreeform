package io.github.mangi.flymefreeform.config

import java.util.Base64

/** 固定项身份不包含可执行 Intent；侧边栏目标必须由所属用户的实时目录重新解析。 */
internal sealed interface PinnedTarget {
    data class App(val packageName: String, val className: String) : PinnedTarget
    data class Sidebar(val kind: Kind, val alias: String, val userId: Int) : PinnedTarget
    enum class Kind { Tool, Shortcut, AppEntry }
}

internal object PinnedTargetCodec {
    private const val MAX_TEXT = 16_384
    private const val MAX_PART = 1_024
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

    fun encode(target: PinnedTarget): String = when (target) {
        is PinnedTarget.App -> "A|${target.packageName}/${target.className}"
        is PinnedTarget.Sidebar -> {
            val kind = when (target.kind) {
                PinnedTarget.Kind.Tool -> "T"
                PinnedTarget.Kind.Shortcut -> "S"
                PinnedTarget.Kind.AppEntry -> "N"
            }
            "$kind|${target.userId}|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(target.alias.toByteArray(Charsets.UTF_8))
        }
    }

    fun decode(value: String): PinnedTarget? {
        if (value.length > MAX_TEXT) return null
        val parts = value.split('|')
        if (parts.size == 2 && parts[0] == "A") return decodeApp(parts[1])
        if (parts.size != 3 || parts[0] !in setOf("T", "S", "N")) return null
        val user = parts[1].toIntOrNull()?.takeIf { it in 0..21_474 } ?: return null
        if (parts[1] != user.toString() || parts[2].length > MAX_PART * 2) return null
        val alias = try {
            val bytes = Base64.getUrlDecoder().decode(parts[2])
            if (bytes.size > MAX_PART) return null
            String(bytes, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) { return null }
        if (alias.isBlank() || alias.length > MAX_PART || alias.any(Char::isISOControl)) return null
        val result = PinnedTarget.Sidebar(
            when (parts[0]) {
                "T" -> PinnedTarget.Kind.Tool
                "S" -> PinnedTarget.Kind.Shortcut
                else -> PinnedTarget.Kind.AppEntry
            },
            alias,
            user,
        )
        return result.takeIf { encode(it) == value }
    }

    fun decodeApp(value: String): PinnedTarget.App? {
        if (value.length > MAX_PART) return null
        val parts = value.split('/')
        if (parts.size != 2 || !packagePattern.matches(parts[0])) return null
        val name = if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
        if (!classPattern.matches(name)) return null
        return PinnedTarget.App(parts[0], name)
    }

    fun decodeList(value: String): List<PinnedTarget> =
        if (value.length > MAX_TEXT) emptyList() else normalize(value.lineSequence().mapNotNull(::decode).toList())

    fun decodeLegacy(value: String): List<PinnedTarget> =
        if (value.length > MAX_TEXT) emptyList() else
            normalize(value.lineSequence().map { it.trim() }.mapNotNull(::decodeApp).toList())

    fun normalize(targets: List<PinnedTarget>): List<PinnedTarget> =
        targets.asSequence().mapNotNull { decode(encode(it)) }.distinct()
            .take(ModulePreferences.MAX_PINNED_APPS).toList()

    fun encodeList(targets: List<PinnedTarget>): String = normalize(targets).joinToString("\n", transform = ::encode)
}
