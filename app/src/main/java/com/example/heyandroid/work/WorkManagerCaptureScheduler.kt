package com.example.heyandroid.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules post-capture processing. Kept behind an interface so the capture
 * processor does not depend on WorkManager directly.
 */
interface CaptureWorkScheduler {
    fun schedule(captureId: Long)
}

@Singleton
class WorkManagerCaptureScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : CaptureWorkScheduler {

    override fun schedule(captureId: Long) {
        val request = OneTimeWorkRequestBuilder<CaptureProcessWorker>()
            .setInputData(workDataOf(CaptureProcessWorker.KEY_CAPTURE_ID to captureId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$UNIQUE_PREFIX$captureId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        const val UNIQUE_PREFIX = "capture-process-"
    }
}
