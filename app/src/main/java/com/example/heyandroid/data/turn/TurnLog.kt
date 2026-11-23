package com.example.heyandroid.data.turn

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One assistant turn, recorded for diagnosis: which provider and model ran, how
 * many model steps and tools it took, how long it took, and how it ended. This is
 * the data the Log tab needs to explain a bad answer or a failed request.
 */
@Entity(tableName = "turn_logs", indices = [Index("timestamp")])
data class TurnLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val provider: String = "",
    val model: String = "",
    val steps: Int = 0,
    val toolNames: String = "",
    val durationMs: Long = 0,
    /** "answered", "failed", "cancelled" or "local". */
    val outcome: String = "",
    val error: String? = null
)

@Dao
interface TurnLogDao {

    @Insert
    suspend fun insert(log: TurnLog): Long

    @Query("SELECT * FROM turn_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<TurnLog>>

    @Query("SELECT * FROM turn_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<TurnLog>

    @Query("DELETE FROM turn_logs WHERE timestamp < :before")
    suspend fun pruneBefore(before: Long)
}
