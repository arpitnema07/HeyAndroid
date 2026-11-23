package com.example.heyandroid.tools

import com.example.heyandroid.llm.LlmToolCall
import com.example.heyandroid.llm.LlmToolSpec

/**
 * One thing the assistant can do on the device. Tools are plain objects so the
 * registry stays testable without Android; [parametersJson] is a JSON-schema
 * object string advertised to the model.
 */
interface Tool {
    val name: String
    val description: String
    val parametersJson: String

    /** Runs the tool and returns a short result to send back to the model. */
    suspend fun run(argumentsJson: String): String
}

/**
 * The set of tools offered to the model. Unknown names and tool failures become
 * ordinary result text so the model can recover instead of the app crashing.
 */
class ToolRegistry(tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }
    val tools: List<Tool> = tools

    init {
        require(byName.size == tools.size) { "Duplicate tool names are not allowed" }
    }

    fun specs(): List<LlmToolSpec> = tools.map {
        LlmToolSpec(
            name = it.name,
            description = it.description,
            parametersJson = it.parametersJson
        )
    }

    /** Specs for a subset of tools, in registry order. Unknown names are skipped. */
    fun specsFor(names: Collection<String>): List<LlmToolSpec> {
        val wanted = names.toSet()
        return specs().filter { it.name in wanted }
    }

    suspend fun execute(call: LlmToolCall): String {
        val tool = byName[call.name] ?: return "No tool named ${call.name}"
        return runCatching { tool.run(call.argumentsJson) }
            .getOrElse { "Tool ${call.name} failed: ${it.message ?: it.javaClass.simpleName}" }
    }
}
