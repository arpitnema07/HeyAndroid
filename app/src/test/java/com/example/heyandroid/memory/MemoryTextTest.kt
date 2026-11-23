package com.example.heyandroid.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTextTest {

    @Test
    fun `list content keeps old items when new details arrive`() {
        val existing = "- long-press screenshot\n- transactions"
        val incoming = "- long-press screenshot\n- transactions\n- markdown reader"

        val merged = MemoryText.mergeContent(existing, incoming)

        assertTrue(merged.contains("long-press screenshot"))
        assertTrue(merged.contains("transactions"))
        assertTrue(merged.contains("markdown reader"))
    }

    @Test
    fun `a shortened list does not delete earlier items`() {
        val existing = "- one\n- two\n- three"
        val incoming = "- three"

        val merged = MemoryText.mergeContent(existing, incoming)

        assertEquals(existing, merged)
    }

    @Test
    fun `content is capped with an explicit marker`() {
        val huge = "x".repeat(MemoryText.MAX_CONTENT + 500)

        val capped = MemoryText.capContent(huge)

        assertTrue(capped.endsWith(MemoryText.TRUNCATED_MARKER))
        assertTrue(capped.length <= MemoryText.MAX_CONTENT)
    }

    @Test
    fun `plural subjects match`() {
        assertTrue(MemoryText.similarSubjects("lantern", "lanterns"))
        assertTrue(MemoryText.similarSubjects("Lantern S1", "lantern"))
        assertFalse(MemoryText.similarSubjects("lantern", "app about screen"))
    }

    @Test
    fun `mismatched id subjects are rejected`() {
        assertFalse(
            MemoryText.matchesMemory(
                subject = "lantern",
                title = "Lantern S1",
                existingSubject = "app about screen details"
            )
        )
        assertTrue(
            MemoryText.matchesMemory(
                subject = "lantern",
                title = "Lantern S1",
                existingSubject = "lantern s1"
            )
        )
    }

    @Test
    fun `titles are shortened at a word boundary`() {
        val title = MemoryText.shortTitle(
            "in the app bulb list I want bug list app bug list I want you to note things"
        )
        assertTrue(title.length <= 43)
        assertTrue(title.endsWith("…"))
    }
}
