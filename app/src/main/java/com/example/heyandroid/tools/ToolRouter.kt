package com.example.heyandroid.tools

import com.example.heyandroid.llm.LlmToolSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the tools advertised with a request: the memory tools and web search
 * are always included, then whatever the user's words point at (they often name
 * the action: "remember", "add to my watchlist", "what am I watching"), then the
 * rest of the catalogue in registry order, capped at [MAX_TOOLS] so the request
 * stays small as custom tools pile up.
 */
@Singleton
class ToolRouter @Inject constructor(private val registry: ToolRegistry) {

    fun select(text: String): List<LlmToolSpec> {
        val lower = text.lowercase()
        val ranked = linkedSetOf<String>().apply { addAll(CORE) }

        triggers.forEach { (tool, phrases) ->
            if (phrases.any { lower.contains(it) }) ranked += tool
        }
        registry.tools.forEach { if (lower.contains(it.name)) ranked += it.name }

        // Nothing is left out just because a keyword list missed it.
        registry.tools.forEach { ranked += it.name }

        val byName = registry.specs().associateBy { it.name }
        return ranked.mapNotNull { byName[it] }.take(MAX_TOOLS)
    }

    fun all(): List<LlmToolSpec> = registry.specs()

    private companion object {
        const val MAX_TOOLS = 16

        /** Always sent: the app's core job, plus the model's escape hatch when unsure. */
        val CORE = listOf(
            "save_memory",
            "search_memory",
            "list_memories",
            "update_memory",
            "web_search"
        )

        val triggers = mapOf(
            "save_memory" to listOf(
                "remember", "save", "store", "keep this", "memorise", "memorize",
                "don't forget", "dont forget", "add to", "for later"
            ),
            "search_memory" to listOf(
                "search", "find", "look up", "recall", "where am i", "where was i",
                "what did i", "when did i", "do i have", "what am i", "what do i",
                "how far", "which episode", "my list", "watchlist", "reading list"
            ),
            "list_memories" to listOf(
                "list", "show me", "everything", "all my", "what have i", "reminders",
                "coming up", "due"
            ),
            "update_memory" to listOf(
                "update", "mark", "completed", "finished", "started", "watching",
                "reading", "listening", "progress", "done with", "move to", "archive"
            ),
            "web_search" to listOf(
                "web search", "search the web", "search online", "look online", "google",
                "online", "latest", "news", "current", "today's", "todays", "price of",
                "how much is", "who won", "weather", "stock", "release date", "score"
            ),
            "syncpad_add_text" to listOf(
                "syncpad", "clipboard", "clip this", "snippet", "copy this to", "send this to sync"
            ),
            "syncpad_add_screenshot" to listOf(
                "syncpad", "screenshot", "screen shot", "send this screenshot"
            ),
            "syncpad_add_image" to listOf(
                "syncpad", "image", "picture", "photo", "screenshot"
            ),
            "call_custom_api" to listOf("api", "endpoint", "webhook", "custom tool", "call my"),
            "edit_note" to listOf(
                "note", "notes", "add to my note", "remove from my note", "strike"
            ),
            "start_call" to listOf("call ", "call my", "phone", "dial", "ring up"),
            "search_contacts" to listOf(
                "contact", "contacts", "phone number", "number of", "number for"
            ),
            "add_calendar_event" to listOf(
                "calendar", "event", "meeting", "appointment", "schedule"
            ),
            "set_alarm" to listOf(
                "alarm", "wake me", "timer", "clock", "countdown", "remind",
                "reminder", "stopwatch", "stop watch"
            ),
            "mcp_call" to listOf(
                "mcp", "gmail", "google drive", "google docs", "sheets", "workspace",
                "my email", "my mail"
            )
        )
    }
}
