package io.github.mangi.flymefreeform.platform.coloros

/** 同一有效期内不重复接收请求；容量满时拒绝新请求，不驱逐仍可能重放的记录。 */
internal class EntryRequestHistory(private val capacity: Int = 32) {
    private val deadlines = mutableMapOf<String, Long>()

    fun claim(id: String, deadline: Long, now: Long): Boolean {
        deadlines.entries.removeAll { it.value <= now }
        if (deadline <= now || id in deadlines || deadlines.size >= capacity) return false
        deadlines[id] = deadline
        return true
    }
}
