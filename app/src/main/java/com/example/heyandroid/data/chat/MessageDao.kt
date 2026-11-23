package com.example.heyandroid.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeMessages(conversationId: Long): Flow<List<Message>>

    /** Newest first; callers reverse this into chronological chat history. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun recentMessages(conversationId: Long, limit: Int): List<Message>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countFor(conversationId: Long): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clear(conversationId: Long)

    /** Every error line, newest first, for the Log tab. */
    @Query("SELECT * FROM messages WHERE role = 'ERROR' ORDER BY id DESC LIMIT :limit")
    fun observeErrors(limit: Int): Flow<List<Message>>
}
