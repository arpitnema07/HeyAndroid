package com.example.heyandroid.data.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Who wrote a line in the conversation. Status lines come from tools, errors
 * from failed requests; neither is sent back to the model.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    STATUS,
    ERROR
}

/**
 * One line of the assistant conversation. Kept in Room so the thread survives
 * closing the app, and grouped by [conversationId] so past conversations stay
 * browsable.
 */
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val conversationId: Long = 0,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
