package com.example.heyandroid.memory

import com.example.heyandroid.data.AppDatabase
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryCard
import com.example.heyandroid.data.memory.attachedImages
import com.example.heyandroid.data.memory.MemoryEvent
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.data.memory.MemoryStatus
import com.example.heyandroid.data.memory.MemoryVector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Reads and writes the memory store, keeping the vector index in step with the
 * rows: every save, update or delete rewrites exactly one vector.
 *
 * Search fuses two signals — cosine similarity over the local vectors and a
 * plain keyword match — so an exact name found by text still ranks high even
 * when the hashed embedding is fuzzy.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val embedder: LocalEmbedder
) {
    private val memories = db.memoryDao()
    private val events = db.memoryEventDao()
    private val vectors = db.memoryVectorDao()

    fun observeRecent(limit: Int = 200): Flow<List<Memory>> = memories.observeRecent(limit)

    suspend fun recent(limit: Int = 60): List<Memory> = withContext(Dispatchers.IO) {
        memories.recent(limit)
    }

    suspend fun byId(id: Long): Memory? = withContext(Dispatchers.IO) { memories.byId(id) }

    suspend fun find(subject: String, kind: MemoryKind): Memory? = withContext(Dispatchers.IO) {
        if (subject.isBlank()) null else memories.bySubject(subject, kind.name)
    }

    /**
     * Finds a memory whose subject is the same thing as [subject], allowing for
     * plurals, word order and small wording changes ("lantern" / "Lanterns S1").
     * Only kinds that can describe the same thing are compared.
     */
    suspend fun findSimilar(subject: String, kind: MemoryKind): Memory? =
        withContext(Dispatchers.IO) {
            val key = subject.trim()
            if (key.isBlank()) return@withContext null
            memories.active(SEARCH_POOL)
                .asSequence()
                .filter { kindFamily(it.kind) == kindFamily(kind) }
                .filter { MemoryText.similarSubjects(key, it.subject) }
                .maxByOrNull { overlapScore(key, it.subject) }
        }

    /** Inserts or updates a memory and re-embeds it. Returns its id. */
    suspend fun save(memory: Memory, event: String? = null): Long = withContext(Dispatchers.IO) {
        val id = if (memory.id == 0L) {
            memories.insert(memory)
        } else {
            memories.update(memory)
            memory.id
        }
        val stored = memory.copy(id = id)
        reindex(stored)
        event?.takeIf { it.isNotBlank() }?.let {
            events.insert(MemoryEvent(memoryId = id, summary = it.trim().take(240)))
        }
        id
    }

    suspend fun addEvent(memoryId: Long, summary: String) = withContext(Dispatchers.IO) {
        events.insert(MemoryEvent(memoryId = memoryId, summary = summary.trim().take(240)))
        Unit
    }

    suspend fun delete(memoryId: Long) = withContext(Dispatchers.IO) {
        events.deleteFor(memoryId)
        vectors.delete(memoryId)
        memories.delete(memoryId)
    }

    suspend fun activeCount(): Int = withContext(Dispatchers.IO) { memories.activeCount() }

    suspend fun timeline(memoryId: Long, limit: Int = 12): List<MemoryEvent> =
        withContext(Dispatchers.IO) {
            events.recent(memoryId, limit).reversed()
        }

    /**
     * Hybrid search: vector similarity over every stored embedding, plus the
     * keyword matches SQL found. Keyword hits get a boost because an exact word
     * ("Breaking Bad") is stronger evidence than a fuzzy vector neighbour.
     */
    suspend fun search(query: String, limit: Int = 12): List<MemoryCard> =
        withContext(Dispatchers.IO) {
            val text = query.trim()
            if (text.isBlank()) {
                return@withContext memories.recent(limit).map { MemoryCard(it) }
            }

            val keywordHits = memories.keywordSearch(text, limit).associateBy { it.id }
            val pool = memories.active(SEARCH_POOL).associateBy { it.id }
            val queryVector = embedder.embed(text)

            val scores = HashMap<Long, Float>()
            keywordHits.keys.forEach { id -> scores[id] = KEYWORD_BOOST }
            vectors.all().forEach { stored ->
                if (!pool.containsKey(stored.memoryId)) return@forEach
                val similarity = embedder.cosine(queryVector, VectorCodec.decode(stored.vector))
                if (similarity > MIN_SIMILARITY) {
                    scores[stored.memoryId] = (scores[stored.memoryId] ?: 0f) + similarity
                }
            }

            scores.entries
                .sortedByDescending { it.value }
                .take(limit)
                .mapNotNull { entry ->
                    val memory = pool[entry.key] ?: keywordHits[entry.key] ?: return@mapNotNull null
                    MemoryCard(memory = memory, events = events.recent(memory.id, 4).reversed())
                }
        }

    /** Rebuilds one memory's embedding. Called on every write to that memory. */
    suspend fun reindex(memory: Memory) = withContext(Dispatchers.IO) {
        val text = embeddingText(memory)
        vectors.upsert(
            MemoryVector(
                memoryId = memory.id,
                vector = VectorCodec.encode(embedder.embed(text)),
                text = text
            )
        )
    }

    /** Embeds rows that have no vector yet, e.g. after an app update. */
    suspend fun reindexMissing() = withContext(Dispatchers.IO) {
        val indexed = vectors.all().map { it.memoryId }.toSet()
        memories.active(500).filter { it.id !in indexed }.forEach { reindex(it) }
    }

    private fun embeddingText(memory: Memory): String = buildString {
        append(memory.subject).append(' ')
        append(memory.title).append(' ')
        append(memory.collection).append(' ')
        append(memory.progress).append(' ')
        append(memory.content).append(' ')
        append(memory.kind.name)
    }

    private fun kindFamily(kind: MemoryKind): String = when (kind) {
        MemoryKind.PROGRESS, MemoryKind.ITEM, MemoryKind.NOTE, MemoryKind.FACT -> "thing"
        MemoryKind.TASK -> "task"
        MemoryKind.TRANSACTION -> "transaction"
    }

    private fun overlapScore(a: String, b: String): Int =
        MemoryText.tokens(a).intersect(MemoryText.tokens(b)).size

    private companion object {
        const val SEARCH_POOL = 500
        const val KEYWORD_BOOST = 0.75f
        const val MIN_SIMILARITY = 0.08f
    }
}

/**
 * Turns memories into the compact lines the model and the UI read. Dates are
 * explicit because "when did I finish E2" is a question about time.
 */
object MemoryFormatter {

    private val dayFormat = SimpleDateFormat("d MMM", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

    fun kindLabel(memory: Memory): String = when (memory.kind) {
        MemoryKind.PROGRESS -> if (memory.collection.isNotBlank()) memory.collection else "progress"
        MemoryKind.TASK -> "task"
        MemoryKind.ITEM -> "item"
        MemoryKind.NOTE -> "note"
        MemoryKind.FACT -> "fact"
        MemoryKind.TRANSACTION -> "transaction"
    }

    /** Only things that happen at a time show a due date. */
    fun due(memory: Memory): String? = memory.dueAt
        ?.takeIf { memory.kind.needsDue }
        ?.let { "due ${timeFormat.format(Date(it))}" }

    fun line(memory: Memory): String = buildString {
        append("#").append(memory.id).append(' ')
        append('[').append(kindLabel(memory).uppercase(Locale.getDefault()))
        if (memory.status != MemoryStatus.ACTIVE) append(" · ").append(memory.status.name.lowercase())
        append("] ")
        append(memory.title)
        if (memory.progress.isNotBlank()) append(" — at ").append(memory.progress)
        if (memory.content.isNotBlank()) append(" — ").append(memory.content.replace('\n', ' ').take(600))
        due(memory)?.let { append(" · ").append(it) }
        val imageCount = memory.attachedImages.size
        if (imageCount == 1) append(" · image attached")
        if (imageCount > 1) append(" · ").append(imageCount).append(" images attached")
        append(" · ").append(dayFormat.format(Date(memory.updatedAt)))
    }

    /** Memory plus its timeline, used as retrieval context for the chat model. */
    fun context(card: MemoryCard): String = buildString {
        append(line(card.memory))
        card.events.takeLast(4).forEach { event ->
            append('\n').append("    · ").append(dayFormat.format(Date(event.at)))
                .append(": ").append(event.summary)
        }
    }

    fun now(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(now))
}
