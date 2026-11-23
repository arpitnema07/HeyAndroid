package com.example.heyandroid.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.heyandroid.syncpad.SyncPadRepository
import java.util.concurrent.TimeUnit

/**
 * Pulls the newest clips into the widget cache.
 *
 * Run on demand (widget refresh, app open, after a clip is added) and every 15
 * minutes in the background, which is the shortest interval WorkManager allows.
 * While the SyncPad screen is open the screen polls faster and redraws the
 * widget, so new clips land on the home screen within a few seconds.
 */
class SyncPadSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = SyncPadRepository.getInstance(applicationContext)
        if (!repository.isConfigured) return Result.success()

        return repository.refresh(20).fold(
            onSuccess = { Result.success() },
            onFailure = {
                // A flaky network gets a couple of retries; a wrong key should not.
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        )
    }

    companion object {
        private const val ONCE = "syncpad-sync"
        private const val PERIODIC = "syncpad-sync-periodic"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncPadSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncPadSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(ONCE)
            manager.cancelUniqueWork(PERIODIC)
        }
    }
}
