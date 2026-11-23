package com.example.heyandroid.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
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

/** Launches an external activity; a tool runs outside any activity, so ask for a task. */
private fun Context.openExternal(intent: Intent) =
    runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }.isSuccess

/**
 * Places a phone call. With the call permission the dialer starts dialing at once;
 * without it the number is filled into the dialer and the user taps the call key.
 *
 * Android has no intent for group or second-line calls, so only the first number
 * can be dialed; the tool says so plainly instead of pretending.
 */
@Singleton
class StartCallTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "start_call"

    override val description =
        "Start a phone call to a number. Use it when the user asks you to call " +
            "someone. A second or group call cannot be placed by an app on Android, so " +
            "tell the user that instead of promising it."

    override val parametersJson = stringParameters(
        required = mapOf("number" to "The phone number to call, digits with optional +"),
        optional = mapOf(
            "label" to "Who the number belongs to, e.g. \"Mom\"",
            "numbers" to "More numbers for a group call, comma separated"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val numbers = (args.optString("number") + "," + args.optString("numbers"))
            .split(',', ';')
            .map { clean(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (numbers.isEmpty()) return "No number was given, so no call was started."

        val first = numbers.first()
        val label = args.optString("label").trim()
        val canCall = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL)
            .setData(Uri.parse("tel:$first"))
        if (!context.openExternal(intent)) return "No dialer app could handle the call."

        val who = if (label.isBlank()) first else "$label ($first)"
        return if (numbers.size <= 1) {
            if (canCall) {
                "Calling $who."
            } else {
                "Opened the dialer for $who. Android needs the call permission for the " +
                    "app to dial itself; the user taps the call key."
            }
        } else {
            val rest = numbers.drop(1).joinToString(", ")
            "Started a call to $who. Android cannot place group calls from an app, so " +
                "$rest were not dialed - they can be called after this one ends."
        }
    }

    private fun clean(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val digits = trimmed.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        return digits.replace(" ", "").replace("-", "")
    }
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
        if (!context.openExternal(intent)) return "No calendar app is installed to open."

        val when_ = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
            .format(Date(begin))
        return "Opened the calendar with \"$title\" on $when_ - the user saves it there."
    }

    private fun parseBegin(value: String): Long? {
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
        "Set an alarm for a time of day, or start a countdown timer. Use it when the " +
            "user asks to be woken or to be alerted after a number of minutes. This is " +
            "the phone's clock app, not a memory reminder."

    override val parametersJson = stringParameters(
        required = mapOf(
            "type" to "\"alarm\" for a time of day, \"timer\" for a countdown"
        ),
        optional = mapOf(
            "hour" to "Alarm hour, 0-23, local time",
            "minute" to "Alarm minute, 0-59",
            "minutes" to "Timer length in minutes",
            "label" to "What the alarm or timer is for"
        )
    )

    override suspend fun run(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return "Invalid arguments: expected a JSON object"
        val type = args.optString("type").trim().lowercase()
        val label = args.optString("label").trim()

        return if (type == "timer") {
            val minutes = args.optString("minutes").trim().toIntOrNull()
                ?: return "A timer needs a length in minutes."
            val seconds = (minutes.coerceIn(1, 24 * 60)) * 60
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            if (!context.openExternal(intent)) return "No clock app is installed."
            "Started a $minutes minute timer" + if (label.isBlank()) "." else ": $label."
        } else {
            val hour = args.optString("hour").trim().toIntOrNull()
                ?: LocalTime.now().hour
            val minute = args.optString("minute").trim().toIntOrNull() ?: 0
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            if (!context.openExternal(intent)) return "No clock app is installed."
            val when_ = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            "Opened the clock with an alarm at $when_" +
                if (label.isBlank()) "." else ": $label."
        }
    }
}
