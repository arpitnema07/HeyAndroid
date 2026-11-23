package com.example.heyandroid.ui.log

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heyandroid.data.capture.Capture
import com.example.heyandroid.data.capture.CaptureStatus
import com.example.heyandroid.data.chat.Message
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.turn.TurnLog
import com.example.heyandroid.data.turn.TurnLogDao
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.components.AppCard

/**
 * Diagnostics: one card per assistant turn (provider, model, steps, tools,
 * duration, outcome), the exact error lines, and the raw captures underneath.
 * This is the tab that explains "why did it answer that / why did it fail".
 */
@Composable
fun LogScreen(
    turnLogDao: TurnLogDao,
    messageDao: MessageDao,
    captures: List<Capture>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val turns by turnLogDao.observeRecent(200).collectAsState(initial = emptyList())
    val errors by messageDao.observeErrors(50).collectAsState(initial = emptyList())

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item(key = "summary") {
            Summary(turns = turns, errors = errors, accent = accent)
        }

        if (turns.isNotEmpty()) {
            item(key = "turn-header") { SectionHeader("TURN LOG") }
            items(turns, key = { "turn-${it.id}" }) { log ->
                TurnCard(log = log, accent = accent)
            }
        }

        if (errors.isNotEmpty()) {
            item(key = "error-header") { SectionHeader("ERRORS") }
            items(errors, key = { "error-${it.id}" }) { message ->
                ErrorCard(message, accent)
            }
        }

        if (captures.isNotEmpty()) {
            item(key = "capture-header") { SectionHeader("CAPTURES") }
            items(captures, key = { "capture-${it.id}" }) { capture ->
                CaptureCard(capture = capture, accent = accent)
            }
        }

        if (turns.isEmpty() && errors.isEmpty() && captures.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Nothing logged yet. Talk to the assistant and every turn lands here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 40.dp)
                )
            }
        }
    }
}

@Composable
private fun Summary(turns: List<TurnLog>, errors: List<Message>, accent: Color) {
    val failed = turns.count { it.outcome == "failed" }
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "${turns.size} turns · $failed failed · ${errors.size} error lines",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary
        )
        Text(
            text = "Each turn records the model, steps, tools and latency.",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
        color = AppColors.TextTertiary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun TurnCard(log: TurnLog, accent: Color) {
    val (label, tint) = when (log.outcome) {
        "answered" -> "answered" to accent
        "failed" -> "failed" to MaterialTheme.colorScheme.error
        "local" -> "local ack" to Color(0xFF7FC7FF)
        else -> log.outcome.ifBlank { "unknown" } to AppColors.TextSecondary
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = relative(log.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (log.durationMs > 0) "${log.durationMs} ms" else "—",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
        }

        Text(
            text = listOf(log.provider, log.model).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "no model recorded" },
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )

        val meta = buildString {
            append(log.steps).append(if (log.steps == 1) " step" else " steps")
            if (log.toolNames.isNotBlank()) append(" · ").append(log.toolNames)
        }
        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 2.dp)
        )

        log.error?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(message: Message, accent: Color) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextPrimary
        )
        Text(
            text = relative(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
private fun CaptureCard(capture: Capture, accent: Color) {
    val (label, tint) = when (capture.status) {
        CaptureStatus.CAPTURED -> "captured" to AppColors.TextSecondary
        CaptureStatus.PROCESSED -> "processed" to accent
        CaptureStatus.FAILED -> "failed" to MaterialTheme.colorScheme.error
    }
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(
            text = capture.text,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextPrimary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp)
        ) {
            Text(
                text = relative(capture.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}

private fun relative(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
