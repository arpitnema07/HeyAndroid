package com.example.heyandroid.router

/**
 * What a capture should turn into. Pure data so the router stays testable without Android.
 */
sealed interface Route {
    data class CreateNote(val title: String, val body: String) : Route
    data object Unhandled : Route
}

/**
 * Deterministic keyword routing that runs before any LLM is involved.
 * A capture that starts with a known prefix becomes a note immediately; everything
 * else is left as a plain capture for later processing.
 */
object KeywordRouter {

    private val prefixes = listOf("note:", "new note", "note ", "remember ")

    private const val MAX_TITLE_LENGTH = 80

    fun route(input: String): Route {
        val text = input.trim()
        if (text.isEmpty()) return Route.Unhandled

        val lower = text.lowercase()
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return Route.Unhandled

        val rest = text.substring(prefix.length).trimStart(' ', ':', '-', ',')
        if (rest.isBlank()) return Route.Unhandled

        val lines = rest.lines()
        val title = lines.first().trim().let {
            if (it.length > MAX_TITLE_LENGTH) it.take(MAX_TITLE_LENGTH).trimEnd() else it
        }
        val body = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else ""

        return Route.CreateNote(title = title, body = body)
    }
}
