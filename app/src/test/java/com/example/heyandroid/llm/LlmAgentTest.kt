package com.example.heyandroid.llm

import com.example.heyandroid.tools.Tool
import com.example.heyandroid.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmAgentTest {

    private class ScriptedClient(private val results: List<LlmResult>) : LlmClient {
        override val provider = LlmProvider.GROQ
        val sent = mutableListOf<List<LlmMessage>>()
        private var index = 0

        override suspend fun complete(messages: List<LlmMessage>, maxTokens: Int): LlmResult =
            completeWithTools(messages, emptyList(), maxTokens)

        override suspend fun completeWithTools(
            messages: List<LlmMessage>,
            tools: List<LlmToolSpec>,
            maxTokens: Int
        ): LlmResult {
            sent += messages.toList()
            return results[index++]
        }
    }

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "Echo the arguments back"
        override val parametersJson = "{\"type\":\"object\",\"properties\":{}}"
        val calls = mutableListOf<String>()

        override suspend fun run(argumentsJson: String): String {
            calls += argumentsJson
            return "echoed"
        }
    }

    private class ThrowingTool : Tool {
        override val name = "boom"
        override val description = "Always fails"
        override val parametersJson = "{\"type\":\"object\",\"properties\":{}}"

        override suspend fun run(argumentsJson: String): String =
            throw IllegalStateException("kaboom")
    }

    @Test
    fun `executes requested tools and returns the final answer`() = runBlocking {
        val tool = EchoTool()
        val client = ScriptedClient(
            listOf(
                LlmResult.ToolCalls(listOf(LlmToolCall("call_1", "echo", "{\"a\":1}"))),
                LlmResult.Success("done")
            )
        )
        val agent = LlmAgent(client, ToolRegistry(listOf(tool)))

        val outcome = agent.run(listOf(LlmMessage.user("remember this")))

        assertTrue(outcome.usedTools)
        assertEquals("done", (outcome.result as LlmResult.Success).text)
        assertEquals(listOf("{\"a\":1}"), tool.calls)

        val finalMessages = client.sent.last()
        assertEquals(3, finalMessages.size)
        assertEquals("assistant", finalMessages[1].role)
        assertEquals("echo", finalMessages[1].toolCalls.single().name)
        assertEquals("tool", finalMessages[2].role)
        assertEquals("call_1", finalMessages[2].toolCallId)
        assertEquals("echoed", finalMessages[2].content)
    }

    @Test
    fun `tool failures are reported back to the model`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                LlmResult.ToolCalls(listOf(LlmToolCall("call_1", "boom", "{}"))),
                LlmResult.Success("recovered")
            )
        )
        val agent = LlmAgent(client, ToolRegistry(listOf(ThrowingTool())))

        val outcome = agent.run(listOf(LlmMessage.user("go")))

        assertEquals("recovered", (outcome.result as LlmResult.Success).text)
        val toolMessage = client.sent.last().last()
        assertTrue(toolMessage.content.orEmpty().contains("kaboom"))
    }

    @Test
    fun `stops after the step budget instead of looping forever`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                LlmResult.ToolCalls(listOf(LlmToolCall("call_1", "echo", "{}"))),
                LlmResult.ToolCalls(listOf(LlmToolCall("call_2", "echo", "{}"))),
                // The tool-free synthesis call also fails, so the turn ends as an error.
                LlmResult.Failure("no answer")
            )
        )
        val agent = LlmAgent(client, ToolRegistry(listOf(EchoTool())), maxSteps = 2)

        val outcome = agent.run(listOf(LlmMessage.user("go")))

        assertTrue(outcome.result is LlmResult.Failure)
        assertTrue(outcome.usedTools)
        assertEquals(3, client.sent.size)
    }

    @Test
    fun `answers from the tool results when the budget runs out`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                LlmResult.ToolCalls(listOf(LlmToolCall("call_1", "echo", "{}"))),
                LlmResult.ToolCalls(listOf(LlmToolCall("call_2", "echo", "{}"))),
                LlmResult.Success("final answer")
            )
        )
        val agent = LlmAgent(client, ToolRegistry(listOf(EchoTool())), maxSteps = 2)

        val outcome = agent.run(listOf(LlmMessage.user("go")))

        assertEquals("final answer", (outcome.result as LlmResult.Success).text)
        assertTrue(outcome.usedTools)
        assertEquals(3, client.sent.size)
    }

    @Test
    fun `identical tool calls run once within a turn`() = runBlocking {
        val tool = EchoTool()
        val client = ScriptedClient(
            listOf(
                LlmResult.ToolCalls(
                    listOf(
                        LlmToolCall("call_1", "echo", "{\"a\":1}"),
                        LlmToolCall("call_2", "echo", "{\"a\":1}")
                    )
                ),
                LlmResult.Success("done")
            )
        )
        val agent = LlmAgent(client, ToolRegistry(listOf(tool)))

        val outcome = agent.run(listOf(LlmMessage.user("go")))

        assertEquals(1, tool.calls.size)
        assertEquals(1, outcome.toolRuns.size)
    }

    @Test
    fun `does not flag tools when the model answers directly`() = runBlocking {
        val client = ScriptedClient(listOf(LlmResult.Success("hello")))
        val agent = LlmAgent(client, ToolRegistry(emptyList()))

        val outcome = agent.run(listOf(LlmMessage.user("hi")))

        assertFalse(outcome.usedTools)
        assertEquals("hello", (outcome.result as LlmResult.Success).text)
    }
}
