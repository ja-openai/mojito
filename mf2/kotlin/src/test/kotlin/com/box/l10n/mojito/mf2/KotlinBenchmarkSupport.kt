package com.box.l10n.mojito.mf2

internal object KotlinBenchmarkSupport {
    fun utf8Length(value: String): Int {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            bytes += when {
                ch <= '\u007f' -> 1
                ch <= '\u07ff' -> 2
                Character.isHighSurrogate(ch) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            index++
        }
        return bytes
    }
}
