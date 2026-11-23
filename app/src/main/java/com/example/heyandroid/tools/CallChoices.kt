package com.example.heyandroid.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One person-and-number the user can pick when a name matches several contacts. */
data class CallChoice(val name: String, val number: String)

/** A resolved call, waiting out its short confirmation countdown. */
data class PendingCall(val name: String, val number: String)

/**
 * Everything that happens between "call Mom" and the dialer: the options when a
 * name is ambiguous, and the countdown that shows the name and number one last
 * time before dialing.
 *
 * The call tool fills the options; the chat shows them as tappable rows. A picked
 * option, or a name with a single match, becomes a pending call that dials after
 * [SECONDS] unless the user cancels - a wrong contact is hard to undo mid-call.
 */
@Singleton
class CallChoiceStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countdown: Job? = null

    private val _choices = MutableStateFlow<List<CallChoice>>(emptyList())
    val choices: StateFlow<List<CallChoice>> = _choices.asStateFlow()

    private val _pending = MutableStateFlow<PendingCall?>(null)

    /** The call about to be placed, if any. */
    val pending: StateFlow<PendingCall?> = _pending.asStateFlow()

    private val _secondsLeft = MutableStateFlow(0)

    /** Seconds until the pending call dials; 0 when there is no pending call. */
    val secondsLeft: StateFlow<Int> = _secondsLeft.asStateFlow()

    fun offer(options: List<CallChoice>) {
        val unique = options.distinctBy { it.number }
        android.util.Log.d("CallChoice", "offering ${unique.size} contact options: $unique")
        _choices.value = unique
    }

    /**
     * Handles "call Mom" without the model, so a name is always looked up and the
     * choices always appear. Returns the reply line when it handled the turn, and
     * null when the text is not a call by a contact name (or nothing matches).
     */
    fun handleLocally(text: String): String? {
        val name = contactNameIn(text) ?: return null
        return resolve(name)
    }

    /**
     * Turns a name into a call. One match counts down; any other match - a different
     * person or another number for the same person - becomes a tappable choice, so a
     * partial name like "Arpit" never silently picks one of several contacts.
     * Returns the reply line, or null when nothing matched.
     */
    fun resolve(name: String): String? {
        if (!ContactLookup.hasPermission(context)) return null
        val matches = ContactLookup.search(context, name, limit = 8)
        if (matches.isEmpty()) return null

        val options = matches
            .map { CallChoice(it.name, it.number) }
            .distinctBy { it.number }
        if (options.size > 1) {
            val shown = options.take(6)
            offer(shown)
            return "More than one contact matches \"$name\". " +
                "Tap one on screen, or say its number."
        }

        val only = options.first()
        confirmCall(only.name, only.number)
        return "Calling ${only.name} (${only.number}) in $SECONDS seconds. " +
            "Tap Cancel to stop."
    }

    /** The contact name in "call my mom", "dial Aarti", … ; null otherwise. */
    private fun contactNameIn(text: String): String? {
        val clean = text.trim()
        val match = CALL_PREFIX.find(clean) ?: return null
        val name = match.groupValues[1]
            .replace(Regex("(?i)\\s+please\\s*$"), "")
            .trim()
        if (name.isEmpty() || name.any { it.isDigit() }) return null
        if (name.length > 40 || name.split(' ').size > 4) return null
        return name
    }

    fun clear() {
        _choices.value = emptyList()
    }

    /** Shows who is about to be called and dials after [SECONDS] unless cancelled. */
    fun confirmCall(name: String, number: String) {
        clear()
        countdown?.cancel()
        _pending.value = PendingCall(name, number)
        countdown = scope.launch {
            for (remaining in SECONDS downTo 1) {
                _secondsLeft.value = remaining
                delay(1_000L)
            }
            val call = _pending.value ?: return@launch
            _pending.value = null
            _secondsLeft.value = 0
            placeCall(context, call.number, call.name)
        }
    }

    /** Stops the pending call before it dials. True when there was one to stop. */
    fun cancelCall(): Boolean {
        val wasPending = _pending.value != null
        countdown?.cancel()
        countdown = null
        _pending.value = null
        _secondsLeft.value = 0
        return wasPending
    }

    companion object {
        const val SECONDS = 5

        /** Whether the line starts like "call <name>", before any contact is read. */
        fun looksLikeCall(text: String): Boolean = CALL_PREFIX.containsMatchIn(text.trim())

        /** 0-based index picked by "1", "2nd", "first", "two", …; null when unclear. */
        fun indexOf(text: String): Int? {
            val tokens = text.trim().lowercase()
                .split(Regex("[\\s,.!?]+"))
                .filter { it.isNotBlank() }
            tokens.forEach { token ->
                token.toIntOrNull()?.let { return if (it in 1..9) it - 1 else null }
                (ORDINALS[token] ?: CARDINALS[token])?.let { return it - 1 }
            }
            return null
        }

        private val CALL_PREFIX = Regex(
            "(?i)^(?:please\\s+)?(?:call|dial|ring|phone)\\s+(?:up\\s+)?(?:my\\s+)?(.+)$"
        )

        private val ORDINALS = mapOf(
            "1st" to 1, "first" to 1,
            "2nd" to 2, "second" to 2,
            "3rd" to 3, "third" to 3,
            "4th" to 4, "fourth" to 4,
            "5th" to 5, "fifth" to 5,
            "6th" to 6, "sixth" to 6
        )

        private val CARDINALS = mapOf(
            "one" to 1, "two" to 2, "three" to 3,
            "four" to 4, "five" to 5, "six" to 6
        )
    }
}
