package com.example.heyandroid.tools

import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.data.memory.MemorySource
import com.example.heyandroid.data.memory.MemoryStatus
import com.example.heyandroid.memory.MemoryFormatter
import com.example.heyandroid.memory.MemoryRepository
import com.example.heyandroid.memory.MemoryText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Writes one memory the model already decided on. The extractor agent runs before
 * the chat most of the time, so this is the path for "remember X" phrased as a
 * tool call, and for anything the model wants to keep mid-conversation.
 *
 * Content is merged, never blindly replaced: a list keeps its old items.
 */
@Singleton
class SaveMemoryTool @Inject constructor(
    private val repository: MemoryRepository
) : Tool {

    override val name = "save_memory"

    override val description =
        "Save something to the user's long-term memory on this device: a fact, task, " +
            "list item, progress in something they are watching, reading or playing, or " +
            "a transaction. Reuse the subject of an existing memory to update it instead " +
            "of creating a duplicate. Never use it for a request to be reminded: call " +
            "set_alarm for that."

    override val parametersJson = stringParameters(
        required = mapOf("title" to "Short label, under 60 characters"),
        optional = mapOf(
            "content" to "Details worth keeping; for a list, the complete list",
            "kind" to "FACT, TASK, ITEM, PROGRESS, NOTE or TRANSACTION",
            "subject" to "Stable lowercase key, e.g. \"breaking bad\"",
            "collection" to "watching, reading, listening, shopping or ideas",
            "progress" to "Current position, e.g. S1E2 or page 120",
            "dueAt" to "Required for kind=TASK: local date and time, YYYY-MM-DDTHH:mm. " +
                "Never set it for FACT, NOTE, ITEM, PROGRESS or TRANSACTION."
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"

        val title = args.optString("title").trim()
        if (title.isBlank()) return "Memory not saved: title is required"

        val kind = MemoryKind.entries
            .firstOrNull { it.name.equals(args.optString("kind").trim(), true) }
            ?: MemoryKind.FACT
        val subject = MemoryText.normalizeSubject(
            args.optString("subject").trim().ifBlank { title }
        )

        val existing = repository.find(subject, kind) ?: repository.findSimilar(subject, kind)
        val now = System.currentTimeMillis()
        val incomingContent = args.optString("content").trim()

        val memory = (existing ?: Memory(title = title, createdAt = now)).copy(
            kind = if (existing != null && existing.kind != MemoryKind.FACT) existing.kind else kind,
            subject = subject,
            title = title.take(120),
            content = if (incomingContent.isBlank()) {
                existing?.content.orEmpty()
            } else {
                MemoryText.mergeContent(existing?.content.orEmpty(), incomingContent)
            },
            collection = args.optString("collection").trim()
                .ifBlank { existing?.collection.orEmpty() },
            progress = args.optString("progress").trim()
                .ifBlank { existing?.progress.orEmpty() },
            dueAt = if (kind.needsDue) {
                parseDue(args.optString("dueAt").trim()) ?: existing?.dueAt
            } else {
                null
            },
            source = MemorySource.VOICE,
            updatedAt = now
        )

        repository.save(
            memory,
            when {
                existing == null -> "Saved"
                memory.progress.isNotBlank() && memory.progress != existing.progress ->
                    "At ${memory.progress}"
                else -> "Updated"
            }
        )
        return if (existing == null) {
            "Saved memory #${memory.id}: $title"
        } else {
            "Updated memory #${memory.id}: $title"
        }
    }

    private fun parseDue(value: String): Long? {
        if (value.isBlank() || value.equals("null", true)) return null
        value.toLongOrNull()?.let { if (it > 1_000_000_000_000L) return it }
        val zone = ZoneId.systemDefault()
        val iso = value.replace(' ', 'T')
        runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        runCatching { LocalDate.parse(iso) }.getOrNull()
            ?.let { return it.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli() }
        runCatching { OffsetDateTime.parse(value) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }
        return null
    }
}

/** Finds stored memories by meaning, not just exact words. */
@Singleton
class SearchMemoryTool @Inject constructor(
    private val repository: MemoryRepository
) : Tool {

    override val name = "search_memory"

    override val description =
        "Search the user's saved memories, including what they are watching, reading " +
            "or listening to, their tasks and their notes. Returns each match with " +
            "its id and recent timeline. Use it before answering any question about the " +
            "user's own life, plans or progress."

    override val parametersJson = stringParameters(
        required = mapOf("query" to "What to look for, in the user's own words"),
        optional = mapOf("limit" to "How many memories to return, 1 to 20 (default 6)")
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val query = args.optString("query").trim()
        if (query.isBlank()) return "Search not run: query is required"

        val limit = args.optInt("limit", 6).coerceIn(1, 20)
        val cards = repository.search(query, limit)
        if (cards.isEmpty()) return "Nothing in memory matches \"$query\"."
        return cards.joinToString(separator = "\n") { MemoryFormatter.context(it) }
    }
}

/** Everything stored, newest first, for "what have I saved" questions. */
@Singleton
class ListMemoriesTool @Inject constructor(
    private val repository: MemoryRepository
) : Tool {

    override val name = "list_memories"

    override val description =
        "List the user's most recently updated memories. Use it when they ask what " +
            "they have saved, what is on a list, or what is coming up."

    override val parametersJson = stringParameters(
        optional = mapOf(
            "kind" to "Optional filter: FACT, TASK, ITEM, PROGRESS, NOTE or TRANSACTION",
            "limit" to "How many memories to return, 1 to 40 (default 12)"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
        val limit = args?.optInt("limit", 12)?.coerceIn(1, 40) ?: 12
        val kind = args?.optString("kind")?.trim()?.uppercase(java.util.Locale.ROOT).orEmpty()

        val memories = repository.recent(limit * 3)
            .filter { kind.isBlank() || it.kind.name == kind }
            .take(limit)
        if (memories.isEmpty()) return "Nothing is saved in memory yet."
        return memories.joinToString(separator = "\n") { MemoryFormatter.line(it) }
    }
}

/** Moves a memory forward: new position, new details, or mark it done. */
@Singleton
class UpdateMemoryTool @Inject constructor(
    private val repository: MemoryRepository
) : Tool {

    override val name = "update_memory"

    override val description =
        "Update an existing memory: change its progress (episode, chapter, page), its " +
            "details, or mark it done. Give the subject when you know it, or an id from " +
            "search_memory. If the id does not exist the real ids are returned."

    override val parametersJson = stringParameters(
        optional = mapOf(
            "id" to "Numeric memory id, from search_memory",
            "subject" to "Stable key of the memory, if the id is unknown",
            "progress" to "New position, e.g. S1E3",
            "content" to "New details; existing list items are kept",
            "status" to "ACTIVE, DONE or ARCHIVED",
            "event" to "Short timeline note, e.g. \"Finished S1E2\""
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val id = args.optLong("id", 0L)
        val subject = MemoryText.normalizeSubject(args.optString("subject").trim())

        val memory = (if (id > 0) repository.byId(id) else null)
            ?: subject.takeIf { it.isNotBlank() }?.let { key ->
                repository.recent(500).firstOrNull {
                    MemoryText.normalizeSubject(it.subject) == key ||
                        MemoryText.similarSubjects(key, it.subject)
                }
            }
            ?: return missing(id, subject)

        val status = MemoryStatus.entries
            .firstOrNull { it.name.equals(args.optString("status").trim(), true) }
        val progress = args.optString("progress").trim()
        val incoming = args.optString("content").trim()

        val updated = memory.copy(
            progress = progress.ifBlank { memory.progress },
            content = if (incoming.isBlank()) {
                memory.content
            } else {
                MemoryText.mergeContent(memory.content, incoming)
            },
            status = status ?: memory.status,
            updatedAt = System.currentTimeMillis()
        )
        repository.save(updated, args.optString("event").trim().ifBlank { "Updated" })
        return "Updated memory #${updated.id}: ${updated.title}" +
            updated.progress.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }

    /** Gives the model the ids that do exist, so its next call can succeed. */
    private suspend fun missing(id: Long, subject: String): String {
        val ids = repository.recent(12).joinToString(", ") { "#${it.id} ${it.title.take(28)}" }
        val asked = when {
            id > 0 -> "No memory with id $id."
            subject.isNotBlank() -> "No memory matches \"$subject\"."
            else -> "No id or subject was given."
        }
        return if (ids.isBlank()) {
            "$asked Nothing is saved in memory yet."
        } else {
            "$asked Existing memories: $ids. Use one of these ids or the exact subject."
        }
    }
}
