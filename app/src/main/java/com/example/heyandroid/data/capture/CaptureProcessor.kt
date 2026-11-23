package com.example.heyandroid.data.capture

import android.util.Log
import com.example.heyandroid.data.AppDatabase
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.data.memory.MemorySource
import com.example.heyandroid.memory.MemoryRepository
import com.example.heyandroid.router.KeywordRouter
import com.example.heyandroid.router.Route
import com.example.heyandroid.work.CaptureWorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of storing one capture: the row id, or null when the write failed.
 * [noteId] is set when the keyword router turned the capture into a memory note.
 */
data class CaptureResult(val captureId: Long?, val noteId: Long? = null) {
    val saved: Boolean get() = captureId != null
}

/**
 * Capture first, process later: the raw text is committed to Room before routing,
 * so nothing is lost if processing fails. Captures the deterministic router cannot
 * handle are queued for the background model pass.
 */
class CaptureProcessor @Inject constructor(
    private val db: AppDatabase,
    private val repository: MemoryRepository,
    private val scheduler: CaptureWorkScheduler
) {

    private companion object {
        const val TAG = "CaptureProcessor"
    }

    suspend fun process(text: String): CaptureResult = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext CaptureResult(captureId = null)

        val captureId = try {
            db.captureDao().insert(Capture(text = trimmed, status = CaptureStatus.CAPTURED))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving capture to Room", e)
            return@withContext CaptureResult(captureId = null)
        }

        val route = KeywordRouter.route(trimmed)
        if (route is Route.CreateNote) {
            try {
                val now = System.currentTimeMillis()
                // Notes are memory rows now, so there is one store and one search.
                val memoryId = repository.save(
                    Memory(
                        kind = MemoryKind.NOTE,
                        subject = route.title.lowercase().take(60),
                        title = route.title,
                        content = route.body,
                        source = MemorySource.VOICE,
                        createdAt = now,
                        updatedAt = now
                    ),
                    "Captured note"
                )
                db.captureDao().updateStatus(captureId, CaptureStatus.PROCESSED)
                return@withContext CaptureResult(captureId = captureId, noteId = memoryId)
            } catch (e: Exception) {
                // The capture is already safe; a failed note just leaves it unprocessed.
                Log.e(TAG, "Error creating note from capture", e)
            }
        }

        try {
            scheduler.schedule(captureId)
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule capture processing", e)
        }

        CaptureResult(captureId = captureId)
    }

    /**
     * Stores an interactive chat turn exactly as spoken or typed, without routing
     * or scheduling background work: the conversation handles the reply already.
     */
    suspend fun record(text: String): CaptureResult = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext CaptureResult(captureId = null)

        val captureId = try {
            db.captureDao().insert(Capture(text = trimmed, status = CaptureStatus.CAPTURED))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat capture", e)
            null
        }
        CaptureResult(captureId = captureId)
    }

    /** Marks a capture as handled once the conversation has answered it. */
    suspend fun markProcessed(captureId: Long) {
        withContext(Dispatchers.IO) {
            runCatching { db.captureDao().updateStatus(captureId, CaptureStatus.PROCESSED) }
        }
    }

    /** Marks a capture failed when the turn it belonged to could not finish. */
    suspend fun markFailed(captureId: Long) {
        withContext(Dispatchers.IO) {
            runCatching { db.captureDao().updateStatus(captureId, CaptureStatus.FAILED) }
        }
    }

    /** Clears captures left pending by an older build or a killed process. */
    suspend fun failStale(olderThanMillis: Long = 60 * 60 * 1000L) {
        withContext(Dispatchers.IO) {
            runCatching {
                db.captureDao().failStale(System.currentTimeMillis() - olderThanMillis)
            }
        }
    }
}
