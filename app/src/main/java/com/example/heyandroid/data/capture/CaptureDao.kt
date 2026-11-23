package com.example.heyandroid.data.capture

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(capture: Capture): Long

    @Query("SELECT * FROM captures ORDER BY timestamp DESC")
    fun getAllCaptures(): Flow<List<Capture>>

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun getCaptureById(id: Long): Capture?

    @Query("UPDATE captures SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: CaptureStatus)

    /**
     * Captures stuck before processing are marked failed, so the Log tab never
     * shows an entry that can no longer make progress as if it were pending.
     */
    @Query("UPDATE captures SET status = 'FAILED' WHERE status = 'CAPTURED' AND timestamp < :before")
    suspend fun failStale(before: Long): Int
}
