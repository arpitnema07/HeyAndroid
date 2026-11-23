package com.example.heyandroid.data.capture

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CaptureStatus {
    CAPTURED,
    PROCESSED,
    FAILED
}

/**
 * Represents a raw user dictation/input capture.
 * Core design rule: "Capture first, process later".
 * Saved to Room immediately before any LLM or keyword processing.
 */
@Entity(tableName = "captures")
data class Capture(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: CaptureStatus = CaptureStatus.CAPTURED
)
