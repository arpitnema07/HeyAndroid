package com.example.heyandroid.tools

import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.memory.MemoryRepository
import com.example.heyandroid.memory.MemoryText
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Changes one note in place: add an item, rewrite an item, remove an item, or
 * delete the note. A note is a list of lines, so every action works line by line
 * and the rest of the note is left exactly as it was.
 *
 * Merging a new sentence into the whole content was the reason notes went wrong:
 * "add X to my note" replaced or duplicated lines. This tool edits the line the
 * user means.
 */
@Singleton
class EditNoteTool @Inject constructor(
    private val repository: MemoryRepository
) : Tool {

    override val name = "edit_note"

    override val description =
        "Change an existing note line by line: add an item, correct an item, remove " +
            "an item, or delete the whole note. Use it whenever the user wants a saved " +
            "note changed instead of saving a new one."

    override val parametersJson = stringParameters(
        required = mapOf(
            "action" to "\"add\", \"update\", \"remove\" or \"delete_note\"",
            "note" to "Title or subject of the note, or its id as a number"
        ),
        optional = mapOf(
            "item" to "The existing item to change or remove, as it is written",
            "new_item" to "The new wording for \"add\" or \"update\""
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val action = args.optString("action").trim().lowercase().ifBlank { "add" }
        val key = args.optString("note").trim()
        val item = args.optString("item").trim()
        val newItem = args.optString("new_item").trim().ifBlank { item }

        val note = findNote(key) ?: return "No note matches \"$key\". " + existingNotes()
        if (action == "delete_note") {
            repository.delete(note.id)
            return "Deleted the note \"${note.title}\"."
        }

        val lines = note.content.lines().map { it.trim() }.filter { it.isNotBlank() }
            .toMutableList()

        return when (action) {
            "add" -> {
                if (newItem.isBlank()) return "Nothing to add: new_item was empty."
                val entry = if (newItem.startsWith("- ")) newItem else "- $newItem"
                if (lines.any { MemoryText.keyOfLine(it) == MemoryText.keyOfLine(entry) }) {
                    "That item is already in \"${note.title}\"."
                } else {
                    lines += entry
                    save(note, lines, "Added: $newItem")
                    "Added to \"${note.title}\": $newItem"
                }
            }

            "update" -> {
                val index = indexOf(lines, item)
                if (index < 0) return "No item like \"$item\" in \"${note.title}\". " + items(lines)
                lines[index] = if (newItem.startsWith("- ")) newItem else "- $newItem"
                save(note, lines, "Changed: $item → $newItem")
                "Updated \"${note.title}\": $item → $newItem"
            }

            "remove" -> {
                val index = indexOf(lines, item)
                if (index < 0) return "No item like \"$item\" in \"${note.title}\". " + items(lines)
                val removed = lines.removeAt(index)
                save(note, lines, "Removed: $removed")
                "Removed from \"${note.title}\": $removed"
            }

            else -> "Unknown action \"$action\". Use add, update, remove or delete_note."
        }
    }

    private suspend fun save(note: Memory, lines: List<String>, event: String) {
        repository.save(
            note.copy(
                content = MemoryText.capContent(lines.joinToString("\n")),
                updatedAt = System.currentTimeMillis()
            ),
            event
        )
    }

    /** Finds the note the user means by id, subject or title words. */
    private suspend fun findNote(key: String): Memory? {
        val id = key.toLongOrNull()
        if (id != null && id > 0) return repository.byId(id)
        if (key.isBlank()) return null
        val notes = repository.recent(500).filter { it.kind == MemoryKind.NOTE }
        val normalized = MemoryText.normalizeSubject(key)
        return notes.firstOrNull { MemoryText.normalizeSubject(it.subject) == normalized }
            ?: notes.firstOrNull { MemoryText.similarSubjects(normalized, it.subject) }
            ?: notes.firstOrNull { MemoryText.similarSubjects(normalized, it.title) }
            ?: notes.firstOrNull { it.title.contains(key, ignoreCase = true) }
    }

    private fun indexOf(lines: List<String>, item: String): Int {
        if (item.isBlank()) return -1
        val needle = MemoryText.keyOfLine(item)
        val exact = lines.indexOfFirst { MemoryText.keyOfLine(it) == needle }
        if (exact >= 0) return exact
        return lines.indexOfFirst {
            it.contains(item, ignoreCase = true) ||
                MemoryText.keyOfLine(it).contains(needle) && needle.length > 3
        }
    }

    private suspend fun existingNotes(): String {
        val notes = repository.recent(50).filter { it.kind == MemoryKind.NOTE }
        if (notes.isEmpty()) return "No notes are saved yet."
        return "Notes: " + notes.joinToString(", ") { "#${it.id} ${it.title}" }
    }

    private fun items(lines: List<String>): String =
        if (lines.isEmpty()) "The note is empty." else "Items: " + lines.joinToString(" · ")
}
