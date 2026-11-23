package com.example.heyandroid.llm

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** One model that can be tried: provider, its key, its endpoint and the model id. */
data class ModelTarget(
    val provider: LlmProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String
) {
    /** Stable key for the scoreboard, e.g. "nvidia:nvidia/nemotron-3.5-bolt-e4b". */
    val key: String get() = "${provider.id}:$model"
}

/**
 * Tries the saved model first and moves down a short ladder of alternates whenever
 * one fails or is too slow, so a turn still gets an answer when a provider is
 * down, rate limited, out of quota or has retired the model.
 *
 * The ladder is ordered by [ModelScoreboard]: the model with the best record goes
 * first, models that have been failing go last but stay available. Failures that
 * are the request's fault (bad key, no tools support) are recorded too, so a
 * broken entry stops being retried before the working ones.
 */
class FallbackLlmClient(
    private val targets: List<ModelTarget>,
    private val scoreboard: ModelScoreboard,
    /** How long one model may take before the next one is tried. */
    private val attemptTimeoutMs: Long = 15_000L
) : LlmClient {

    override val provider: LlmProvider
        get() = targets.first().provider

    /** Every model this client may use, best first. Useful for the Log tab. */
    val ladder: List<ModelTarget> get() = ordered()

    override suspend fun complete(
        messages: List<LlmMessage>,
        maxTokens: Int
    ): LlmResult = run(messages, tools = null, maxTokens = maxTokens)

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        maxTokens: Int
    ): LlmResult = run(messages, tools = tools, maxTokens = maxTokens)

    private suspend fun run(
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>?,
        maxTokens: Int
    ): LlmResult {
        if (targets.isEmpty()) return LlmResult.Failure("No model is configured")

        var lastFailure: LlmResult.Failure? = null
        for (target in ordered()) {
            val client = LlmClientFactory.create(
                provider = target.provider,
                apiKey = target.apiKey,
                model = target.model,
                baseUrl = target.baseUrl,
                // One shot per model: the ladder itself is the retry, so a slow or
                // broken model does not hold the answer behind several backoffs.
                readTimeoutMs = attemptTimeoutMs,
                maxRetries = 0
            )

            val started = System.currentTimeMillis()
            val result = try {
                if (tools.isNullOrEmpty()) {
                    client.complete(messages, maxTokens)
                } else {
                    client.completeWithTools(messages, tools, maxTokens)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                LlmResult.Failure(e.message ?: "Network error")
            } catch (e: Exception) {
                LlmResult.Failure(e.message ?: "Unexpected error")
            }

            when (result) {
                is LlmResult.Success, is LlmResult.ToolCalls -> {
                    scoreboard.recordSuccess(
                        target.key,
                        System.currentTimeMillis() - started
                    )
                    return result
                }

                is LlmResult.Failure -> {
                    scoreboard.recordFailure(target.key)
                    lastFailure = result
                }
            }
        }

        return lastFailure ?: LlmResult.Failure("No model answered")
    }

    /**
     * Best record first, benched models last. `sortedWith` is stable, so models
     * with the same score keep the order the app built: saved model, its other
     * models, then the other providers.
     */
    private fun ordered(): List<ModelTarget> = targets.sortedWith(
        compareBy<ModelTarget> { scoreboard.isBenched(it.key) }
            .thenByDescending { scoreboard.score(it.key) }
    )
}
