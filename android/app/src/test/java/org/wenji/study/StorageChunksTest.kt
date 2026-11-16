package org.wenji.study

import org.junit.Assert.*
import org.junit.Test

class StorageChunksTest {
    @Test fun largeChineseCacheAndEmojiRoundTripThroughUtf8() {
        val original = "中".repeat(23_999) + "\uD83D\uDE42" + "课堂".repeat(200_000)
        val chunks = storageChunks(original)
        assertTrue(chunks.all { it.length <= 24_000 })
        assertTrue(chunks.none { it.last().isHighSurrogate() })
        assertEquals(original, chunks.joinToString("") { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) })
    }
    @Test fun emptyAndExactBoundaryArePreserved() {
        assertTrue(storageChunks("").isEmpty())
        assertEquals(listOf("abcd", "efgh"), storageChunks("abcdefgh", 4))
    }
}
