package com.example.heyandroid.memory

import android.content.Context
import android.util.Base64
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.data.memory.MemorySource
import com.example.heyandroid.data.memory.MemoryStatus
import com.example.heyandroid.data.memory.attachedImages
import com.example.heyandroid.data.memory.encodeImages
import com.example.heyandroid.llm.LlmClient
import com.example.heyandroid.llm.LlmClientFactory
import com.example.heyandroid.llm.LlmMessage
import com.example.heyandroid.llm.LlmResult
import com.example.heyandroid.llm.LlmSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** What one ingest changed, in short lines the chat can show back to the user. */
data class IngestResult(
    val saved: List<String> = emptyList(),
    val changed: Int = 0,
    val usedModel: Boolean = false
) {
    val isEmpty: Boolean get() = changed == 0
}

/**
 * The memory agent: reads one message (and a photo or screenshot), decides what
 * is worth keeping, and writes it as structured rows with vectors and a timeline.
 *
 * It is deliberately separate from the chat agent. Extraction is a narrow,
 * JSON-only task; answering is a conversational one with tools. Keeping them
 * apart means a chatty reply never corrupts the store, and extraction still
 * works when the reply fails.
 */
@Singleton
class MemoryEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MemoryRepository,
    private val settings: LlmSettingsStore
) {

    /**
     * Turns a statement or a screenshot into memories. Without an API key the raw
     * text is still stored as a note, so nothing the user said is lost.
     */
    suspend fun ingest(
        text: String,
        imageDataUrl: String? = null,
        source: MemorySource = MemorySource.VOICE
    ): IngestResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageDataUrl == null) return IngestResult()

        val image = imageDataUrl?.let { saveImage(it) }

        val client = client(vision = imageDataUrl != null)
            ?: return storeRaw(trimmed, source, image)

        val candidates = repository.recent(CANDIDATE_LIMIT)
        val prompt = buildString {
            append("Current date and time: ").append(MemoryFormatter.now()).append('\n')
            if (candidates.isEmpty()) {
                append("The memory store is empty.\n")
            } else {
                append("Existing memories (reuse these ids to update instead of duplicating):\n")
                candidates.forEach { memory ->
                    append(MemoryFormatter.line(memory)).append('\n')
                }
            }
            append('\n').append("New input from the user")
            if (imageDataUrl != null) append(" (a photo/screenshot is attached; read it)")
            append(":\n").append(trimmed.ifEmpty { "Save what is in the attached image." })

            val cards = if (trimmed.isNotBlank()) repository.search(trimmed, 4) else emptyList()
            if (cards.isNotEmpty()) {
                append("\n\nPossibly related memories:\n")
                cards.take(4).forEach { card ->
                    append(MemoryFormatter.context(card)).append('\n')
                }
            }
        }

        val result = client.complete(
            messages = listOf(
                LlmMessage.system(EXTRACT_PROMPT),
                LlmMessage.user(prompt, images = listOfNotNull(imageDataUrl))
            ),
            maxTokens = 1200
        )

        val raw = when (result) {
            is LlmResult.Success -> result.text
            else -> return storeRaw(trimmed, source, image)
        }

        val actions = parseActions(raw)
        if (actions.isEmpty()) {
            // A deliberate "nothing to save" and a malformed reply look the same.
            // Storing an empty note would be noise, so keep it only if it was not JSON.
            return if (raw.contains("{")) IngestResult(usedModel = true) else storeRaw(trimmed, source, image)
        }

        val saved = mutableListOf<String>()
        actions.forEach { action ->
            val written = applyAction(action, trimmed, source, image) ?: return@forEach
            saved += written
        }
        return IngestResult(saved = saved, changed = saved.size, usedModel = true)
    }

    /** Retrieval context for a question; empty when nothing relevant is stored. */
    suspend fun recall(query: String, limit: Int = 6): String {
        if (query.isBlank()) return ""
        val cards = repository.search(query, limit)
        if (cards.isEmpty()) return ""
        return cards.joinToString(separator = "\n") { MemoryFormatter.context(it) }
    }

    suspend fun search(query: String, limit: Int = 20) = repository.search(query, limit)

    private suspend fun applyAction(
        action: Action,
        originalText: String,
        source: MemorySource,
        image: SavedImage?
    ): String? {
        val kind = action.kind
        val subject = MemoryText.normalizeSubject(action.subject.ifBlank { action.title })

        val existing = resolveExisting(action, subject)

        if (action.isArchive) {
            val target = existing ?: return null
            val updated = target.copy(
                status = MemoryStatus.ARCHIVED,
                updatedAt = System.currentTimeMillis()
            )
            repository.save(updated, action.event ?: "Archived")
            return ack(updated)
        }

        if (existing != null) {
            val now = System.currentTimeMillis()
            val mergedContent = when {
                action.content == null -> existing.content
                else -> MemoryText.mergeContent(existing.content, action.content)
            }
            // A positional update should not turn a PROGRESS row into a FACT just
            // because the model left the kind out.
            val effectiveKind =
                if (action.kind == MemoryKind.FACT && existing.kind != MemoryKind.FACT) {
                    existing.kind
                } else {
                    action.kind
                }
            val due = if (!effectiveKind.needsDue) {
                null
            } else {
                action.dueAt
                    ?: existing.dueAt.takeIf { existing.kind.needsDue }
                    ?: inferDue(originalText)
            }
            val updated = existing.copy(
                kind = effectiveKind,
                subject = subject.ifBlank { existing.subject },
                title = action.title.ifBlank { existing.title },
                content = mergedContent,
                collection = action.collection ?: existing.collection,
                progress = action.progress ?: existing.progress,
                status = action.status ?: existing.status,
                dueAt = due,
                notifiedAt = if (due != existing.dueAt) null else existing.notifiedAt,
                imagePath = image?.path ?: existing.imagePath,
                imageName = image?.name ?: existing.imageName,
                images = mergeImages(existing.attachedImages, image),
                source = source,
                updatedAt = now
            )
            val event = action.event ?: describeUpdate(existing, updated)
            repository.save(updated, event)
            return ack(updated)
        }

        val now = System.currentTimeMillis()
        val due = if (kind.needsDue) {
            action.dueAt ?: inferDue(originalText)
        } else {
            null
        }
        val created = Memory(
            kind = kind,
            subject = subject,
            title = action.title.ifBlank { MemoryText.shortTitle(originalText).ifBlank { "Memory" } }.take(120),
            content = action.content.orEmpty().let { MemoryText.capContent(it) },
            collection = action.collection.orEmpty(),
            progress = action.progress.orEmpty(),
            status = action.status ?: MemoryStatus.ACTIVE,
            dueAt = due,
            source = source,
            imagePath = image?.path,
            imageName = image?.name,
            images = encodeImages(listOfNotNull(image?.path)),
            createdAt = now,
            updatedAt = now
        )
        val event = action.event ?: when {
            action.progress != null -> "Set to ${action.progress}"
            due != null -> "Due ${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(due))}"
            else -> "Saved"
        }
        repository.save(created, event)
        return ack(created)
    }

    /**
     * Resolves which row an action targets. A model-invented id must never win:
     * the id is only trusted when its subject actually matches what the action
     * names. Otherwise the subject is matched exactly, then fuzzily.
     */
    private suspend fun resolveExisting(action: Action, subject: String): Memory? {
        val label = action.subject.ifBlank { action.title }

        if (action.id != null && action.id > 0) {
            val byId = repository.byId(action.id)
            if (byId != null) {
                val trusted = label.isBlank() ||
                    MemoryText.matchesMemory(label, action.title, byId.subject) ||
                    MemoryText.tokens(label).intersect(MemoryText.tokens(byId.subject)).isNotEmpty()
                if (trusted) return byId
            }
        }

        if (subject.isNotBlank()) {
            repository.find(subject, action.kind)?.let { return it }
            repository.findSimilar(subject, action.kind)?.let { return it }
        }
        return null
    }

    private fun describeUpdate(before: Memory, after: Memory): String = when {
        before.progress != after.progress && after.progress.isNotBlank() ->
            "At ${after.progress}"
        before.status != MemoryStatus.DONE && after.status == MemoryStatus.DONE -> "Completed"
        after.content.length > before.content.length -> "Added details"
        else -> "Updated"
    }

    private suspend fun storeRaw(
        text: String,
        source: MemorySource,
        image: SavedImage?
    ): IngestResult {
        if (text.isBlank() && image == null) return IngestResult()
        val now = System.currentTimeMillis()
        val memory = Memory(
            kind = MemoryKind.NOTE,
            subject = MemoryText.normalizeSubject(text.take(48)),
            title = text.lineSequence().first().take(90).ifBlank { "Screenshot" },
            content = text,
            source = source,
            imagePath = image?.path,
            imageName = image?.name,
            images = encodeImages(listOfNotNull(image?.path)),
            createdAt = now,
            updatedAt = now
        )
        repository.save(memory, "Captured")
        return IngestResult(listOf(ack(memory)), changed = 1, usedModel = false)
    }

    /**
     * Keeps every image a memory already had and appends the new one, newest last.
     * The list is capped so a long-lived row cannot grow without bound.
     */
    private fun mergeImages(existing: List<String>, image: SavedImage?): String {
        val paths = existing.toMutableList()
        image?.path?.let { if (it !in paths) paths += it }
        return encodeImages(paths.takeLast(MAX_IMAGES))
    }

    /** Copies the attached image into app-private storage and names it uniquely. */
    private fun saveImage(dataUrl: String): SavedImage? = runCatching {
        val comma = dataUrl.indexOf(',')
        if (comma <= 0) return@runCatching null
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        if (bytes.isEmpty()) return@runCatching null
        val dir = File(context.filesDir, "memories").apply { mkdirs() }
        val name = "memory_${System.currentTimeMillis()}_${(0..999).random()}.jpg"
        val file = File(dir, name)
        file.writeBytes(bytes)
        SavedImage(path = file.absolutePath, name = name)
    }.getOrNull()

    private fun client(vision: Boolean): LlmClient? {
        val config = settings.config.value
        if (!config.isConfigured) return null
        val model = if (vision) config.provider.visionModel ?: return null else config.model
        return LlmClientFactory.create(config.provider, config.apiKey, model, config.baseUrl)
    }

    private fun parseActions(raw: String): List<Action> {
        val json = extractJson(raw) ?: return emptyList()
        val array = runCatching { JSONObject(json).optJSONArray("memories") }.getOrNull()
            ?: runCatching { JSONArray(json) }.getOrNull()
            ?: return emptyList()

        val actions = mutableListOf<Action>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            val subject = item.optString("subject").trim()
            val content = item.optString("content").trim()
            if (title.isBlank() && subject.isBlank() && content.isBlank()) continue

            val kind = parseKind(item.optString("kind"))
            actions += Action(
                isArchive = item.optString("action").equals("archive", true),
                id = item.optLong("id", 0L).takeIf { it > 0 },
                kind = kind,
                subject = subject,
                title = title,
                content = content.takeIf { it.isNotBlank() },
                collection = item.optString("collection").trim().takeIf { it.isNotBlank() },
                progress = item.optString("progress").trim().takeIf { it.isNotBlank() },
                status = parseStatus(item.optString("status")),
                // Only tasks and reminders have a time. Anything else that happens
                // to mention "today" or "Sunday" must not gain a due date.
                dueAt = if (kind.needsDue) {
                    parseDue(item.optString("dueAt")) ?: inferDue(
                        item.optString("dueAt").ifBlank { subject.ifBlank { title } }
                    )
                } else {
                    null
                },
                event = item.optString("event").trim().takeIf { it.isNotBlank() }
            )
        }
        return actions
    }

    private fun extractJson(raw: String): String? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    private fun parseKind(value: String): MemoryKind =
        MemoryKind.entries.firstOrNull { it.name.equals(value.trim(), true) } ?: MemoryKind.FACT

    private fun parseStatus(value: String): MemoryStatus? {
        val cleaned = value.trim()
        if (cleaned.isEmpty() || cleaned.equals("null", true)) return null
        if (cleaned.equals("completed", true)) return MemoryStatus.DONE
        return MemoryStatus.entries.firstOrNull { it.name.equals(cleaned, true) }
    }

    private fun parseDue(value: String): Long? {
        val cleaned = value.trim()
        if (cleaned.isEmpty() || cleaned.equals("null", true)) return null
        val zone = ZoneId.systemDefault()

        cleaned.toLongOrNull()?.let { if (it > 1_000_000_000_000L) return it }

        val iso = cleaned.replace(' ', 'T')
        runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        runCatching { LocalDate.parse(iso) }.getOrNull()
            ?.let { return it.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli() }
        runCatching { OffsetDateTime.parse(cleaned) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }
        return null
    }

    /**
     * Resolves the human phrasing the extractor may have left as-is:
     * "Sunday 22 November in the morning", "tomorrow at 6", "tonight".
     * Returns the next matching local time, or null when there is no date at all.
     */
    fun inferDue(text: String, now: Long = System.currentTimeMillis()): Long? {
        val lower = text.lowercase(Locale.getDefault())
        val zone = ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val day = when {
            lower.contains("day after tomorrow") -> today.plusDays(2)
            lower.contains("tomorrow") -> today.plusDays(1)
            lower.contains("today") || lower.contains("tonight") -> today
            else -> WEEKDAYS.entries
                .firstOrNull { (name, _) -> Regex("\\b$name\\b").containsMatchIn(lower) }
                ?.value
                ?.let { target ->
                    today.with(TemporalAdjusters.nextOrSame(target)).let {
                        if (it == today && !lower.contains("today")) it.plusWeeks(1) else it
                    }
                }
                ?: parsedMonthDay(lower, today)
                ?: return null
        }

        val hour = when {
            Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?\\b").find(lower) != null -> {
                val match = Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?\\b").find(lower)!!
                var h = match.groupValues[1].toIntOrNull() ?: 9
                val m = match.groupValues[2].toIntOrNull() ?: 0
                if (match.groupValues[3] == "pm" && h < 12) h += 12
                if (match.groupValues[3] == "am" && h == 12) h = 0
                return LocalDateTime.of(day, LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59)))
                    .atZone(zone).toInstant().toEpochMilli()
            }
            Regex("\\b(\\d{1,2})\\s*(am|pm)\\b").find(lower) != null -> {
                val match = Regex("\\b(\\d{1,2})\\s*(am|pm)\\b").find(lower)!!
                var h = match.groupValues[1].toIntOrNull() ?: 9
                if (match.groupValues[2] == "pm" && h < 12) h += 12
                if (match.groupValues[2] == "am" && h == 12) h = 0
                h
            }
            Regex("\\bat\\s+(\\d{1,2})\\b").find(lower) != null -> {
                Regex("\\bat\\s+(\\d{1,2})\\b").find(lower)!!.groupValues[1].toIntOrNull() ?: 9
            }
            lower.contains("morning") -> 9
            lower.contains("noon") -> 12
            lower.contains("afternoon") -> 15
            lower.contains("evening") -> 19
            lower.contains("night") -> 21
            else -> 9
        }

        return LocalDateTime.of(day, LocalTime.of(hour.coerceIn(0, 23), 0))
            .atZone(zone).toInstant().toEpochMilli()
    }

    private fun parsedMonthDay(text: String, today: LocalDate): LocalDate? {
        val match = Regex("\\b(\\d{1,2})\\s+(january|february|march|april|may|june|july|august|september|october|november|december)\\b")
            .find(text) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = MONTHS.indexOf(match.groupValues[2]) + 1
        if (month <= 0) return null
        val candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
    }

    private fun ack(memory: Memory): String = buildString {
        append(memory.title)
        if (memory.progress.isNotBlank()) append(" · ").append(memory.progress)
        if (memory.status == MemoryStatus.DONE) append(" · done")
        if (memory.collection.isNotBlank()) append(" (").append(memory.collection).append(')')
        if (memory.dueAt != null && memory.kind.needsDue) {
            append(" · ").append(SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(memory.dueAt)))
        }
    }

    private data class SavedImage(val path: String, val name: String)

    private data class Action(
        val isArchive: Boolean,
        val id: Long?,
        val kind: MemoryKind,
        val subject: String,
        val title: String,
        val content: String?,
        val collection: String?,
        val progress: String?,
        val status: MemoryStatus?,
        val dueAt: Long?,
        val event: String?
    )

    private companion object {
        const val CANDIDATE_LIMIT = 30
        const val MAX_IMAGES = 6

        val MONTHS = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
        )

        val WEEKDAYS = mapOf(
            "monday" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY
        )

        val EXTRACT_PROMPT = """
            You are the memory agent of a private assistant on the user's phone.
            Your only job is to turn what the user just said (and any attached
            screenshot or photo) into structured memories.

            Reply with JSON only, no prose. Every memory uses the same shape; leave
            out whatever does not apply instead of inventing a value:
            {"memories":[{"action":"create|update|complete|archive","id":12,
            "kind":"FACT|TASK|ITEM|PROGRESS|NOTE|TRANSACTION",
            "subject":"stable lowercase key","title":"short label",
            "content":"details worth keeping","collection":"watching|reading|listening|shopping|ideas",
            "progress":"S1E2","status":"ACTIVE|DONE|ARCHIVED",
            "dueAt":"YYYY-MM-DDTHH:mm, only for TASK",
            "event":"short timeline note"}]}

            Fields, by what the thing is:
            - Every kind: title (under 60 characters), subject, content, and event
              when something changed.
            - TASK: dueAt is REQUIRED. Resolve it to an absolute local date and time
              using the current date given to you ("tomorrow at 6", "Sunday morning"
              -> choose a sensible hour). Content says what to do.
            - A request to be reminded ("remind me to call Mom at 6") is NOT a memory:
              the assistant sets a clock alarm or a calendar event for that, so leave
              it out and reply {"memories":[]} for it.
            - ITEM: one thing on a list; collection says which list. No date.
            - PROGRESS: one row per series, book or game being followed; the current
              position goes in progress, the collection says what kind of thing it is.
              No date.
            - TRANSACTION: money in or out; title like "₹450 · BigBasket", content
              with merchant, category and date. No separate due date.
            - FACT: plain information. No date, no due date, even when the text
              mentions a day.
            - NOTE: a note is a list of things kept under one subject ("app bugs",
              "gift ideas"). Its content MUST be the note's items, one per line,
              each line starting with "- ", copied as the user wrote them. Keep every
              item already in the existing note and add the new ones: never merge
              items into a sentence, never summarise, never drop an earlier item.
              A note never gets a due date. One subject is one note.

            Rules:
            - One row per thing. A series, book or game being followed is ONE
              PROGRESS row: update it and put the current position in progress
              (episode, chapter, page). Never create a second row for the same series.
            - Use the id of an existing memory only when the subject really matches the
              id shown. When unsure, leave id out and give the subject; the app resolves
              it. Never invent an id.
            - "I finished E2" is a progress/event change, not a new memory. "I started
              E3" sets progress. Keep the timeline in "event".
            - For a list (bug list, ideas, shopping), content must contain the COMPLETE
              list including every item already in the existing memory plus the new
              one. Never drop earlier items and never cut a sentence off.
            - Never add dueAt to a FACT, NOTE, ITEM, PROGRESS or TRANSACTION.
            - Things to watch/read/listen to later go in the matching collection.
            - Screenshots: extract only concrete, useful details (names, prices, dates,
              links, places, code) - not the app frame or UI labels.
            - Ignore small talk, questions and anything not worth keeping: reply
              {"memories":[]}. Never invent facts and never store secrets or passwords.
        """.trimIndent()
    }
}
