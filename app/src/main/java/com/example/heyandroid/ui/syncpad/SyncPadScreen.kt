package com.example.heyandroid.ui.syncpad

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heyandroid.R
import com.example.heyandroid.syncpad.LinkPreview
import com.example.heyandroid.syncpad.LinkPreviewFetcher
import com.example.heyandroid.syncpad.SyncPadClip
import com.example.heyandroid.syncpad.SyncPadRepository
import com.example.heyandroid.ui.components.AppCard
import com.example.heyandroid.ui.components.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the open screen checks for clips made elsewhere. */
private const val POLL_MS = 5_000L

private data class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * The SyncPad tab: the same personal clipboard as the web app's /app page.
 *
 * While this screen is composed it polls every few seconds, so a clip pasted on
 * a laptop shows up here - and on the home-screen widget - without a manual
 * refresh. The refresh button is there for when the user does not want to wait.
 */
@Composable
fun SyncPadScreen(
    accent: Color,
    modifier: Modifier = Modifier,
    pasteClipboard: Boolean = false
) {
    val context = LocalContext.current
    val repository = remember { SyncPadRepository.getInstance(context) }
    val settings = repository.settings
    val domain by settings.domain.collectAsState()
    val apiKey by settings.apiKey.collectAsState()
    val configured = domain.isNotBlank() && apiKey.isNotBlank()

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var clips by remember { mutableStateOf(repository.cached()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncedAt by remember { mutableStateOf(0L) }

    fun applyRefresh() {
        scope.launch {
            refreshing = true
            repository.refresh().fold(
                onSuccess = {
                    clips = it
                    error = null
                    syncedAt = System.currentTimeMillis()
                },
                onFailure = { error = it.message ?: "Could not reach SyncPad." }
            )
            refreshing = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val picked = withContext(Dispatchers.IO) { readPickedFile(context, uri) }
            if (picked == null) {
                error = "Couldn't read that file."
            } else {
                repository.upload(picked.name, picked.mime, picked.bytes).fold(
                    onSuccess = {
                        clips = listOf(it) + clips
                        error = null
                        syncedAt = System.currentTimeMillis()
                    },
                    onFailure = { error = it.message ?: "Upload failed." }
                )
            }
            busy = false
        }
    }

    // The widget's paste button opens this screen with a request to put the
    // system clipboard into the composer. Reading the clipboard needs the app on
    // screen, which is exactly why the widget cannot do it on its own.
    LaunchedEffect(pasteClipboard, configured) {
        if (!pasteClipboard || !configured) return@LaunchedEffect
        val text = clipboard.getText()?.text.orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        } else {
            draft = text
        }
    }

    LaunchedEffect(configured) {
        if (!configured) {
            clips = emptyList()
            return@LaunchedEffect
        }
        clips = repository.cached()
        while (true) {
            repository.refresh().fold(
                onSuccess = {
                    clips = it
                    error = null
                    syncedAt = System.currentTimeMillis()
                },
                onFailure = { if (clips.isEmpty()) error = it.message }
            )
            delay(POLL_MS)
        }
    }

    fun addDraft() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        scope.launch {
            busy = true
            repository.addText(text).fold(
                onSuccess = {
                    clips = listOf(it) + clips
                    draft = ""
                    error = null
                    syncedAt = System.currentTimeMillis()
                },
                onFailure = { error = it.message ?: "Could not add that clip." }
            )
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "SyncPad",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 26.sp,
                            letterSpacing = (-0.6).sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = ".",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
                Text(
                    text = when {
                        !configured -> "Not connected — add a domain and key in Settings"
                        syncedAt > 0 -> "Synced ${
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(syncedAt))
                        } · live"
                        else -> "Connected to ${domain.removePrefix("https://").removePrefix("http://")}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            IconButton(onClick = { applyRefresh() }, enabled = configured && !refreshing) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = "Refresh clips",
                    tint = if (refreshing) AppColors.TextTertiary else accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (!configured) {
            NotConnectedCard()
            return@Column
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            busy = busy,
            accent = accent,
            onAdd = { addDraft() },
            onAttach = { picker.launch("*/*") },
            onPaste = {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                } else {
                    draft = text
                }
            }
        )

        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (clips.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(AppColors.SurfaceRaised),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = "Nothing here yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                    Text(
                        text = "Paste or attach something above. It lands on\nevery SyncPad device at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipCard(
                        clip = clip,
                        accent = accent,
                        repository = repository,
                        onCopy = {
                            val text = clip.content
                            if (text.isNullOrBlank()) {
                                Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
                            } else {
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDownload = {
                            runCatching { repository.download(context, clip) }
                                .onFailure {
                                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT)
                                        .show()
                                }
                        },
                        onOpenUrl = {
                            clip.content?.let { url ->
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        "No app can open that link",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                repository.delete(clip.id).fold(
                                    onSuccess = { clips = clips.filterNot { it.id == clip.id } },
                                    onFailure = { error = it.message ?: "Could not delete that clip." }
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotConnectedCard() {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(18.dp)
    ) {
        Text(
            text = "Connect your SyncPad",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary
        )
        Text(
            text = "In Settings, add your SyncPad domain and an API key. Create the key " +
                "in SyncPad → Settings → Device keys, then paste it here.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    accent: Color,
    onAdd: () -> Unit,
    onAttach: () -> Unit,
    onPaste: () -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp, max = 170.dp)
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = "Paste or type anything — text, a link, a code snippet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextTertiary
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = AppColors.TextPrimary,
                    lineHeight = 21.sp
                ),
                cursorBrush = SolidColor(accent)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ComposerAction(
                icon = R.drawable.ic_attach,
                label = "Attach",
                tint = AppColors.TextSecondary,
                enabled = !busy,
                onClick = onAttach
            )
            ComposerAction(
                icon = R.drawable.ic_copy,
                label = "Paste",
                tint = AppColors.TextSecondary,
                enabled = !busy,
                onClick = onPaste
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (busy || draft.isBlank()) AppColors.SurfaceRaised else accent)
                    .clickable(enabled = !busy && draft.isNotBlank(), onClick = onAdd)
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send),
                        contentDescription = null,
                        tint = if (busy || draft.isBlank()) {
                            AppColors.TextTertiary
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = if (busy) "Saving…" else "Add",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (busy || draft.isBlank()) {
                            AppColors.TextTertiary
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerAction(
    icon: Int,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) tint else AppColors.TextTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) tint else AppColors.TextTertiary
        )
    }
}

@Composable
private fun ClipCard(
    clip: SyncPadClip,
    accent: Color,
    repository: SyncPadRepository,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onOpenUrl: () -> Unit,
    onDelete: () -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeChip(clip.type)
            if (clip.isPinned) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "pinned",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
                    .format(Date(clip.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            clip.type == "image" && clip.hasFile -> ClipImage(clip, repository, accent)

            clip.type == "file" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.SurfaceSunken),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_document),
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = clip.fileName ?: "File",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            clip.fileSize?.let { append(SyncPadClip.formatSize(it)) }
                            clip.mimeType?.let {
                                if (isNotEmpty()) append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }

            clip.type == "code" -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.SurfaceSunken)
                    .padding(12.dp)
            ) {
                Text(
                    text = clip.content.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    ),
                    color = AppColors.TextPrimary,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis
                )
            }

            clip.type == "url" -> LinkPreviewCard(
                clip = clip,
                accent = accent,
                onOpenUrl = onOpenUrl
            )

            else -> Text(
                text = clip.content.orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = AppColors.TextPrimary,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (clip.isText) {
                CardAction(icon = R.drawable.ic_copy, label = "Copy", onClick = onCopy)
            }
            if (clip.hasFile) {
                CardAction(icon = R.drawable.ic_document, label = "Download", onClick = onDownload)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "Delete clip",
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CardAction(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextSecondary
        )
    }
}

/**
 * A link clip, shown the way the web app shows it: the page's own title, summary
 * and preview image. The host is known from the URL itself, so something is on
 * screen immediately while the page is fetched; a page without Open Graph tags
 * falls back to its `<title>`, then to the URL.
 */
@Composable
private fun LinkPreviewCard(clip: SyncPadClip, accent: Color, onOpenUrl: () -> Unit) {
    val url = clip.content.orEmpty()
    val preview by produceState<LinkPreview?>(initialValue = null, key1 = clip.id) {
        value = LinkPreviewFetcher.preview(url).getOrNull()
    }

    val title = preview?.title ?: clip.title ?: LinkPreviewFetcher.hostOf(url)
    val image = preview?.image

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceSunken)
            .clickable(onClick = onOpenUrl)
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
        }

        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            preview?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = AppColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }

            Text(
                text = LinkPreviewFetcher.hostOf(url),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

/** Fetches a thumbnail for an image clip; the bytes come through the API key. */
@Composable
private fun ClipImage(clip: SyncPadClip, repository: SyncPadRepository, accent: Color) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = clip.id) {
        value = withContext(Dispatchers.IO) {
            repository.downloadBytes(clip.id).getOrNull()?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceSunken)
            .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = clip.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_gallery),
                contentDescription = "Loading image",
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TypeChip(type: String) {
    val (label, tint) = when (type) {
        "url" -> "LINK" to AppColors.Accent
        "code" -> "CODE" to AppColors.Sky
        "image" -> "IMAGE" to AppColors.Mint
        "file" -> "FILE" to AppColors.Amber
        else -> "TEXT" to AppColors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}

/** Display name, MIME type and bytes for a picked document or image. */
private fun readPickedFile(context: Context, uri: Uri): PickedFile? {
    val resolver = context.contentResolver
    var name = "upload.bin"

    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index) ?: name
        }
    }

    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
        .getOrNull()
        ?: return null

    return PickedFile(
        name = name,
        mime = resolver.getType(uri) ?: "application/octet-stream",
        bytes = bytes
    )
}
