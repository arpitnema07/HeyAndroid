package com.example.heyandroid.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: Memory): Long

    @Update
    suspend fun update(memory: Memory)

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: Long): Memory?

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Memory>

    @Query("SELECT * FROM memories WHERE status != 'ARCHIVED' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun active(limit: Int): List<Memory>

    @Query("SELECT * FROM memories WHERE subject = :subject AND kind = :kind LIMIT 1")
    suspend fun bySubject(subject: String, kind: String): Memory?

    @Query(
        "SELECT * FROM memories WHERE subject LIKE '%' || :query || '%' " +
            "OR title LIKE '%' || :query || '%' " +
            "OR content LIKE '%' || :query || '%' " +
            "OR collection LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun keywordSearch(query: String, limit: Int): List<Memory>

    @Query("SELECT * FROM memories WHERE kind = :kind ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun byKind(kind: String, limit: Int): List<Memory>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM memories WHERE status != 'ARCHIVED'")
    suspend fun activeCount(): Int
}

@Dao
interface MemoryEventDao {

    @Insert
    suspend fun insert(event: MemoryEvent): Long

    @Query("SELECT * FROM memory_events WHERE memoryId = :memoryId ORDER BY at DESC LIMIT :limit")
    suspend fun recent(memoryId: Long, limit: Int): List<MemoryEvent>

    @Query("SELECT * FROM memory_events WHERE memoryId = :memoryId ORDER BY at ASC")
    suspend fun all(memoryId: Long): List<MemoryEvent>

    @Query("DELETE FROM memory_events WHERE memoryId = :memoryId")
    suspend fun deleteFor(memoryId: Long)
}

@Dao
interface MemoryVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vector: MemoryVector)

    @Query("SELECT * FROM memory_vectors")
    suspend fun all(): List<MemoryVector>

    @Query("DELETE FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun delete(memoryId: Long)

    @Query("SELECT COUNT(*) FROM memory_vectors")
    suspend fun count(): Int
}
