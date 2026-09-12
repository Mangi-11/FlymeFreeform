package io.github.mangi.flymefreeform.apps

/** 拒绝缺页、重复身份、乱序及超量目录，失败后不能把部分条目当作完整目录。 */
internal class CatalogTransfer<T, K>(private val limit: Int, private val identity: (T) -> K) {
    private val entries = mutableListOf<T>()
    private val keys = hashSetOf<K>()
    private var closed = false

    fun append(index: Int, entry: T): Boolean {
        if (closed || index != entries.size || entries.size >= limit || !keys.add(identity(entry))) {
            cancel()
            return false
        }
        entries += entry
        return true
    }

    fun complete(count: Int): List<T>? {
        if (closed || count != entries.size) { cancel(); return null }
        val result = entries.toList()
        cancel()
        return result
    }

    fun cancel() { closed = true; entries.clear(); keys.clear() }
}
