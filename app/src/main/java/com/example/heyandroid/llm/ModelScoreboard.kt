package com.example.heyandroid.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * A small track record per model, learned while the app runs.
 *
 * Every call a model answers or fails is recorded, and the fallback chain is
 * ordered by what actually worked: a model that keeps answering is tried before
 * one that timed out last time, and a model with a poor record is pushed to the
 * back (benched) without being deleted, so it can come back if the provider
 * recovers. Nothing here is uploaded; it is a few counters in app-private prefs.
 */
class ModelScoreboard private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("model_scores", Context.MODE_PRIVATE)

    /** One model's record. [key] is "providerId:modelId". */
    data class Entry(
        val attempts: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val averageLatencyMs: Long = 0
    ) {
        val errorRate: Double
            get() = if (attempts == 0) 0.0 else failures.toDouble() / attempts

        /**
         * 0.5 for a model never called, so a new candidate sits in the middle;
         * near 1.0 for one that always answers quickly, near 0 for one that does not.
         */
        val score: Double
            get() {
                if (attempts == 0) return 0.5
                val reliability = successes.toDouble() / attempts
                val speed = when {
                    averageLatencyMs <= 4_000L -> 0.05
                    averageLatencyMs <= 10_000L -> 0.0
                    else -> -0.1
                }
                return (reliability + speed).coerceIn(0.0, 1.0)
            }

        /** Two failures in a row, or a record that keeps failing, bench a model. */
        val benched: Boolean
            get() = consecutiveFailures >= 2 || (attempts >= 4 && errorRate >= 0.6)
    }

    fun entry(key: String): Entry = decode(prefs.getString(key, null))

    fun score(key: String): Double = entry(key).score

    fun isBenched(key: String): Boolean = entry(key).benched

    fun recordSuccess(key: String, latencyMs: Long) {
        val old = entry(key)
        val attempts = old.attempts + 1
        // A running average, so one slow answer does not rewrite the model's speed.
        val average = if (attempts == 1) {
            latencyMs
        } else {
            (old.averageLatencyMs * (attempts - 1) + latencyMs) / attempts
        }
        save(
            key,
            old.copy(
                attempts = attempts,
                successes = old.successes + 1,
                consecutiveFailures = 0,
                averageLatencyMs = average
            )
        )
    }

    fun recordFailure(key: String) {
        val old = entry(key)
        save(
            key,
            old.copy(
                attempts = old.attempts + 1,
                failures = old.failures + 1,
                consecutiveFailures = old.consecutiveFailures + 1
            )
        )
    }

    /** Forgets a model's record, e.g. after the user changes provider. */
    fun reset(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun save(key: String, entry: Entry) {
        prefs.edit()
            .putString(
                key,
                listOf(
                    entry.attempts,
                    entry.successes,
                    entry.failures,
                    entry.consecutiveFailures,
                    entry.averageLatencyMs
                ).joinToString(",")
            )
            .apply()
    }

    private fun decode(value: String?): Entry {
        val parts = value?.split(',') ?: return Entry()
        val numbers = parts.mapNotNull { it.trim().toLongOrNull() }
        if (numbers.size < 5) return Entry()
        return Entry(
            attempts = numbers[0].toInt(),
            successes = numbers[1].toInt(),
            failures = numbers[2].toInt(),
            consecutiveFailures = numbers[3].toInt(),
            averageLatencyMs = numbers[4]
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: ModelScoreboard? = null

        fun getInstance(context: Context): ModelScoreboard =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelScoreboard(context).also { INSTANCE = it }
            }
    }
}
