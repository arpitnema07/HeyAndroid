package com.example.heyandroid.memory

/**
 * Cheap, deterministic routing before any model call: is this message something
 * to remember, or a question about what is already remembered?
 *
 * The app does not rely on this alone - the chat agent can also call memory
 * tools - but it makes statements work even when a model ignores tools and
 * keeps the common "save this" turn to a single request.
 */
object MemoryIntent {

    private val recordPhrases = listOf(
        "remember", "don't forget", "dont forget", "do not forget", "note that", "note down",
        "save this", "save that", "save it", "remind me", "add to my", "add this to",
        "i am watching", "i'm watching", "im watching", "i am reading", "i'm reading",
        "im reading", "i am listening", "i'm listening", "i watched", "i read",
        "i finished", "i completed", "i started", "just finished", "just started",
        "completed episode", "finished episode", "mark ", "log that", "log this",
        "my favourite", "my favorite", "i prefer", "i like ", "i bought", "i need to",
        "i have to", "for later", "bookmark", "i spent", "i paid", "i earned",
        "i received", "expense", "salary", "refund", "transaction"
    )

    private val queryStarts = listOf(
        "what", "where", "when", "who", "why", "how", "which", "did", "do", "does",
        "is", "are", "was", "were", "can", "could", "should", "will", "would",
        "any", "tell me", "show me", "find", "search", "list"
    )

    fun isQuestion(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.endsWith("?")) return true
        val lower = trimmed.lowercase()
        return queryStarts.any { lower == it || lower.startsWith("$it ") }
    }

    fun shouldRecord(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (isQuestion(trimmed)) return false
        val lower = trimmed.lowercase()
        return recordPhrases.any { lower.contains(it) }
    }

    fun shouldRetrieve(text: String): Boolean = isQuestion(text)
}
