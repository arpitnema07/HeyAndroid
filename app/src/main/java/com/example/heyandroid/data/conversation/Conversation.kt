package com.example.heyandroid.data.conversation

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One assistant session. Every invocation starts a new conversation, so the
 * history tab can show what was asked when, without the older threads being
 * wiped.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** First thing the user said, shortened. */
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0
)

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 200): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): Conversation?

    @Query(
        "UPDATE conversations SET title = :title, updatedAt = :updatedAt, " +
            "messageCount = :messageCount WHERE id = :id"
    )
    suspend fun updateMeta(id: Long, title: String, updatedAt: Long, messageCount: Int)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun deleteMessages(id: Long)
}
