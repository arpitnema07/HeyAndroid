package com.example.heyandroid.ui.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heyandroid.R
import com.example.heyandroid.assistant.AssistantActivity
import com.example.heyandroid.brief.BriefPage
import com.example.heyandroid.data.capture.CaptureDao
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.conversation.ConversationDao
import com.example.heyandroid.data.turn.TurnLogDao
import com.example.heyandroid.memory.MemoryRepository
import com.example.heyandroid.ui.components.AppBackground
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.memory.MemoryScreen
import com.example.heyandroid.ui.settings.SettingsPage
import com.example.heyandroid.ui.settings.SettingsScreen
import com.example.heyandroid.ui.syncpad.SyncPadScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HomeTab(val label: String, val icon: Int) {
    Memory("Memory", R.drawable.ic_kind_fact),
    SyncPad("SyncPad", R.drawable.ic_copy),
    Brief("Brief", R.drawable.ic_document),
    Settings("Settings", R.drawable.ic_settings)
}

/**
 * Main screen: the memory store, SyncPad, the generated brief page and settings,
 * in a bottom bar. Conversation history and the capture log sit in the settings
 * menu.
 */
@Composable
fun HomeScreen(
    memoryRepository: MemoryRepository,
    conversationDao: ConversationDao,
    messageDao: MessageDao,
    captureDao: CaptureDao,
    turnLogDao: TurnLogDao,
    initialTab: String? = null,
    pasteClipboard: Boolean = false
) {
    val conversations by conversationDao.observeAll().collectAsState(initial = emptyList())
    val captures by captureDao.getAllCaptures().collectAsState(initial = emptyList())
    val context = LocalContext.current

    // History and Log moved into Settings, so the old deep link from the
    // assistant opens Settings with that page already on screen.
    val deepLinkPage = when (initialTab) {
        "history" -> SettingsPage.History
        "log" -> SettingsPage.Log
        else -> null
    }
    var tab by remember {
        mutableStateOf(
            when {
                initialTab == "syncpad" -> HomeTab.SyncPad
                deepLinkPage != null -> HomeTab.Settings
                else -> HomeTab.Memory
            }
        )
    }
    var settingsPage by remember { mutableStateOf(deepLinkPage) }

    // The pending page is a one-shot: once the user leaves Settings, tapping the
    // tab again starts at the settings list.
    LaunchedEffect(tab) {
        if (tab != HomeTab.Settings) settingsPage = null
    }

    fun openAssistant() {
        // NEW_TASK: the overlay has its own task affinity, so closing it never
        // takes the home screen with it.
        context.startActivity(
            Intent(context, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackground(accent = AppColors.Accent)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    HomeTab.Memory -> MemoryTab(
                        memoryRepository = memoryRepository,
                        onOpenAssistant = { openAssistant() }
                    )

                    HomeTab.Brief -> BriefPage(embedded = true)

                    HomeTab.SyncPad -> SyncPadScreen(
                        accent = AppColors.Accent,
                        pasteClipboard = pasteClipboard
                    )

                    HomeTab.Settings -> SettingsScreen(
                        conversations = conversations,
                        messageDao = messageDao,
                        turnLogDao = turnLogDao,
                        captures = captures,
                        embedded = true,
                        initialPage = settingsPage
                    )
                }
            }

            HomeTabBar(selected = tab, onSelect = { tab = it })
        }
    }
}

@Composable
private fun MemoryTab(
    memoryRepository: MemoryRepository,
    onOpenAssistant: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeHeader(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )

        AssistantCard(
            onClick = onOpenAssistant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(18.dp))

        MemoryScreen(
            repository = memoryRepository,
            accent = AppColors.Accent,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        )
    }
}

/** Bottom bar: icon over a small label, three equal tabs, hairline on top. */
@Composable
private fun HomeTabBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.BackgroundLift)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColors.Border)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeTab.entries.forEach { entry ->
                val active = entry == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(entry) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(entry.icon),
                        contentDescription = null,
                        tint = if (active) AppColors.Accent else AppColors.TextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) AppColors.Accent else AppColors.TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = remember {
                SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            }.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
            color = AppColors.TextTertiary
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "HeyAndroid",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    letterSpacing = (-0.8).sp
                ),
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary
            )
            Text(
                text = ".",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 30.sp,
                    lineHeight = 36.sp
                ),
                fontWeight = FontWeight.Bold,
                color = AppColors.Accent
            )
        }
        Text(
            text = "Your external memory",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** The one obvious action: open the assistant. */
@Composable
private fun AssistantCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B2140), Color(0xFF151A2C))
                )
            )
            .border(1.dp, Color(0xFF2C3559), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Ask HeyAndroid",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
            Text(
                text = "Voice, text or a screenshot",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(AppColors.Accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = "Open assistant",
                tint = Color(0xFF0B1230),
                modifier = Modifier.size(21.dp)
            )
        }
    }
}
