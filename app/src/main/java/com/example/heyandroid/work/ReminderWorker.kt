package com.example.heyandroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.heyandroid.MainActivity
import com.example.heyandroid.R
import com.example.heyandroid.memory.MemoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Posts a notification for reminders that have come due. Runs every 15 minutes;
 * each reminder is marked as notified so it fires once.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: MemoryRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val due = runCatching { repository.dueReminders() }.getOrElse { return Result.retry() }
        due.forEach { memory ->
            notify(memory.id, memory.title, memory.content)
            repository.markNotified(memory.id)
        }
        return Result.success()
    }

    private fun notify(id: Long, title: String, body: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            applicationContext,
            id.toInt(),
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(body.take(120).ifBlank { "Reminder from HeyAndroid" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(400)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { manager.notify(id.toInt(), notification) }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        private const val UNIQUE = "reminder-check"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Reminders you saved in HeyAndroid" }
            )
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
