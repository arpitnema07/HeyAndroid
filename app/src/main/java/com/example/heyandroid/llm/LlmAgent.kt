package com.example.heyandroid.llm

import com.example.heyandroid.tools.ToolRegistry

/**
 * What one tool did, in the order it ran. Shown in the chat as a collapsed
 * activity block, with the arguments so the user can see which memory was used.
 */
data class ToolRun(
    val name: String,
    val argumentsJson: String,
    val result: String
)

/**
 * The model, its text reply, whether it used any device tool, and what those tools did.
 */
data class AgentOutcome(
    val result: LlmResult,
    val usedTools: Boolean,
    val toolRuns: List<ToolRun> = emptyList(),
    val steps: Int = 0
)

/**
 * Runs one conversation with tool calling: ask the model, execute whatever tools
 * it requests, feed the results back, and stop when it answers or the step budget
 * runs out. Bounded so a misbehaving model cannot loop forever.
 *
 * On exhaustion the agent makes one last tool-free call asking for a final answer
 * from everything the tools already returned, so a long tool chain still ends in
 * a reply instead of "stopped after N steps".
 */
class LlmAgent(
    private val client: LlmClient,
    private val tools: ToolRegistry,
    private val maxSteps: Int = 8
) {
    suspend fun run(
        messages: List<LlmMessage>,
        maxTokens: Int = 512,
        /** Only the tools the current message seems to need; see ToolRouter. */
        toolSpecs: List<LlmToolSpec> = tools.specs()
    ): AgentOutcome {
        val conversation = messages.toMutableList()
        val toolRuns = mutableListOf<ToolRun>()
        val seen = HashMap<String, String>()
        var steps = 0

        repeat(maxSteps) {
            steps++
            when (val result = client.completeWithTools(conversation, toolSpecs, maxTokens)) {
                is LlmResult.Success -> return AgentOutcome(result, toolRuns.isNotEmpty(), toolRuns, steps)
                is LlmResult.Failure -> return AgentOutcome(result, toolRuns.isNotEmpty(), toolRuns, steps)
                is LlmResult.ToolCalls -> {
                    conversation += LlmMessage.assistantToolCalls(result.calls)
                    result.calls.forEach { call ->
                        val key = "${call.name}:${call.argumentsJson}"
                        val cached = seen[key]
                        val output = cached ?: tools.execute(call)
                        if (cached == null) {
                            seen[key] = output
                            toolRuns += ToolRun(call.name, call.argumentsJson, output)
                        }
                        conversation += LlmMessage.toolResult(call.id, output)
                    }
                }
            }
        }

        // The budget ran out mid-tool-chain. One tool-free completion over the
        // collected results still gives the user an answer.
        if (toolRuns.isNotEmpty()) {
            conversation += LlmMessage.system(
                "Tool budget reached. Using the tool results above, answer the user's " +
                    "request now in a short reply. Do not ask for more tools."
            )
            val finalResult = client.complete(conversation, maxTokens)
            if (finalResult is LlmResult.Success) {
                return AgentOutcome(finalResult, usedTools = true, toolRuns = toolRuns, steps = steps + 1)
            }
        }

        return AgentOutcome(
            result = LlmResult.Failure("Stopped after $steps steps without a final answer"),
            usedTools = toolRuns.isNotEmpty(),
            toolRuns = toolRuns,
            steps = steps
        )
    }
}
