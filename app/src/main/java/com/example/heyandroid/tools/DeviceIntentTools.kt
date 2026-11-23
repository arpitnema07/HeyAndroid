package com.example.heyandroid.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Tools that hand the user's request to another app on the phone: the dialer, the
 * calendar editor and the clock. They open the system app with the details filled
 * in, so the user always sees and confirms the action - nothing is placed or saved
 * silently behind their back.
 */

/**
 * Launches an external activity; a tool runs outside any activity, so ask for a task.
 * Returns null when the app opened, or the reason it could not - a missing
 * permission (clock apps want SET_ALARM), no matching app, and so on.
 */
private fun Context.openExternal(intent: Intent): String? =
    runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        null
    }.getOrElse { error ->
        Log.w(TAG, "could not start ${intent.action}", error)
        error.message?.take(180) ?: error.javaClass.simpleName
    }

private const val TAG = "DeviceIntent"

/**
 * Dials a number from anywhere in the app, the contact picker included. With the
 * call permission the call starts at once; without it the dialer opens prefilled.
 * Returns a short line describing what happened.
 */
fun placeCall(context: Context, number: String, label: String = ""): String {
    val canCall = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED

    val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL)
        .setData(Uri.parse("tel:$number"))
    context.openExternal(intent)?.let { return "The dialer could not be opened: $it" }

    val who = if (label.isBlank()) number else "$label ($number)"
    return if (canCall) {
        "Calling $who."
    } else {
        "Opened the dialer for $who. Android needs the call permission for the " +
            "app to dial itself; the user taps the call key."
    }
}

/** One phone number found in the contacts, with a score for how well it matched. */
internal data class ContactMatch(val name: String, val number: String, val score: Int)

/**
 * Looks a person up in the phone's contacts by name. Reading contacts needs the
 * READ_CONTACTS permission; without it nothing is read and the caller says so
 * rather than pretending a contact does not exist.
 */
internal object ContactLookup {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Matches on name and on number; best matches first, one row per number. */
    fun search(context: Context, query: String, limit: Int = 6): List<ContactMatch> {
        val wanted = query.trim()
        if (wanted.isBlank() || !hasPermission(context)) return emptyList()

        val nameColumn = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val numberColumn = ContactsContract.CommonDataKinds.Phone.NUMBER
        val projection = arrayOf(nameColumn, numberColumn)
        val selection = "$nameColumn LIKE ? OR $numberColumn LIKE ?"
        val selectionArgs = arrayOf("%$wanted%", "%$wanted%")

        val matches = linkedMapOf<String, ContactMatch>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "$nameColumn ASC"
            )?.use { cursor ->
                val names = cursor.getColumnIndexOrThrow(nameColumn)
                val numbers = cursor.getColumnIndexOrThrow(numberColumn)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(names)?.trim().orEmpty()
                    val number = cursor.getString(numbers)?.trim().orEmpty()
                    if (name.isBlank() || number.isBlank()) continue
                    val key = "$name|${number.filter { it.isDigit() }}"
                    val match = ContactMatch(name, number, score(name, wanted))
                    val existing = matches[key]
                    if (existing == null || match.score < existing.score) matches[key] = match
                }
            }
        }.onFailure { Log.w(TAG, "contact lookup failed", it) }

        return matches.values
            .sortedWith(compareBy({ it.score }, { it.name }))
            .take(limit)
    }

    /** Exact name beats a name that starts with the words, a word match, then any match. */
    private fun score(name: String, query: String): Int {
        val n = name.lowercase(Locale.getDefault())
        val q = query.lowercase(Locale.getDefault())
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            n.split(' ').any { it.startsWith(q) } -> 2
            n.contains(q) -> 3
            else -> 4
        }
    }
}

/**
 * Places a phone call. With the call permission the dialer starts dialing at once;
 * without it the number is filled into the dialer and the user taps the call key.
 *
 * The caller may give a contact's name instead of a number; the name is looked up
 * in the contacts and the matching number is dialed. Android has no intent for
 * group or second-line calls, so only the first number can be dialed; the tool
 * says so plainly instead of pretending.
 */
@Singleton
class StartCallTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callChoices: CallChoiceStore
) : Tool {

    override val name = "start_call"

    override val description =
        "Start a phone call to a name or a number. Use it when the user asks you to " +
            "call someone: put the contact's name in \"number\" (e.g. \"Mom\") and it " +
            "is looked up in the phone's contacts. A second or group call cannot be " +
            "placed by an app on Android, so tell the user that instead of promising it."

    override val parametersJson = stringParameters(
        required = mapOf(
            "number" to "The phone number to call, or the contact's name such as \"Mom\""
        ),
        optional = mapOf(
            "label" to "Who the number belongs to, e.g. \"Mom\"",
            "numbers" to "More numbers for a group call, comma separated"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val raw = args.optString("number").trim()
        val label = args.optString("label").trim()
        // A new call request replaces any options left from the previous one.
        callChoices.clear()

        val numbers = (raw + "," + args.optString("numbers"))
            .split(',', ';')
            .map { clean(it) }
            .filter { it.isNotBlank() }
            .distinct()

        // Nothing that looks like a number: treat what was said as a contact name.
        if (numbers.isEmpty()) {
            val name = listOf(raw, label).firstOrNull { it.isNotBlank() }
                ?: return "No number or contact name was given, so no call was started."
            return callByName(name)
        }

        if (numbers.size > 1) {
            val rest = numbers.drop(1).joinToString(", ")
            return confirm(numbers.first(), label) +
                " Android cannot place group calls from an app, so $rest were not " +
                "dialed - they can be called after this one ends."
        }
        return confirm(numbers.first(), label)
    }

    /** Resolves a name through the contacts, then dials the best match. */
    private fun callByName(name: String): String {
        if (!ContactLookup.hasPermission(context)) {
            return "\"$name\" is not a number and the assistant has no contacts " +
                "permission, so the contact could not be found and no call was " +
                "started. Say that plainly; do not guess a number."
        }

        val reply = callChoices.resolve(name)
            ?: return "No contact matches \"$name\", so no call was started. Ask the " +
                "user for the number."
        return if (reply.startsWith("More than one")) {
            "$reply The rows are already on screen, so do not repeat the numbers as a list."
        } else {
            reply
        }
    }

    /**
     * Hands the call to the confirmation countdown instead of dialing at once, so
     * the name and number are on screen with a Cancel button for a few seconds.
     */
    private fun confirm(number: String, label: String): String {
        callChoices.confirmCall(label.ifBlank { number }, number)
        val who = if (label.isBlank()) number else "$label ($number)"
        return "A call to $who starts in ${CallChoiceStore.SECONDS} seconds unless the " +
            "user cancels; the name, the number and a Cancel button are on screen. Say " +
            "that the call is about to start - do not claim it has already started."
    }

    private fun clean(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val digits = trimmed.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        return digits.replace(" ", "").replace("-", "")
    }
}

/**
 * Finds a person's phone numbers so the assistant can answer "what is Mom's
 * number" or pick the right contact before calling.
 */
@Singleton
class SearchContactsTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callChoices: CallChoiceStore
) : Tool {

    override val name = "search_contacts"

    override val description =
        "Look up a person's phone numbers in the phone's contacts by name. Use it " +
            "when the user asks for someone's number, or before calling someone when " +
            "the name may match more than one contact."

    override val parametersJson = stringParameters(
        required = mapOf("name" to "Part of the person's name, e.g. \"Mom\""),
        optional = mapOf("limit" to "How many matches to return, 1-10 (default 5)")
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val name = args.optString("name").trim().ifBlank { args.optString("query").trim() }
        if (name.isBlank()) return "No name was given, so no contact was searched."
        if (!ContactLookup.hasPermission(context)) {
            return "The assistant has no contacts permission, so the contacts could " +
                "not be searched. Say that instead of guessing a number."
        }

        val limit = args.optString("limit").trim().toIntOrNull()?.coerceIn(1, 10) ?: 5
        val matches = ContactLookup.search(context, name, limit)
        if (matches.isEmpty()) return "No contact matches \"$name\"."
        val found = matches.joinToString("; ") { "${it.name}: ${it.number}" }
        if (matches.size == 1) return "Found one contact: $found"

        // Several people (or several numbers for one name): the same tappable rows
        // the call tool offers, so the user can pick instead of reading a list.
        val options = matches.map { CallChoice(it.name, it.number) }.distinctBy { it.number }
        callChoices.offer(options)
        return "Contacts matching \"$name\": $found. They are already on screen as " +
            "tappable rows and the user can also answer with a number; ask which one " +
            "to call instead of repeating the list."
    }
}

/** Parses "YYYY-MM-DDTHH:mm" (or a bare date, taken as 09:00) in the local zone. */
private fun parseLocalMoment(value: String): Long? {
    if (value.isBlank()) return null
    value.toLongOrNull()?.let { if (it > 1_000_000_000_000L) return it }
    val zone = ZoneId.systemDefault()
    val iso = value.replace(' ', 'T')
    runCatching { LocalDateTime.parse(iso) }.getOrNull()
        ?.let { return it.atZone(zone).toInstant().toEpochMilli() }
    runCatching { LocalDate.parse(iso) }.getOrNull()
        ?.let { return it.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli() }
    return null
}

/**
 * Opens the calendar's own event editor with the event filled in. The user saves it,
 * so the app never needs calendar read or write permission.
 */
@Singleton
class CalendarEventTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "add_calendar_event"

    override val description =
        "Open the calendar with a new event filled in: title, start, duration and " +
            "place. Use it for meetings, appointments and anything the user wants on " +
            "their calendar. The user confirms the save in the calendar app."

    override val parametersJson = stringParameters(
        required = mapOf(
            "title" to "What the event is",
            "begin" to "Start, local time as YYYY-MM-DDTHH:mm"
        ),
        optional = mapOf(
            "minutes" to "How long it lasts, in minutes (default 60)",
            "location" to "Where it is",
            "notes" to "Details to add to the event",
            "allDay" to "\"yes\" for a whole-day event"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val title = args.optString("title").trim()
        if (title.isBlank()) return "No event title was given, so nothing opened."

        val begin = parseBegin(args.optString("begin").trim())
            ?: return "The start time was unclear. Give it as YYYY-MM-DDTHH:mm."
        val allDay = args.optString("allDay").equals("yes", true)
        val minutes = args.optString("minutes").trim().toIntOrNull()?.coerceIn(5, 1440) ?: 60
        val end = begin + minutes * 60_000L

        // The type is what calendar apps match on; without it some of them (Google
        // Calendar among them) report no activity for the insert.
        val intent = Intent(Intent.ACTION_INSERT)
            .setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
            .apply {
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            args.optString("location").trim().takeIf { it.isNotBlank() }
                ?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            args.optString("notes").trim().takeIf { it.isNotBlank() }
                ?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            if (allDay) {
                putExtra(CalendarContract.Events.ALL_DAY, true)
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }
        }
        context.openExternal(intent)?.let { return "The calendar could not be opened: $it" }

        val when_ = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
            .format(Date(begin))
        return "Opened the calendar with \"$title\" on $when_ - the user saves it there."
    }

    private fun parseBegin(value: String): Long? = parseLocalMoment(value)
}

/**
 * Sets an alarm or starts a timer in the clock app. The clock app opens with the
 * value filled in, so the user sees the alarm that was created.
 */
@Singleton
class ClockTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "set_alarm"

    override val description =
        "The tool for every reminder: give date when the user names a day or time, or " +
            "hour/minute for an alarm, or minutes for a timer. A date inside the next " +
            "seven days sets a clock alarm; a date further out adds a calendar event " +
            "instead. Also opens the stopwatch."

    override val parametersJson = stringParameters(
        required = mapOf(
            "type" to "\"alarm\" for a time of day, \"timer\" for a countdown, " +
                "\"stopwatch\" to open the clock's stopwatch"
        ),
        optional = mapOf(
            "hour" to "Alarm hour, 0-23, local time",
            "minute" to "Alarm minute, 0-59",
            "minutes" to "Timer length in minutes",
            "date" to "When the reminder is for, local time as YYYY-MM-DDTHH:mm",
            "label" to "What the alarm, timer or event is for"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val type = args.optString("type").trim().lowercase()
        val label = args.optString("label").trim()
        val dueAt = parseLocalMoment(args.optString("date").trim())
        Log.d(TAG, "set_alarm $argumentsJson")

        // A reminder is an alarm while it is close, and a calendar entry once it is
        // further off: an alarm holds a time of day, not a date.
        if (dueAt != null && type != "timer" && type != "stopwatch") {
            val weekAhead = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
            if (dueAt > weekAhead) return addCalendarReminder(dueAt, label)
            val at = java.time.Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault())
            return setAlarm(at.hour, at.minute, label)
        }

        return if (type == "stopwatch") {
            // Clock apps keep their stopwatch screens unexported, so no app can start
            // one; opening the clock is as far as Android allows.
            context.openExternal(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                ?.let { reason -> return "The clock app could not be opened: $reason" }
            "The stopwatch was NOT started: Android gives an app no way to start one. " +
                "Only the clock app was opened, on its alarm tab, and the user has to tap " +
                "Stopwatch at the bottom. Say that to the user instead of saying the " +
                "stopwatch is running."
        } else if (type == "timer") {
            val minutes = args.optString("minutes").trim().toIntOrNull()
                ?: return "A timer needs a length in minutes."
            val seconds = (minutes.coerceIn(1, 24 * 60)) * 60
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            context.openExternal(intent)?.let { reason ->
                return startClockFallback(reason)
            }
            "Started a $minutes minute timer" + if (label.isBlank()) "." else ": $label."
        } else {
            val hour = args.optString("hour").trim().toIntOrNull()
                ?: LocalTime.now().hour
            val minute = args.optString("minute").trim().toIntOrNull() ?: 0
            setAlarm(hour, minute, label)
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        context.openExternal(intent)?.let { reason ->
            return startClockFallback(reason)
        }
        val when_ = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        val what = if (label.isBlank()) "." else ": $label."
        return "Set an alarm at $when_$what The clock app is open, so it can be checked there."
    }

    /** Beyond a week, the reminder goes on the calendar instead of the clock. */
    private fun addCalendarReminder(begin: Long, label: String): String {
        val title = label.ifBlank { "Reminder" }
        val intent = Intent(Intent.ACTION_INSERT)
            .setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
            .apply {
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 30 * 60_000L)
                putExtra(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            }
        context.openExternal(intent)?.let { return "The calendar could not be opened: $it" }
        val when_ = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(begin))
        return "That is more than a week away, so the calendar is open with \"$title\" on " +
            "$when_ instead of an alarm - the user saves it there."
    }

    /**
     * A clock app that refused the alarm intent still has its own alarm screen; open
     * that instead of claiming no clock exists, and say why the time was not filled in.
     */
    private fun startClockFallback(reason: String): String {
        val shown = context.openExternal(Intent(AlarmClock.ACTION_SHOW_ALARMS)) == null
        return if (shown) {
            "The clock app would not take the time ($reason), so its alarm list is open; " +
                "the alarm has to be added there by hand."
        } else {
            "The clock app could not be opened ($reason)."
        }
    }
}
