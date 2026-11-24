package com.example.heyandroid.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackLlmClientTest {

    private val scoreboard = ModelScoreboard(FakeSharedPreferences())

    private fun target(model: String) =
        ModelTarget(
            provider = LlmProvider.GROQ,
            apiKey = "test-key",
            baseUrl = LlmProvider.GROQ.baseUrl,
            model = model
        )

    private fun client(vararg models: String) =
        FallbackLlmClient(targets = models.map(::target), scoreboard = scoreboard)

    private fun models(client: FallbackLlmClient) = client.ladder.map { it.model }

    @Test
    fun `unrated models keep the order the app built`() {
        assertEquals(listOf("saved", "alt", "other"), models(client("saved", "alt", "other")))
    }

    @Test
    fun `the saved model is tried first when the record is even`() {
        scoreboard.recordSuccess("groq:alt", 800)
        scoreboard.recordSuccess("groq:other", 800)
        // Both alternates now score above the unrated saved model.
        assertEquals(listOf("alt", "other", "saved"), models(client("saved", "alt", "other")))
    }

    @Test
    fun `a model that failed twice moves to the back`() {
        val client = client("saved", "alt", "other")
        scoreboard.recordFailure("groq:saved")
        scoreboard.recordFailure("groq:saved")
        assertEquals(listOf("alt", "other", "saved"), models(client))
    }

    @Test
    fun `a benched model stays in the ladder`() {
        val client = client("saved", "alt", "other")
        repeat(4) { scoreboard.recordFailure("groq:saved") }
        assertEquals(3, client.ladder.size)
        assertEquals("saved", models(client).last())
    }

    @Test
    fun `a failure puts a model behind a proven one`() {
        val client = client("saved", "alt", "other")
        scoreboard.recordSuccess("groq:alt", 900)
        scoreboard.recordFailure("groq:other")
        assertEquals(listOf("alt", "saved", "other"), models(client))
    }

    @Test
    fun `a slow proven model still beats an unrated one`() {
        val client = client("saved", "alt", "other")
        scoreboard.recordSuccess("groq:other", 12_000)
        assertEquals(listOf("other", "saved", "alt"), models(client))
    }

    @Test
    fun `a recovered model leaves the bench`() {
        val client = client("saved", "alt")
        scoreboard.recordFailure("groq:saved")
        scoreboard.recordFailure("groq:saved")
        assertEquals(listOf("alt", "saved"), models(client))
        scoreboard.recordSuccess("groq:saved", 700)
        scoreboard.recordSuccess("groq:saved", 700)
        assertEquals(listOf("saved", "alt"), models(client))
    }
}
