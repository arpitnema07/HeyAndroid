package com.example.heyandroid.assistant

import com.example.heyandroid.data.capture.CaptureProcessor
import com.example.heyandroid.data.chat.Message
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.chat.MessageRole
import com.example.heyandroid.data.conversation.Conversation
import com.example.heyandroid.data.conversation.ConversationDao
import com.example.heyandroid.data.memory.MemorySource
import com.example.heyandroid.data.turn.TurnLog
import com.example.heyandroid.data.turn.TurnLogDao
import com.example.heyandroid.llm.AgentOutcome
import com.example.heyandroid.llm.LlmAgentFactory
import com.example.heyandroid.llm.LlmMessage
import com.example.heyandroid.llm.LlmResult
import com.example.heyandroid.llm.ToolRun
import com.example.heyandroid.memory.IngestResult
import com.example.heyandroid.memory.MemoryEngine
import com.example.heyandroid.memory.MemoryIntent
import com.example.heyandroid.memory.MemoryText
import com.example.heyandroid.tools.CallChoiceStore
import com.example.heyandroid.tools.ToolRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Lines that still mean something to the model. Tool status and error lines are
 * for the user only.
 */
internal fun messageToLlmMessage(message: Message): LlmMessage? = when (message.role) {
    MessageRole.USER -> LlmMessage.user(message.content)
    MessageRole.ASSISTANT -> LlmMessage(role = "assistant", content = message.content)
    MessageRole.STATUS, MessageRole.ERROR -> null
}

/**
 * Drives one interactive conversation.
 *
 * Each turn runs two agents in order: the memory agent decides what is worth
 * keeping and writes it (with vectors and a timeline), then the chat agent
 * answers with the retrieved memories as context and a routed subset of tools.
 * Conversations are rows, so starting a new one never destroys the old thread.
 *
 * Every model turn also writes one structured [TurnLog] row, which is what the
 * Log tab shows: provider, model, steps, tools, duration and the error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSession(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val captureProcessor: CaptureProcessor,
    private val agentFactory: LlmAgentFactory,
    private val memoryEngine: MemoryEngine,
    private val toolRouter: ToolRouter,
    private val turnLogDao: TurnLogDao,
    private val callChoices: CallChoiceStore,
    private val scope: CoroutineScope
) {
    private val _conversationId = MutableStateFlow(0L)
    val conversationId: StateFlow<Long> = _conversationId.asStateFlow()

    val messages: StateFlow<List<Message>> = _conversationId
        .flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList()) else messageDao.observeMessages(id)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    /** Emitted once a reply is complete, so the overlay can start listening again. */
    val turnFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    private var sendJob: Job? = null

    /**
     * Resolves once the new conversation row exists. Speech starts the moment the
     * overlay opens, so a turn can arrive before the insert finishes; awaiting this
     * keeps the first sentence from being dropped.
     */
    private var conversationReady = CompletableDeferred<Long>()

    /** Every assistant invocation starts a fresh conversation. */
    fun startNewConversation() {
        cancel()
        callChoices.clear()
        val ready = CompletableDeferred<Long>()
        conversationReady = ready
        _conversationId.value = 0L
        scope.launch {
            val id = conversationDao.insert(Conversation())
            ready.complete(id)
            _conversationId.value = id
        }
    }

    /**
     * Sends one turn. [imageDataUrl] is a `data:` URL; it is also written to a
     * private file when the memory agent keeps it, so the image survives the turn.
     * Ignored while a reply is in flight.
     */
    fun send(
        text: String,
        imageDataUrl: String? = null,
        source: MemorySource = MemorySource.VOICE
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageDataUrl == null) return
        if (sendJob?.isActive == true) return

        // A call is on its countdown: "cancel" or "stop" calls it off, and nothing
        // reaches the model while the seconds tick.
        if (imageDataUrl == null && callChoices.pending.value != null &&
            trimmed.split(' ').size <= 4 &&
            CANCEL_WORDS.containsMatchIn(trimmed.lowercase())
        ) {
            cancelPendingCall()
            return
        }

        // "Call Mom" is resolved here, against the real contacts, so the options and
        // the countdown never depend on the model choosing the right tool.
        if (imageDataUrl == null && CallChoiceStore.looksLikeCall(trimmed)) {
            val reply = runCatching { callChoices.handleLocally(trimmed) }.getOrNull()
            if (reply != null) {
                recordLocally(trimmed, reply)
                return
            }
        }

        // "1" or "the second one" answers the contact options on screen; that is a
        // local dial, so no model turn is spent on it.
        if (imageDataUrl == null && trimmed.split(' ').size <= 3) {
            val pending = callChoices.choices.value
            val index = if (pending.isEmpty()) null else CallChoiceStore.indexOf(trimmed)
            if (index != null && index < pending.size) {
                chooseCall(index)
                return
            }
        }
        // Anything else moves the conversation on, so the old options are withdrawn.
        callChoices.clear()

        sendJob = scope.launch {
            _thinking.value = true
            val conversationId = conversationReady.await()
            if (conversationId == 0L) {
                _thinking.value = false
                return@launch
            }

            var captureId: Long? = null
            try {
                val spoken = trimmed.ifEmpty { "Save what is in this screenshot." }
                messageDao.insert(
                    Message(conversationId = conversationId, role = MessageRole.USER, content = spoken)
                )
                refreshConversationMeta(conversationId)

                captureId = runCatching { captureProcessor.record(spoken).captureId }.getOrNull()

                // 1. Memory agent: decide and store. Photos are always read; typed or
                //    spoken statements only when they sound like something to keep.
                val shouldRemember = when {
                    imageDataUrl != null -> !MemoryIntent.isQuestion(trimmed)
                    else -> MemoryIntent.shouldRecord(trimmed)
                }
                val ingestion = if (shouldRemember) {
                    runCatching {
                        memoryEngine.ingest(spoken, imageDataUrl, source)
                    }.getOrNull()
                } else {
                    null
                }

                // A plain statement that was stored needs no second model call: the
                // stored summary is the whole answer, and replying locally is instant.
                val answerLocally = shouldRemember &&
                    imageDataUrl == null &&
                    !MemoryIntent.isQuestion(trimmed) &&
                    (ingestion?.changed ?: 0) > 0

                if (answerLocally) {
                    messageDao.insert(
                        Message(
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = localAck(ingestion!!)
                        )
                    )
                    writeTurnLog(
                        outcome = "local",
                        steps = 0,
                        toolRuns = emptyList(),
                        durationMs = 0,
                        error = null
                    )
                    captureId?.let { captureProcessor.markProcessed(it) }
                } else {
                    // One line summarising what was saved, then the model's answer.
                    ingestion?.saved?.takeIf { it.isNotEmpty() }?.let { lines ->
                        messageDao.insert(
                            Message(
                                conversationId = conversationId,
                                role = MessageRole.STATUS,
                                content = if (lines.size == 1) {
                                    "Saved · ${lines.first()}"
                                } else {
                                    "Saved ${lines.size} things\n" +
                                        lines.joinToString("\n") { "· $it" }
                                }
                            )
                        )
                    }
                    answerWithModel(
                        conversationId = conversationId,
                        spoken = spoken,
                        trimmed = trimmed,
                        imageDataUrl = imageDataUrl
                    )
                    captureId?.let { captureProcessor.markProcessed(it) }
                }
            } catch (e: Exception) {
                captureId?.let { captureProcessor.markFailed(it) }
                messageDao.insert(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ERROR,
                        content = "Something went wrong: ${e.message ?: "unknown error"}"
                    )
                )
                writeTurnLog(
                    outcome = "failed",
                    steps = 0,
                    toolRuns = emptyList(),
                    durationMs = 0,
                    error = e.message ?: e.javaClass.simpleName
                )
            } finally {
                refreshConversationMeta(conversationId)
                _thinking.value = false
                turnFinished.tryEmit(Unit)
            }
        }
    }

    /**
     * Takes one of the pending contact options. The tap on a row and an ordinal
     * reply both land here; the call itself waits in the confirmation countdown.
     */
    fun chooseCall(index: Int) {
        val pending = callChoices.choices.value
        val chosen = pending.getOrNull(index) ?: return
        callChoices.confirmCall(chosen.name, chosen.number)
        recordLocally("Call ${chosen.name}", null)
    }

    /** Stops a pending call and leaves a short note in the conversation. */
    fun cancelPendingCall() {
        if (callChoices.cancelCall()) recordLocally("Cancel", "Call cancelled.")
    }

    /** Writes a turn that needs no model: the user's line and an optional reply. */
    private fun recordLocally(userText: String, reply: String?) {
        scope.launch {
            val conversationId = conversationReady.await()
            if (conversationId == 0L) return@launch
            messageDao.insert(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.USER,
                    content = userText
                )
            )
            if (reply != null) {
                messageDao.insert(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = reply
                    )
                )
            }
            refreshConversationMeta(conversationId)
            turnFinished.tryEmit(Unit)
        }
    }

    private suspend fun answerWithModel(
        conversationId: Long,
        spoken: String,
        trimmed: String,
        imageDataUrl: String?
    ) {
        val agent = if (imageDataUrl != null) {
            agentFactory.createForImages()
        } else {
            agentFactory.create()
        }
        if (agent == null) {
            messageDao.insert(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.ERROR,
                    content = if (imageDataUrl != null) {
                        "Photos need an API key and a model that can read images."
                    } else {
                        "Add an API key in Settings to chat."
                    }
                )
            )
            writeTurnLog("failed", 0, emptyList(), 0, "No API key configured")
            return
        }

        val history = messageDao.recentMessages(conversationId, HISTORY_LIMIT)
            .asReversed()
            .mapNotNull { messageToLlmMessage(it) }

        // The stored line is already in the history; attach the photo to it.
        val conversation = if (imageDataUrl != null && history.isNotEmpty()) {
            history.dropLast(1) + LlmMessage.user(spoken, listOf(imageDataUrl))
        } else {
            history
        }

        val recallQuery = trimmed.ifBlank { spoken }
        val memoryContext = runCatching { memoryEngine.recall(recallQuery) }.getOrNull().orEmpty()
        val systemPrompt = buildSystemPrompt(memoryContext)

        val startedAt = System.currentTimeMillis()
        val outcome = agent.run(
            messages = listOf(LlmMessage.system(systemPrompt)) + conversation,
            maxTokens = 700,
            toolSpecs = toolRouter.select(recallQuery)
        )
        val duration = System.currentTimeMillis() - startedAt

        // One collapsed activity block per turn, with identical calls removed.
        toolTrace(outcome.toolRuns)?.let { trace ->
            messageDao.insert(
                Message(
                    conversationId = conversationId,
                    role = MessageRole.STATUS,
                    content = trace
                )
            )
        }

        when (val result = outcome.result) {
            is LlmResult.Success -> {
                messageDao.insert(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = result.text
                    )
                )
                writeTurnLog("answered", outcome, duration, null)
            }

            is LlmResult.Failure -> {
                messageDao.insert(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ERROR,
                        content = result.message
                    )
                )
                writeTurnLog("failed", outcome, duration, result.message)
            }

            is LlmResult.ToolCalls -> {
                messageDao.insert(
                    Message(
                        conversationId = conversationId,
                        role = MessageRole.ERROR,
                        content = "The model kept asking for tools. Try again."
                    )
                )
                writeTurnLog("failed", outcome, duration, "Unresolved tool calls")
            }
        }
    }

    /**
     * A single status line summarising what the tools did, rather than one raw
     * model-facing dump per call. The UI collapses it until tapped.
     */
    private fun toolTrace(runs: List<ToolRun>): String? {
        val unique = runs.distinctBy { it.name to it.argumentsJson to it.result }
        if (unique.isEmpty()) return null
        return buildString {
            append("Tool activity · ").append(unique.size)
            append(if (unique.size == 1) " call" else " calls")
            unique.forEach { run ->
                append('\n')
                append(run.name)
                summarizeArguments(run.argumentsJson)?.let { append(" · ").append(it) }
                append(" → ").append(oneLine(run.result).take(110))
            }
        }
    }

    private fun summarizeArguments(json: String): String? {
        if (json.isBlank() || json == "{}") return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val parts = mutableListOf<String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key)?.toString()?.trim().orEmpty()
            if (value.isBlank() || value == "null") return@forEach
            parts += "$key=\"${value.take(40)}\""
        }
        return parts.take(2).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    private fun localAck(ingestion: IngestResult): String {
        val lines = ingestion.saved
        if (lines.isEmpty()) return "Saved to your memory."
        return if (lines.size == 1) {
            "Saved · ${lines.first()}"
        } else {
            buildString {
                append("Saved ").append(lines.size).append(" things:")
                lines.forEach { append('\n').append("· ").append(it) }
            }
        }
    }

    private suspend fun writeTurnLog(
        outcome: String,
        agent: AgentOutcome,
        durationMs: Long,
        error: String?
    ) = writeTurnLog(
        outcome = outcome,
        steps = agent.steps,
        toolRuns = agent.toolRuns,
        durationMs = durationMs,
        error = error
    )

    private suspend fun writeTurnLog(
        outcome: String,
        steps: Int,
        toolRuns: List<ToolRun>,
        durationMs: Long,
        error: String?
    ) {
        val config = runCatching { agentFactory.config }.getOrNull()
        runCatching {
            turnLogDao.insert(
                TurnLog(
                    provider = config?.provider?.displayName.orEmpty(),
                    model = config?.model.orEmpty(),
                    steps = steps,
                    toolNames = toolRuns.map { it.name }.distinct().joinToString(", "),
                    durationMs = durationMs,
                    outcome = outcome,
                    error = error
                )
            )
            // Keep the log bounded; the tab shows the newest 200 anyway.
            turnLogDao.pruneBefore(System.currentTimeMillis() - LOG_RETENTION_MS)
        }
    }

    private suspend fun refreshConversationMeta(conversationId: Long) {
        runCatching {
            val existing = conversationDao.byId(conversationId) ?: return@runCatching
            val count = messageDao.countFor(conversationId)
            val firstUser = messageDao.recentMessages(conversationId, 40)
                .lastOrNull { it.role == MessageRole.USER }
            val title = existing.title.ifBlank {
                firstUser?.content?.replace('\n', ' ')?.let { MemoryText.shortTitle(it) }.orEmpty()
            }
            conversationDao.updateMeta(
                id = conversationId,
                title = title,
                updatedAt = System.currentTimeMillis(),
                messageCount = count
            )
        }
    }

    /** Stops an in-flight reply, e.g. when the overlay is dismissed. */
    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        _thinking.value = false
    }

    private fun buildSystemPrompt(memoryContext: String): String = buildString {
        append(SYSTEM_PROMPT)
        if (memoryContext.isNotBlank()) {
            append("\n\nWhat you already remember about this user (context, not user words):\n")
            append(memoryContext)
            append("\nUse this to answer. Do not repeat it back unless asked.")
        } else {
            append("\n\nNothing relevant is stored in memory yet.")
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 20

        /** Said while a call is counting down, these stop it. */
        val CANCEL_WORDS = Regex(
            "\\b(cancel|stop|abort|no|don't|dont|never mind|nevermind|hold on|wait)\\b"
        )
        const val LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        val SYSTEM_PROMPT =
            "You are HeyAndroid, a private assistant that lives on the user's phone and " +
                "acts as their external memory. Reply briefly and conversationally in the " +
                "user's language, using Markdown for lists and emphasis. This is a text " +
                "chat: your reply is shown on screen, never spoken. Call save_memory when " +
                "the user shares something worth keeping (for a note that already exists, " +
                "use edit_note instead), search_memory or list_memories " +
                "before answering questions about their own lists, tasks, spending or " +
                "progress, and update_memory to move something forward. Use the id shown in " +
                "search results instead of guessing one. If you do not know something, are " +
                "not sure, or your information may be out of date, call web_search before " +
                "answering instead of guessing or refusing. Call it too for anything about " +
                "the outside world that may have changed: news, prices, opening hours, " +
                "weather, scores, releases. If it reports that web search is off, answer " +
                "from what you know and say the answer is not live. When a photo or screenshot is " +
                "attached, read it and use the details. Call start_call to phone someone, " +
                "add_calendar_event to put something in the calendar, and set_alarm for " +
                "every request to be reminded (with a date it sets a clock alarm when that " +
                "date is within the next seven days, and opens the calendar for the event " +
                "when it is further out), for an alarm and for a timer. A reminder is never " +
                "a memory: do not call save_memory for one. Each of these opens the matching " +
                "system app with the details filled in, so say the user finishes it there. edit_note changes one note " +
                "item by item - add, correct, remove, or delete the note - so use it instead " +
                "of saving a second note. mcp_call reaches the user's connected MCP server " +
                "(Gmail, Calendar, Drive, Docs) when they ask about their own mail or files. " +
                "After a tool runs, say plainly what happened. Never claim an action you " +
                "did not take. When the user is only thanking you or saying goodbye, answer " +
                "in a few words and nothing more."
    }
}
