package com.example.heyandroid.memory

/**
 * Text rules shared by the memory agent and the repository.
 *
 * Content merging is deliberately conservative: a list the user dictated must
 * never lose earlier items because a later update "replaces" it. New lines are
 * appended, duplicates are skipped, and only a hard cap truncates anything - with
 * an explicit marker so a cut list can never look complete.
 */
object MemoryText {

    const val MAX_CONTENT = 4000
    const val TRUNCATED_MARKER = "\n… (truncated)"

    private val SPACES = Regex("\\s+")
    private val LIST_PREFIX = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+")

    fun normalizeSubject(value: String): String =
        value.lowercase().replace(SPACES, " ").trim().take(60)

    /** Strips plurals so "lantern" and "lanterns" become the same key token. */
    fun stem(word: String): String =
        if (word.length > 4 && word.endsWith("s") && !word.endsWith("ss")) {
            word.dropLast(1)
        } else {
            word
        }

    fun tokens(value: String): Set<String> =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .split(SPACES)
            .filter { it.isNotBlank() && it !in STOP_WORDS }
            .map { stem(it) }
            .toSet()

    /** True when two subjects describe the same thing ("lantern" / "lanterns"). */
    fun similarSubjects(a: String, b: String): Boolean {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (normalizeSubject(a) == normalizeSubject(b)) return true
        val overlap = left.intersect(right).size
        val smallest = minOf(left.size, right.size)
        return overlap > 0 && overlap.toFloat() / smallest >= 0.6f
    }

    /** True when an id's memory actually matches the subject the model named. */
    fun matchesMemory(subject: String, title: String, existingSubject: String): Boolean {
        if (existingSubject.isBlank()) return false
        return similarSubjects(subject.ifBlank { title }, existingSubject)
    }

    fun looksLikeList(text: String): Boolean =
        text.lineSequence().count { LIST_PREFIX.containsMatchIn(it) || it.trimStart().startsWith("- ") } >= 2

    fun capContent(text: String): String {
        if (text.length <= MAX_CONTENT) return text
        val room = MAX_CONTENT - TRUNCATED_MARKER.length
        return text.take(room).trimEnd() + TRUNCATED_MARKER
    }

    /**
     * Merges an update into existing content.
     *
     * - nothing new: keep what is there;
     * - the incoming text already covers the old one: take it;
     * - either side is a list: union the lines, keeping the old order;
     * - otherwise: replace, because a prose detail was rewritten.
     */
    fun mergeContent(existing: String, incoming: String): String {
        val old = existing.trim()
        val new = incoming.trim()
        if (new.isBlank()) return old
        if (old.isBlank()) return capContent(new)
        if (old == new) return old
        if (old.contains(new)) return old
        if (new.contains(old)) return capContent(new)

        if (looksLikeList(old) || looksLikeList(new)) {
            val seen = linkedSetOf<String>()
            val merged = mutableListOf<String>()
            (old.lines() + new.lines()).forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val key = keyOfLine(trimmed)
                if (seen.add(key)) merged += trimmed
            }
            return capContent(merged.joinToString("\n"))
        }

        return capContent(new)
    }

    /** Both content and image-bearing updates keep the old value when blank. */
    fun keyOfLine(line: String): String =
        stem(line.removePrefix("- ").removePrefix("* ").trim().lowercase())

    fun shortTitle(text: String, limit: Int = 42): String {
        val clean = text.replace(SPACES, " ").trim()
        if (clean.length <= limit) return clean
        val cut = clean.take(limit)
        val space = cut.lastIndexOf(' ')
        return (if (space > limit / 2) cut.take(space) else cut).trim() + "…"
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "my", "is",
        "it", "this", "that", "with", "at", "from"
    )
}
