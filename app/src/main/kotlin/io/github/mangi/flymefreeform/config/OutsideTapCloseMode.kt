package io.github.mangi.flymefreeform.config

/** 窗外短点击关闭协议；存储值属于已发布配置格式，不得改作其他含义。 */
internal enum class OutsideTapCloseMode(val storedValue: Int) {
    Disabled(0),
    SingleTap(1),
    DoubleTap(2),
    ;

    companion object {
        fun fromStoredValue(value: Int): OutsideTapCloseMode =
            entries.firstOrNull { it.storedValue == value } ?: Disabled
    }
}
