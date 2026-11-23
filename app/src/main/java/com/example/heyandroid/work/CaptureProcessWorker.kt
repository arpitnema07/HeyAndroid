package com.example.heyandroid.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.heyandroid.data.capture.CaptureDao
import com.example.heyandroid.data.capture.CaptureStatus
import com.example.heyandroid.llm.LlmAgentFactory
import com.example.heyandroid.llm.LlmMessage
import com.example.heyandroid.llm.LlmResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Processes one stored capture in the background: lets the model answer it and
 * run device tools (creating or searching notes). The capture row already exists
 * before this runs, so a failed job never loses the user's words.
 */
@HiltWorker
class CaptureProcessWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val captureDao: CaptureDao,
    private val agentFactory: LlmAgentFactory
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val captureId = inputData.getLong(KEY_CAPTURE_ID, -1L)
        if (captureId <= 0) return Result.failure()

        val capture = captureDao.getCaptureById(captureId) ?: return Result.success()
        if (capture.status != CaptureStatus.CAPTURED) return Result.success()

        // No API key yet: leave the capture untouched so it can be retried later.
        val agent = agentFactory.create() ?: return Result.success()

        val outcome = agent.run(
            messages = listOf(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user(capture.text)
            ),
            maxTokens = 400
        )

        return when (outcome.result) {
            is LlmResult.Success -> {
                if (outcome.usedTools) {
                    captureDao.updateStatus(captureId, CaptureStatus.PROCESSED)
                }
                Result.success()
            }

            is LlmResult.Failure -> {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    captureDao.updateStatus(captureId, CaptureStatus.FAILED)
                    Result.success()
                }
            }

            is LlmResult.ToolCalls -> Result.retry()
        }
    }

    companion object {
        const val KEY_CAPTURE_ID = "capture_id"
        private const val MAX_ATTEMPTS = 2

        private const val SYSTEM_PROMPT =
            "You are HeyAndroid, a private assistant running on the user's phone. " +
                "The user just captured the message that follows, by voice or typing. " +
                "If it asks you to remember, note or save something, call save_memory. " +
                "If it asks what they noted about something, call search_memory or list_memories. " +
                "Otherwise reply in at most two short sentences. " +
                "Never claim a tool ran unless it did."
    }
}
