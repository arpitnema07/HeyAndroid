package com.example.heyandroid.ui.memory

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.heyandroid.R
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryCard
import com.example.heyandroid.data.memory.MemoryEvent
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.data.memory.MemoryStatus
import com.example.heyandroid.data.memory.attachedImages
import com.example.heyandroid.memory.MemoryFormatter
import com.example.heyandroid.memory.MemoryRepository
import com.example.heyandroid.ui.components.AppBackground
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.components.AppCard
import com.example.heyandroid.ui.markdown.MarkdownText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MemoryFilter(val label: String, val kind: MemoryKind?) {
    All("All", null),
    Tasks("Tasks", MemoryKind.TASK),
    Progress("Progress", MemoryKind.PROGRESS),
    Items("Items", MemoryKind.ITEM),
    Money("Money", MemoryKind.TRANSACTION),
    Notes("Notes", MemoryKind.NOTE)
}

/**
 * Everything the user has stored, searchable both by keywords and by meaning:
 * typing runs the same hybrid vector search the assistant uses.
 */
@Composable
fun MemoryScreen(
    repository: MemoryRepository,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val recent by repository.observeRecent(1000).collectAsState(initial = emptyList())
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MemoryCard>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Memory?>(null) }
    var filter by remember { mutableStateOf(MemoryFilter.All) }

    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        results = repository.search(text, 60)
        searching = false
    }

    // The keyboard used to survive into the detail screen and pop back up.
    LaunchedEffect(open?.id) {
        if (open != null) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    BackHandler(enabled = open != null) { open = null }

    val all = results?.map { it.memory } ?: recent
    val items = all.filter { filter.kind == null || it.kind == filter.kind }
    val dueSoon = if (query.isBlank() && (filter == MemoryFilter.All || filter == MemoryFilter.Tasks)) {
        val now = System.currentTimeMillis()
        items.filter {
            it.kind == MemoryKind.TASK && it.status == MemoryStatus.ACTIVE &&
                it.dueAt != null && it.dueAt in (now - 60_000)..(now + 7L * 24 * 60 * 60 * 1000)
        }.sortedBy { it.dueAt }
    } else {
        emptyList()
    }
    val rest = items.filterNot { memory -> dueSoon.any { it.id == memory.id } }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                accent = accent,
                hint = if (searching) "Searching memory…" else "Search everything you saved"
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MemoryFilter.entries.forEach { entry ->
                    FilterChip(
                        label = entry.label,
                        selected = entry == filter,
                        accent = accent,
                        onClick = { filter = entry }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (items.isEmpty()) {
                MemoryEmptyState(
                    query = query,
                    accent = accent,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    if (dueSoon.isNotEmpty()) {
                        item(key = "due-header") {
                            Text(
                                text = "DUE SOON",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.6.sp
                                ),
                                color = AppColors.TextTertiary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }
                        items(dueSoon, key = { "due-${it.id}" }) { memory ->
                            MemoryRow(
                                memory = memory,
                                accent = accent,
                                query = query,
                                onClick = { open = memory }
                            )
                        }
                        item(key = "all-header") {
                            Text(
                                text = "EVERYTHING",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.6.sp
                                ),
                                color = AppColors.TextTertiary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                    }
                    items(rest, key = { it.id }) { memory ->
                        MemoryRow(
                            memory = memory,
                            accent = accent,
                            query = query,
                            onClick = { open = memory }
                        )
                    }
                    if (recent.size >= 1000) {
                        item(key = "cap") {
                            Text(
                                text = "Showing the newest 1,000 memories.",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Detail covers the whole tab, not a row inside the parent layout.
        open?.let { memory ->
            MemoryDetail(
                memory = memory,
                repository = repository,
                accent = accent,
                onClose = { open = null },
                onUpdated = { open = it },
                onDelete = {
                    scope.launch {
                        // The private image copies belong to the row; remove them too.
                        memory.attachedImages.forEach { path ->
                            runCatching { java.io.File(path).delete() }
                        }
                        repository.delete(memory.id)
                        open = null
                    }
                }
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) AppColors.AccentSoft else Color.Transparent)
            .border(
                1.dp,
                if (selected) AppColors.Accent.copy(alpha = 0.55f) else AppColors.Border,
                CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AppColors.AccentText else AppColors.TextSecondary
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    accent: Color,
    hint: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.SurfaceSunken)
            .border(1.dp, AppColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        run {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(accent),
                singleLine = true,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = AppColors.TextTertiary
                            )
                        )
                    }
                    inner()
                }
            )
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Clear search",
                        tint = AppColors.TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(memory: Memory, accent: Color, query: String, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        fill = AppColors.Surface,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindChip(memory, accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = relativeDay(memory.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f)
                )
                if (memory.status == MemoryStatus.DONE) {
                    Text(
                        text = "done",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
            }

            Text(
                text = highlight(memory.title, query, accent),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )

            val detail = buildString {
                if (memory.progress.isNotBlank()) append("At ").append(memory.progress).append(" · ")
                if (memory.content.isNotBlank()) append(memory.content.replace('\n', ' ').take(160))
                MemoryFormatter.due(memory)?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (detail.isNotBlank()) {
                Text(
                    text = highlight(detail, query, accent),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                    color = AppColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun KindChip(memory: Memory, accent: Color) {
    val label = MemoryFormatter.kindLabel(memory).uppercase(Locale.getDefault())
    val tint = when (memory.kind) {
        MemoryKind.PROGRESS -> Color(0xFF2FD8C5)
        MemoryKind.TASK -> accent
        MemoryKind.ITEM -> Color(0xFF7FC7FF)
        MemoryKind.TRANSACTION -> Color(0xFF9BE38A)
        MemoryKind.NOTE -> AppColors.TextSecondary
        MemoryKind.FACT -> AppColors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = tint
        )
    }
}

@Composable
private fun MemoryEmptyState(query: String, accent: Color, modifier: Modifier = Modifier) {
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
                    painter = painterResource(
                        if (query.isBlank()) R.drawable.ic_mic else R.drawable.ic_search
                    ),
                    contentDescription = null,
                    tint = AppColors.Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = if (query.isBlank()) "Nothing here yet" else "No match for \"$query\"",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                text = if (query.isBlank()) {
                    "Summon the assistant and say what to remember."
                } else {
                    "Try different words, or save it first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** Full memory with its timeline and any attached image. */
@Composable
private fun MemoryDetail(
    memory: Memory,
    repository: MemoryRepository,
    accent: Color,
    onClose: () -> Unit,
    onUpdated: (Memory) -> Unit,
    onDelete: () -> Unit
) {
    var events by remember(memory.id) { mutableStateOf<List<MemoryEvent>>(emptyList()) }
    var editingNote by remember(memory.id) { mutableStateOf(false) }
    var draft by remember(memory.id, memory.content) { mutableStateOf(memory.content) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(memory.id) {
        events = repository.timeline(memory.id, 40)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Background)
            .border(1.dp, AppColors.Border, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = memory.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = MemoryFormatter.kindLabel(memory).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary
                        )
                    }
                    if (memory.kind == MemoryKind.NOTE) {
                        Text(
                            text = "Edit",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    draft = memory.content
                                    editingNote = true
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "Delete memory",
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindChip(memory, accent)
                    if (memory.status != MemoryStatus.ACTIVE) {
                        Spacer(modifier = Modifier.width(8.dp))
                        MetaPill(
                            label = memory.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            tint = AppColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = relativeDay(memory.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }

                Text(
                    text = memory.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 26.sp,
                        lineHeight = 32.sp
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 14.dp)
                )

                val trail = listOf(memory.collection, memory.subject)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (trail.isNotBlank()) {
                    Text(
                        text = trail,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Only things that actually happen at a time carry these chips.
                val chips = buildList {
                    if (memory.progress.isNotBlank()) {
                        add("At ${memory.progress}" to Color(0xFF2FD8C5))
                    }
                    if (memory.kind.needsDue) {
                        MemoryFormatter.due(memory)?.let { add(it to Color(0xFFF5B65C)) }
                    }
                }
                if (chips.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        chips.forEach { (label, tint) -> MetaPill(label = label, tint = tint) }
                    }
                }

                if (memory.content.isNotBlank()) {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        MarkdownText(
                            text = memory.content,
                            color = AppColors.TextPrimary,
                            accent = accent,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                lineHeight = 23.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (events.isNotEmpty()) {
                    Text(
                        text = "TIMELINE",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                        color = AppColors.TextTertiary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    events.forEach { event ->
                        Row(modifier = Modifier.padding(bottom = 10.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.Accent)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = event.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary
                                )
                                Text(
                                    text = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                                        .format(Date(event.at)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextTertiary
                                )
                            }
                        }
                    }
                }

                val images = memory.attachedImages
                if (images.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (images.size == 1) "ATTACHED IMAGE" else "ATTACHED IMAGES",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                        color = AppColors.TextTertiary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        images.forEach { path -> MemoryImage(path) }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }

        // A note is edited where it lives: one item per line, so adding, fixing or
        // deleting an item is a plain text edit instead of a second note.
        if (editingNote) {
            Dialog(onDismissRequest = { editingNote = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppColors.Surface)
                        .border(1.dp, AppColors.Border, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Edit note",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = "One item per line.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp, max = 380.dp)
                            .padding(top = 12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = AppColors.TextPrimary,
                                lineHeight = 21.sp
                            ),
                            cursorBrush = SolidColor(accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge,
                            color = AppColors.TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { editingNote = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val updated = memory.copy(
                                        content = draft.trim(),
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    scope.launch {
                                        repository.save(updated, "Edited note")
                                        onUpdated(updated)
                                        editingNote = false
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** A small tinted label, used for progress, due dates and status. */
@Composable
private fun MetaPill(label: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

/**
 * One attached image at a fixed size. The whole picture is fitted inside the tile,
 * so nothing is cropped or stretched - unused room stays as blank space.
 */
@Composable
private fun MemoryImage(path: String) {
    val bitmap = remember(path) {
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(168.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.SurfaceSunken)
            .border(1.dp, AppColors.Border, RoundedCornerShape(16.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = path.substringAfterLast('/'),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_gallery),
                contentDescription = "Image unavailable",
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Marks every occurrence of the search words, so a match is visible. */
private fun highlight(text: String, query: String, tint: Color): AnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val index = text.indexOf(needle, cursor, ignoreCase = true)
            if (index < 0) {
                append(text.substring(cursor))
                break
            }
            if (index > cursor) append(text.substring(cursor, index))
            withStyle(SpanStyle(background = tint.copy(alpha = 0.28f))) {
                append(text.substring(index, index + needle.length))
            }
            cursor = index + needle.length
        }
    }
}

private fun relativeDay(timestamp: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
