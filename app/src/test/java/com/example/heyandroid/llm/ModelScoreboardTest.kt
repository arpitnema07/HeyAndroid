package com.example.heyandroid.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelScoreboardTest {

    private fun scoreboard(prefs: FakeSharedPreferences = FakeSharedPreferences()) =
        ModelScoreboard(prefs)

    @Test
    fun `a model never called sits in the middle and is not benched`() {
        val entry = scoreboard().entry("groq:new-model")
        assertEquals(0.5, entry.score, 0.0001)
        assertEquals(0, entry.attempts)
        assertFalse(entry.benched)
    }

    @Test
    fun `a fast answer scores near the top`() {
        val board = scoreboard()
        board.recordSuccess("groq:fast", 900)
        val entry = board.entry("groq:fast")
        assertEquals(1, entry.successes)
        assertEquals(0, entry.failures)
        assertEquals(1.0, entry.score, 0.0001)
        assertFalse(entry.benched)
    }

    @Test
    fun `a slow answer scores below a fast one`() {
        val board = scoreboard()
        board.recordSuccess("groq:fast", 900)
        board.recordSuccess("groq:slow", 12_000)
        assertTrue(board.score("groq:fast") > board.score("groq:slow"))
    }

    @Test
    fun `two failures in a row bench a model`() {
        val board = scoreboard()
        board.recordFailure("gemini:broken")
        assertFalse(board.isBenched("gemini:broken"))
        board.recordFailure("gemini:broken")
        assertTrue(board.isBenched("gemini:broken"))
    }

    @Test
    fun `a success clears the failure streak`() {
        val board = scoreboard()
        board.recordFailure("groq:flaky")
        board.recordSuccess("groq:flaky", 800)
        board.recordFailure("groq:flaky")
        assertFalse(board.isBenched("groq:flaky"))
    }

    @Test
    fun `a model that keeps failing is benched without two failures in a row`() {
        val board = scoreboard()
        // F S F S F: three failures in five attempts, never two in a row.
        board.recordFailure("nvidia:dying")
        board.recordSuccess("nvidia:dying", 1_000)
        board.recordFailure("nvidia:dying")
        board.recordSuccess("nvidia:dying", 1_000)
        board.recordFailure("nvidia:dying")
        val entry = board.entry("nvidia:dying")
        assertEquals(1, entry.consecutiveFailures)
        assertEquals(0.6, entry.errorRate, 0.0001)
        assertTrue(entry.benched)
    }

    @Test
    fun `a short bad record is not enough to bench a model`() {
        val board = scoreboard()
        board.recordFailure("tokenharbor:new")
        board.recordSuccess("tokenharbor:new", 1_000)
        board.recordFailure("tokenharbor:new")
        assertFalse(board.isBenched("tokenharbor:new"))
    }

    @Test
    fun `the average latency is a running average`() {
        val board = scoreboard()
        board.recordSuccess("groq:steady", 1_000)
        board.recordSuccess("groq:steady", 9_000)
        assertEquals(5_000, board.entry("groq:steady").averageLatencyMs)
        board.recordSuccess("groq:steady", 2_000)
        assertEquals(4_000, board.entry("groq:steady").averageLatencyMs)
    }

    @Test
    fun `a record survives a reload from the same prefs`() {
        val prefs = FakeSharedPreferences()
        scoreboard(prefs).apply {
            recordSuccess("groq:kept", 1_200)
            recordFailure("groq:kept")
        }
        val reloaded = scoreboard(prefs).entry("groq:kept")
        assertEquals(2, reloaded.attempts)
        assertEquals(1, reloaded.successes)
        assertEquals(1, reloaded.failures)
        assertEquals(1_200, reloaded.averageLatencyMs)
    }

    @Test
    fun `an unreadable record is treated as a new model`() {
        val board = scoreboard(FakeSharedPreferences(mapOf("groq:odd" to "not,numbers")))
        assertEquals(ModelScoreboard.Entry(), board.entry("groq:odd"))
        assertFalse(board.isBenched("groq:odd"))
    }

    @Test
    fun `reset forgets a model`() {
        val prefs = FakeSharedPreferences()
        val board = scoreboard(prefs)
        board.recordFailure("groq:gone")
        board.recordFailure("groq:gone")
        board.reset("groq:gone")
        assertEquals(ModelScoreboard.Entry(), board.entry("groq:gone"))
        assertFalse(board.isBenched("groq:gone"))
    }
}
