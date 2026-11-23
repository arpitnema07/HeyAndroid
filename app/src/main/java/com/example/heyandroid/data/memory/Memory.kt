package com.example.heyandroid.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What a memory is for. [PROGRESS] is the "where am I" kind: one row per series,
 * book or game whose position changes over time, with every change in its events.
 * [TRANSACTION] is money in or out: amount, merchant and category in the title
 * and content.
 *
 * [needsDue] is the one rule the rest of the app follows: only things that happen
 * at a time carry a due date. A fact, a note, a list item or a purchase does not,
 * so a date is never parsed out of its wording.
 */
enum class MemoryKind(val needsDue: Boolean) {
    FACT(false),
    TASK(true),
    ITEM(false),
    PROGRESS(false),
    NOTE(false),
    TRANSACTION(false)
}

enum class MemoryStatus {
    ACTIVE,
    DONE,
    ARCHIVED
}

/** Where the memory came from, so the list can show how it was captured. */
enum class MemorySource {
    VOICE,
    TYPED,
    PHOTO,
    SCREENSHOT
}

/**
 * One thing the user asked the app to remember: a fact, a task, an item on a
 * list, or the current position in something they are watching, reading or
 * playing. [subject] is the stable key used to update it later ("breaking bad").
 *
 * [imagePath] points at a private copy of an attached photo/screenshot in the
 * app's files directory, and [imageName] is the unique file name, kept separately
 * so the image can be identified without reading the path.
 */
@Entity(
    tableName = "memories",
    indices = [
        Index("subject"),
        Index("kind"),
        Index("updatedAt")
    ]
)
data class Memory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val kind: MemoryKind = MemoryKind.FACT,
    /** Lowercase stable key the model reuses, e.g. "breaking bad". */
    val subject: String = "",
    val title: String,
    val content: String = "",
    /** A named list: watching, reading, listening, shopping, ideas. */
    val collection: String = "",
    /** Free-form position, e.g. "S1E2". */
    val progress: String = "",
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val dueAt: Long? = null,
    val source: MemorySource = MemorySource.VOICE,
    /** Kept from the old reminder notifications; nothing writes it any more. */
    val notifiedAt: Long? = null,
    /** Absolute path of the first attached image, kept for older rows. */
    val imagePath: String? = null,
    /** Unique file name of the first attached image. */
    val imageName: String? = null,
    /**
     * Every attached image path, one per line, newest last. An empty string means
     * the row has no images; rows written before this column existed fall back to
     * [imagePath].
     */
    @ColumnInfo(defaultValue = "")
    val images: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One change to a memory, in order. This is what answers "when did I finish E2":
 * the memory holds the current position, the events hold the history.
 */
@Entity(tableName = "memory_events", indices = [Index("memoryId")])
data class MemoryEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memoryId: Long,
    val summary: String,
    val at: Long = System.currentTimeMillis()
)

/**
 * The on-device vector for one memory. Kept in its own table so an updated memory
 * only rewrites its vector, and so search can scan vectors without loading rows.
 */
@Entity(tableName = "memory_vectors")
data class MemoryVector(
    @PrimaryKey
    val memoryId: Long,
    val vector: ByteArray,
    /** Text that was embedded, kept so the index can be rebuilt after a model change. */
    val text: String
)

/** Images attached to a memory, oldest first, with the legacy single path last. */
val Memory.attachedImages: List<String>
    get() = buildList {
        addAll(images.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
        imagePath?.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
    }

/** Encodes image paths for the [Memory.images] column. */
fun encodeImages(paths: List<String>): String =
    paths.filter { it.isNotBlank() }.distinct().joinToString("\n")

/** A memory together with its recent timeline, for the transcript and for recall. */
data class MemoryCard(
    val memory: Memory,
    val events: List<MemoryEvent> = emptyList()
)
