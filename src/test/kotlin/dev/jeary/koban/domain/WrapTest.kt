package dev.jeary.koban.domain

import dev.jeary.koban.domain.report.Wrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WrapTest {

    @Test
    fun `short text stays on one line`() {
        assertEquals(listOf("hello there"), Wrap.lines("hello there", 20))
    }

    @Test
    fun `breaks at spaces and never exceeds the width`() {
        val lines = Wrap.lines("the quick brown fox jumps over the lazy dog", 10)
        assertEquals(listOf("the quick", "brown fox", "jumps over", "the lazy", "dog"), lines)
        assertTrue(lines.all { it.length <= 10 })
    }

    @Test
    fun `a word longer than the width is cut`() {
        assertEquals(listOf("aaaaa", "aaaaa", "aa b"), Wrap.lines("aaaaaaaaaaaa b", 5))
    }

    @Test
    fun `too many lines end in an ellipsis`() {
        val lines = Wrap.lines("one two three four five six", 5, maxLines = 2)
        assertEquals(listOf("one", "two…"), lines)
    }

    @Test
    fun `empty and blank text give no lines`() {
        assertEquals(emptyList<String>(), Wrap.lines("", 10))
        assertEquals(emptyList<String>(), Wrap.lines("   ", 10))
    }
}
