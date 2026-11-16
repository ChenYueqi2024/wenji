package org.wenji.study

/** Keep each SQLite TEXT row small without splitting UTF-16 surrogate pairs. */
internal fun storageChunks(text: String, size: Int = 24_000): List<String> {
    require(size >= 2)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + size, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
