package com.example.heyandroid.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heyandroid.R
import com.example.heyandroid.data.chat.Message
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.chat.MessageRole
import com.example.heyandroid.data.conversation.Conversation
import com.example.heyandroid.ui.components.AppBackground
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.components.AppCard
import com.example.heyandroid.ui.markdown.MarkdownText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Past conversations, newest first. */
@Composable
fun HistoryList(
    conversations: List<Conversation>,
    accent: Color,
    onOpen: (Conversation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (conversations.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(AppColors.SurfaceRaised),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_history),
                        contentDescription = null,
                        tint = AppColors.Accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Text(
                    text = "Every time you summon the assistant, the thread is kept here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        items(conversations, key = { it.id }) { conversation ->
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(conversation) },
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = conversation.title.ifBlank { "Untitled conversation" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stamp(conversation.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "${conversation.messageCount} messages",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }
        }
    }
}

/** One stored conversation, read-only, exactly as it happened. */
@Composable
fun ConversationTranscript(
    conversation: Conversation,
    messageDao: MessageDao,
    accent: Color,
    onClose: () -> Unit
) {
    val messages by remember(conversation.id) {
        messageDao.observeMessages(conversation.id)
    }.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackground(accent = accent)

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                run {
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = conversation.title.ifBlank { "Conversation" },
                            style = MaterialTheme.typography.labelLarge,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stamp(conversation.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "This conversation is empty.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        Line(message, accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(message: Message, accent: Color) {
    val context = LocalContext.current
    when (message.role) {
        MessageRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppColors.AccentSoft)
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                )
                Timestamp(message.timestamp)
            }
        }

        MessageRole.ASSISTANT -> Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColors.SurfaceRaised)
                    .padding(horizontal = 13.dp, vertical = 10.dp)
            ) {
                MarkdownText(
                    text = message.content,
                    color = AppColors.TextPrimary,
                    accent = accent
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Timestamp(message.timestamp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { copy(context, message.content) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = "Copy reply",
                            tint = AppColors.TextTertiary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        MessageRole.STATUS -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            ToolActivity(message.content)
        }

        MessageRole.ERROR -> Row(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                )
                Timestamp(message.timestamp)
            }
        }
    }
}

/** Tool status is collapsed by default: one line, expandable details. */
@Composable
private fun ToolActivity(content: String) {
    val lines = content.lines().filter { it.isNotBlank() }
    var expanded by remember(content) { mutableStateOf(false) }
    val collapsible = lines.size > 1

    Column(
        modifier = Modifier
            .widthIn(max = 330.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceRaised)
            .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (expanded || !collapsible) {
                content
            } else {
                lines.first() + "  ·  tap to show"
            },
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Timestamp(timestamp: Long, modifier: Modifier = Modifier) {
    Text(
        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = AppColors.TextTertiary,
        modifier = modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp)
    )
}

private fun copy(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("HeyAndroid reply", text))
}

private fun stamp(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
