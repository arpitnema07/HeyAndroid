package com.example.heyandroid.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heyandroid.R
import com.example.heyandroid.data.capture.Capture
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.conversation.Conversation
import com.example.heyandroid.data.turn.TurnLogDao
import com.example.heyandroid.llm.InputMode
import com.example.heyandroid.llm.LlmClientFactory
import com.example.heyandroid.llm.LlmMessage
import com.example.heyandroid.llm.LlmResult
import com.example.heyandroid.llm.LlmSettingsStore
import com.example.heyandroid.llm.LlmProvider
import com.example.heyandroid.syncpad.SyncPadRepository
import com.example.heyandroid.syncpad.SyncPadSettings
import com.example.heyandroid.tools.CustomToolStore
import com.example.heyandroid.ui.components.AppBackground
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.components.AppCard
import com.example.heyandroid.ui.history.ConversationTranscript
import com.example.heyandroid.ui.history.HistoryList
import com.example.heyandroid.ui.log.LogScreen
import com.example.heyandroid.widget.BriefWorker
import com.example.heyandroid.widget.SyncPadSyncWorker
import com.example.heyandroid.widget.SyncPadWidgetProvider
import kotlinx.coroutines.launch

/**
 * Every destination in the settings menu. Each one is a page of its own, so the
 * main screen stays a short list instead of one long scroll.
 */
enum class SettingsPage(val title: String, val subtitle: String) {
    History("History", "Every conversation, newest first"),
    Log("Log", "Model turns, errors and raw captures"),
    Assistant("Assistant", "Provider, API key and the model that answers"),
    Search("Web search", "Tavily key for live information"),
    Connectors("Connectors", "MCP servers the assistant can call"),
    Tools("Custom tools", "HTTP actions you add yourself"),
    SyncPad("SyncPad", "Your clipboard, here and on the widget"),
    Brief("Home-screen brief", "The memory image on your home screen"),
}

/**
 * Settings is a menu now: one short list of the areas above, each opening its
 * own page. Every value is still stored on this device only.
 */
@Composable
fun SettingsScreen(
    conversations: List<Conversation>,
    messageDao: MessageDao,
    turnLogDao: TurnLogDao,
    captures: List<Capture>,
    embedded: Boolean = false,
    initialPage: SettingsPage? = null,
    onClose: () -> Unit = {}
) {
    val accent = MaterialTheme.colorScheme.primary
    var page by remember { mutableStateOf(initialPage) }

    BackHandler(enabled = page == null) { onClose() }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackground(accent = accent)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!embedded) {
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                SettingsGroupLabel("Your content")
                SettingsMenuRow(
                    icon = R.drawable.ic_history,
                    page = SettingsPage.History,
                    accent = accent,
                    onClick = { page = SettingsPage.History }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsMenuRow(
                    icon = R.drawable.ic_kind_progress,
                    page = SettingsPage.Log,
                    accent = accent,
                    onClick = { page = SettingsPage.Log }
                )

                Spacer(modifier = Modifier.height(22.dp))

                SettingsGroupLabel("Assistant")
                SettingsMenuRow(
                    icon = R.drawable.ic_kind_note,
                    page = SettingsPage.Assistant,
                    accent = accent,
                    onClick = { page = SettingsPage.Assistant }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsMenuRow(
                    icon = R.drawable.ic_search,
                    page = SettingsPage.Search,
                    accent = accent,
                    onClick = { page = SettingsPage.Search }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsMenuRow(
                    icon = R.drawable.ic_attach,
                    page = SettingsPage.Connectors,
                    accent = accent,
                    onClick = { page = SettingsPage.Connectors }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsMenuRow(
                    icon = R.drawable.ic_kind_item,
                    page = SettingsPage.Tools,
                    accent = accent,
                    onClick = { page = SettingsPage.Tools }
                )

                Spacer(modifier = Modifier.height(22.dp))

                SettingsGroupLabel("On this phone")
                SettingsMenuRow(
                    icon = R.drawable.ic_copy,
                    page = SettingsPage.SyncPad,
                    accent = accent,
                    onClick = { page = SettingsPage.SyncPad }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsMenuRow(
                    icon = R.drawable.ic_document,
                    page = SettingsPage.Brief,
                    accent = accent,
                    onClick = { page = SettingsPage.Brief }
                )

                Spacer(modifier = Modifier.height(28.dp))
            }
        }

        // Drawn last, so it covers the menu while a page is open.
        page?.let { open ->
            SettingsSectionPage(
                page = open,
                conversations = conversations,
                messageDao = messageDao,
                turnLogDao = turnLogDao,
                captures = captures,
                onBack = { page = null }
            )
        }
    }
}

@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextTertiary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** One tappable line in the settings menu. */
@Composable
private fun SettingsMenuRow(
    icon: Int,
    page: SettingsPage,
    accent: Color,
    onClick: () -> Unit
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(AppColors.AccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = page.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(180f)
            )
        }
    }
}

/** A settings page: back arrow, title, subtitle, then the page's own content. */
@Composable
private fun SettingsPageScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackground(accent = accent)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }

            content()
        }
    }
}

@Composable
private fun SettingsSectionPage(
    page: SettingsPage,
    conversations: List<Conversation>,
    messageDao: MessageDao,
    turnLogDao: TurnLogDao,
    captures: List<Capture>,
    onBack: () -> Unit
) {
    when (page) {
        SettingsPage.History -> HistoryPage(
            conversations = conversations,
            messageDao = messageDao,
            onBack = onBack
        )

        SettingsPage.Log -> SettingsPageScaffold(
            title = page.title,
            subtitle = page.subtitle,
            onBack = onBack
        ) {
            LogScreen(
                turnLogDao = turnLogDao,
                messageDao = messageDao,
                captures = captures,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp)
            )
        }

        SettingsPage.Assistant -> AssistantPage(onBack = onBack)
        SettingsPage.Search -> SearchPage(onBack = onBack)
        SettingsPage.Connectors -> ConnectorsPage(onBack = onBack)
        SettingsPage.Tools -> ToolsPage(onBack = onBack)
        SettingsPage.SyncPad -> SyncPadPage(onBack = onBack)
        SettingsPage.Brief -> BriefPage(onBack = onBack)
    }
}

@Composable
private fun HistoryPage(
    conversations: List<Conversation>,
    messageDao: MessageDao,
    onBack: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    var openConversation by remember { mutableStateOf<Conversation?>(null) }

    BackHandler(enabled = openConversation == null) { onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsPageScaffold(
            title = SettingsPage.History.title,
            subtitle = SettingsPage.History.subtitle,
            onBack = onBack
        ) {
            HistoryList(
                conversations = conversations,
                accent = accent,
                onOpen = { openConversation = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp)
            )
        }

        openConversation?.let { conversation ->
            ConversationTranscript(
                conversation = conversation,
                messageDao = messageDao,
                accent = accent,
                onClose = { openConversation = null }
            )
        }
    }
}

/** Provider, key, model and how the assistant opens. */
@Composable
private fun AssistantPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LlmSettingsStore.getInstance(context) }
    val config by store.config.collectAsState()
    val inputMode by store.inputMode.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = MaterialTheme.colorScheme.primary

    val provider = config.provider
    var apiKeyDraft by remember(provider) { mutableStateOf(config.apiKey) }
    var modelDraft by remember(provider) { mutableStateOf(config.model) }
    var revealKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }

    fun persist() {
        store.setApiKey(provider, apiKeyDraft)
        store.setModel(provider, modelDraft)
        modelDraft = modelDraft.trim().ifBlank { provider.defaultModel }
        savedFlash = true
        status = null
    }

    fun testConnection() {
        val key = apiKeyDraft.trim()
        if (key.isBlank()) {
            statusOk = false
            status = "Add an API key first"
            return
        }
        persist()
        keyboard?.hide()
        busy = true
        status = null
        scope.launch {
            val client = LlmClientFactory.create(provider, key, modelDraft)
            when (val result = client.complete(
                messages = listOf(LlmMessage.user("Reply with the single word: ready")),
                maxTokens = 16
            )) {
                is LlmResult.Success -> {
                    statusOk = true
                    status = "Connected · ${result.text.take(40)}"
                }

                is LlmResult.ToolCalls -> {
                    statusOk = true
                    status = "Connected"
                }

                is LlmResult.Failure -> {
                    statusOk = false
                    status = result.message
                }
            }
            busy = false
        }
    }

    SettingsPageScaffold(
        title = SettingsPage.Assistant.title,
        subtitle = SettingsPage.Assistant.subtitle,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Provider",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary
            )
            Text(
                text = "Powers memory extraction, chat replies and the home-screen brief.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LlmProvider.entries.forEach { entry ->
                    ProviderChip(
                        label = entry.displayName,
                        selected = entry == provider,
                        accent = accent,
                        onClick = {
                            store.selectProvider(entry)
                            savedFlash = false
                            status = null
                        }
                    )
                }
                ProviderChip(
                    label = "More soon",
                    selected = false,
                    accent = accent,
                    enabled = false,
                    onClick = {}
                )
            }

            Text(
                text = provider.freeTierNote,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(top = 10.dp)
            )

            Spacer(modifier = Modifier.height(26.dp))

            Text(
                text = "When the assistant opens",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary
            )
            Text(
                text = if (inputMode == InputMode.VOICE) {
                    "Listening starts right away. Tap the field to type instead."
                } else {
                    "The keyboard is ready. Tap the microphone to dictate instead."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProviderChip(
                    label = "Voice",
                    selected = inputMode == InputMode.VOICE,
                    accent = accent,
                    onClick = { store.setInputMode(InputMode.VOICE) }
                )
                ProviderChip(
                    label = "Keyboard",
                    selected = inputMode == InputMode.KEYBOARD,
                    accent = accent,
                    onClick = { store.setInputMode(InputMode.KEYBOARD) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "API key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsField(
                        value = apiKeyDraft,
                        onValueChange = {
                            apiKeyDraft = it
                            savedFlash = false
                        },
                        placeholder = provider.keyHint,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AppColors.TextPrimary,
                            letterSpacing = 0.4.sp
                        ),
                        visualTransformation = if (revealKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardType = KeyboardType.Password,
                        cursorColor = accent,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (revealKey) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { revealKey = !revealKey }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Create a key at ${provider.keyUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://${provider.keyUrl}")
                                )
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Free models",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary
            )
            Text(
                text = "Only models included in the ${provider.displayName} free plan are listed.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                provider.freeModels.forEach { model ->
                    FreeModelRow(
                        label = model.label,
                        modelId = model.id,
                        note = model.note,
                        selected = model.id == modelDraft,
                        accent = accent,
                        onClick = {
                            modelDraft = model.id
                            store.setModel(provider, model.id)
                            savedFlash = false
                            status = null
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusOk) accent else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accent)
                        .clickable(enabled = !busy) { testConnection() }
                        .padding(horizontal = 20.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = if (busy) "Testing…" else "Test connection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AppColors.SurfaceRaised)
                        .clickable { persist() }
                        .padding(horizontal = 18.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = if (savedFlash) "Saved" else "Save",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.TextPrimary
                    )
                }
            }

            Text(
                text = "The key is stored privately on this phone and sent only to " +
                    "${provider.displayName}. Nothing is uploaded anywhere else.",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(top = 22.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** The Tavily key that lets the assistant search the web. */
@Composable
private fun SearchPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LlmSettingsStore.getInstance(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = MaterialTheme.colorScheme.primary

    val tavilyKey by store.tavilyKey.collectAsState()
    var tavilyDraft by remember { mutableStateOf(tavilyKey) }
    var revealTavily by remember { mutableStateOf(false) }

    SettingsPageScaffold(
        title = SettingsPage.Search.title,
        subtitle = SettingsPage.Search.subtitle,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (tavilyKey.isBlank()) {
                    "Off. Add a Tavily key and the assistant can search the web when " +
                        "you ask about something current."
                } else {
                    "On. The assistant searches the web through Tavily when a question " +
                        "needs live information."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsField(
                        value = tavilyDraft,
                        onValueChange = { tavilyDraft = it },
                        placeholder = "tvly-…",
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AppColors.TextPrimary,
                            letterSpacing = 0.4.sp
                        ),
                        visualTransformation = if (revealTavily) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardType = KeyboardType.Password,
                        cursorColor = accent,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (revealTavily) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { revealTavily = !revealTavily }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Create a free key at app.tavily.com",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://app.tavily.com"))
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AppColors.SurfaceRaised)
                        .clickable {
                            store.setTavilyKey(tavilyDraft)
                            tavilyDraft = tavilyDraft.trim()
                            keyboard?.hide()
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Save key",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.TextPrimary
                    )
                }

                if (tavilyKey.isNotBlank()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Turn off",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                store.setTavilyKey("")
                                tavilyDraft = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ConnectorsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LlmSettingsStore.getInstance(context) }
    val accent = MaterialTheme.colorScheme.primary

    SettingsPageScaffold(
        title = SettingsPage.Connectors.title,
        subtitle = SettingsPage.Connectors.subtitle,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            McpConnectorSection(store = store, accent = accent)
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ToolsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val customTools = remember { CustomToolStore.getInstance(context) }
    val accent = MaterialTheme.colorScheme.primary

    SettingsPageScaffold(
        title = SettingsPage.Tools.title,
        subtitle = SettingsPage.Tools.subtitle,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            CustomToolsSection(store = customTools, accent = accent)
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** The SyncPad domain and device key, plus connect/disconnect. */
@Composable
private fun SyncPadPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary

    val syncpad = remember { SyncPadSettings.getInstance(context) }
    val syncpadRepository = remember { SyncPadRepository.getInstance(context) }
    val syncpadDomain by syncpad.domain.collectAsState()
    val syncpadKey by syncpad.apiKey.collectAsState()
    var syncpadDomainDraft by remember(syncpadDomain) { mutableStateOf(syncpadDomain) }
    var syncpadKeyDraft by remember(syncpadKey) { mutableStateOf(syncpadKey) }
    var revealSyncpadKey by remember { mutableStateOf(false) }
    var syncpadStatus by remember { mutableStateOf<String?>(null) }
    var syncpadStatusOk by remember { mutableStateOf(false) }
    var syncpadBusy by remember { mutableStateOf(false) }

    fun saveSyncPad() {
        syncpad.save(syncpadDomainDraft, syncpadKeyDraft)
        syncpadDomainDraft = syncpad.domain.value
        syncpadKeyDraft = syncpad.apiKey.value
        syncpadStatusOk = true
        syncpadStatus = "Saved"
        SyncPadSyncWorker.enqueue(context)
        scope.launch { SyncPadWidgetProvider.updateAll(context) }
    }

    fun testSyncPad() {
        if (syncpadDomainDraft.isBlank() || syncpadKeyDraft.isBlank()) {
            syncpadStatusOk = false
            syncpadStatus = "Add the SyncPad domain and an API key first"
            return
        }
        saveSyncPad()
        keyboard?.hide()
        syncpadBusy = true
        syncpadStatus = null
        scope.launch {
            syncpadRepository.ping().fold(
                onSuccess = {
                    syncpadStatusOk = true
                    syncpadStatus = "Connected · $it"
                },
                onFailure = {
                    syncpadStatusOk = false
                    syncpadStatus = it.message ?: "Could not reach SyncPad"
                }
            )
            syncpadBusy = false
        }
    }

    SettingsPageScaffold(
        title = SettingsPage.SyncPad.title,
        subtitle = SettingsPage.SyncPad.subtitle,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (syncpadDomain.isNotBlank() && syncpadKey.isNotBlank()) {
                    "Connected to ${syncpadDomain.removePrefix("https://")}. The SyncPad " +
                        "tab and the home-screen widget share your clipboard."
                } else {
                    "Link your SyncPad account to read and add clips. Create a key in " +
                        "SyncPad → Settings → Device keys."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column {
                    SettingsField(
                        value = syncpadDomainDraft,
                        onValueChange = {
                            syncpadDomainDraft = it
                            syncpadStatus = null
                        },
                        placeholder = "syncpad.vercel.app",
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = AppColors.TextPrimary
                        ),
                        keyboardType = KeyboardType.Uri,
                        cursorColor = accent,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsField(
                            value = syncpadKeyDraft,
                            onValueChange = {
                                syncpadKeyDraft = it
                                syncpadStatus = null
                            },
                            placeholder = "spk_…",
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = AppColors.TextPrimary,
                                letterSpacing = 0.4.sp
                            ),
                            visualTransformation = if (revealSyncpadKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardType = KeyboardType.Password,
                            cursorColor = accent,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (revealSyncpadKey) "Hide" else "Show",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { revealSyncpadKey = !revealSyncpadKey }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Text(
                text = "Domain is the site you open SyncPad on — no trailing slash needed.",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(top = 8.dp)
            )

            syncpadStatus?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncpadStatusOk) accent else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accent)
                        .clickable(enabled = !syncpadBusy) { testSyncPad() }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = if (syncpadBusy) "Testing…" else "Test connection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AppColors.SurfaceRaised)
                        .clickable { saveSyncPad() }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Save",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.TextPrimary
                    )
                }

                if (syncpadDomain.isNotBlank() || syncpadKey.isNotBlank()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Disconnect",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                syncpad.clear()
                                syncpadDomainDraft = ""
                                syncpadKeyDraft = ""
                                syncpadStatus = "Disconnected"
                                syncpadStatusOk = true
                                SyncPadSyncWorker.cancel(context)
                                scope.launch { SyncPadWidgetProvider.updateAll(context) }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/** The home-screen brief widget, rebuilt on demand. */
@Composable
private fun BriefPage(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsPageScaffold(
        title = SettingsPage.Brief.title,
        subtitle = SettingsPage.Brief.subtitle,
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Renders your memories into the widget image. Only tapping " +
                    "refresh spends a model request.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )

            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(CircleShape)
                    .background(AppColors.SurfaceRaised)
                    .clickable { BriefWorker.enqueue(context) }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Rebuild the brief",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ProviderChip(
    label: String,
    selected: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val fill = if (selected) AppColors.AccentSoft else AppColors.SurfaceRaised
    val textColor = when {
        !enabled -> AppColors.TextTertiary
        selected -> AppColors.TextPrimary
        else -> AppColors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor
        )
    }
}

@Composable
private fun FreeModelRow(
    label: String,
    modelId: String,
    note: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) accent else AppColors.SurfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    cursorColor: Color,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = textStyle,
        cursorBrush = SolidColor(cursorColor),
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = textStyle.copy(color = AppColors.TextTertiary)
                )
            }
            inner()
        }
    )
}
