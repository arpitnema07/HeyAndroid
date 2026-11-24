package com.example.heyandroid.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIntentTest {

    @Test
    fun `a question mark or a question word is a question`() {
        assertTrue(MemoryIntent.isQuestion("Where did I put the keys?"))
        assertTrue(MemoryIntent.isQuestion("what did i watch"))
        assertTrue(MemoryIntent.isQuestion("Show me my tasks"))
        assertFalse(MemoryIntent.isQuestion("I watched E2"))
    }

    @Test
    fun `statements that ask to be kept are recorded`() {
        assertTrue(MemoryIntent.shouldRecord("remember I finished E2"))
        assertTrue(MemoryIntent.shouldRecord("Don't forget the plumber on Sunday"))
        assertTrue(MemoryIntent.shouldRecord("I started watching Lantern"))
        assertTrue(MemoryIntent.shouldRecord("note that the wifi password changed"))
    }

    @Test
    fun `questions are never recorded`() {
        assertFalse(MemoryIntent.shouldRecord("What did I finish?"))
        assertFalse(MemoryIntent.shouldRecord("what did i watch"))
        assertFalse(MemoryIntent.shouldRecord("did i watch the finale"))
    }

    @Test
    fun `empty input records nothing`() {
        assertFalse(MemoryIntent.shouldRecord("   "))
        assertFalse(MemoryIntent.shouldRecord(""))
    }

    @Test
    fun `retrieval is the question path`() {
        assertTrue(MemoryIntent.shouldRetrieve("when did I finish the book"))
        assertFalse(MemoryIntent.shouldRetrieve("remember the book"))
    }
}
