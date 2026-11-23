package com.example.heyandroid.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.widget.Toast
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.heyandroid.MainActivity
import com.example.heyandroid.R
import com.example.heyandroid.data.capture.CaptureProcessor
import com.example.heyandroid.data.chat.Message
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.chat.MessageRole
import com.example.heyandroid.data.conversation.ConversationDao
import com.example.heyandroid.data.memory.MemorySource
import com.example.heyandroid.data.turn.TurnLogDao
import com.example.heyandroid.llm.InputMode
import com.example.heyandroid.llm.LlmAgentFactory
import com.example.heyandroid.llm.LlmSettingsStore
import com.example.heyandroid.memory.MemoryEngine
import com.example.heyandroid.speech.AndroidSpeechToText
import com.example.heyandroid.speech.SpeechState
import com.example.heyandroid.speech.SpeechToText
import com.example.heyandroid.tools.CallChoice
import com.example.heyandroid.tools.CallChoiceStore
import com.example.heyandroid.tools.PendingCall
import com.example.heyandroid.tools.ToolRouter
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.markdown.MarkdownText
import com.example.heyandroid.ui.theme.HeyAndroidTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Assistant overlay: a compact bottom window with the conversation, live
 * dictation, the newest screenshot attached by default, and photo questions.
 *
 * Replies are text only. Once a reply lands, dictation restarts on its own so the
 * conversation can continue hands-free.
 */
@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {

    @Inject
    lateinit var captureProcessor: CaptureProcessor

    @Inject
    lateinit var agentFactory: LlmAgentFactory

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    lateinit var conversationDao: ConversationDao

    @Inject
    lateinit var memoryEngine: MemoryEngine

    @Inject
    lateinit var toolRouter: ToolRouter

    @Inject
    lateinit var turnLogDao: TurnLogDao

    @Inject
    lateinit var callChoices: CallChoiceStore

    @Inject
    lateinit var settings: LlmSettingsStore

    private lateinit var speechToText: SpeechToText
    private lateinit var session: ChatSession
    private var hasRecordPermission by mutableStateOf(false)
    private var capturedPhoto by mutableStateOf<String?>(null)

    /** A completed MediaProjection capture arrives from the service. */
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val path = intent?.getStringExtra(ScreenCaptureService.EXTRA_FILE) ?: return
            val file = java.io.File(path)
            lifecycleScope.launch {
                val dataUrl = ImageAttachment.toDataUrl(this@AssistantActivity, Uri.fromFile(file))
                if (dataUrl != null) capturedPhoto = dataUrl
                file.delete()
            }
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
            )
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        runCatching { screenCaptureLauncher.launch(manager.createScreenCaptureIntent()) }
    }

    /** Runs after the gallery permission is granted, to retry the same attach. */
    private var afterMediaPermission: (() -> Unit)? = null

    private val recordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasRecordPermission = granted
            if (granted) speechToText.startListening()
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterMediaPermission
            afterMediaPermission = null
            if (granted) action?.invoke()
        }

    /** Asked once, so "call Mom" can look the number up and dial it directly. */
    private val callPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /**
     * A permission dialog is an activity start into another app, and it throws
     * synchronously when the platform forbids it. Never let that reach main.
     */
    private fun requestRecordPermission() {
        runCatching { recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            .onFailure { hasRecordPermission = false }
    }

    private fun requestCallPermissions() {
        val missing = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        runCatching { callPermissionsLauncher.launch(missing.toTypedArray()) }
    }

    private fun requestMediaPermission(next: () -> Unit) {
        afterMediaPermission = next
        runCatching { mediaPermissionLauncher.launch(ScreenshotSource.permission) }
            .onFailure { afterMediaPermission = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        speechToText = AndroidSpeechToText(applicationContext)
        session = ChatSession(
            messageDao = messageDao,
            conversationDao = conversationDao,
            captureProcessor = captureProcessor,
            agentFactory = agentFactory,
            memoryEngine = memoryEngine,
            toolRouter = toolRouter,
            turnLogDao = turnLogDao,
            callChoices = callChoices,
            scope = lifecycleScope
        )

        // Every assistant invocation starts a fresh conversation; older ones stay
        // in the history tab.
        session.startNewConversation()

        ContextCompat.registerReceiver(
            this,
            captureReceiver,
            IntentFilter(ScreenCaptureService.ACTION_CAPTURED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        hasRecordPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRecordPermission) requestRecordPermission()

        requestCallPermissions()

        setContent {
            HeyAndroidTheme {
                AssistantChat(
                    hasPermission = hasRecordPermission,
                    speechToText = speechToText,
                    session = session,
                    callChoices = callChoices,
                    inputMode = settings.inputMode.value,
                    capturedPhoto = capturedPhoto,
                    onCapturedPhotoConsumed = { capturedPhoto = null },
                    onRequestScreenCapture = { requestScreenCapture() },
                    onRequestPermission = { requestRecordPermission() },
                    onRequestScreenshotPermission = { next -> requestMediaPermission(next) },
                    onOpenHistory = {
                        session.cancel()
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_HISTORY)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                        )
                        finishAndRemoveTask()
                    },
                    onDismiss = {
                        session.cancel()
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Summoned again while the window is still open: begin a new conversation.
        session.startNewConversation()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(captureReceiver) }
        session.cancel()
        speechToText.destroy()
    }
}

@Composable
fun AssistantChat(
    hasPermission: Boolean,
    speechToText: SpeechToText,
    session: ChatSession,
    callChoices: CallChoiceStore,
    inputMode: InputMode,
    capturedPhoto: String?,
    onCapturedPhotoConsumed: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onRequestPermission: () -> Unit,
    onRequestScreenshotPermission: (() -> Unit) -> Unit,
    onOpenHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val speechState by speechToText.state.collectAsState()
    val messages by session.messages.collectAsState()
    val thinking by session.thinking.collectAsState()
    val pendingCalls by callChoices.choices.collectAsState()
    val pendingCall by callChoices.pending.collectAsState()
    val callSecondsLeft by callChoices.secondsLeft.collectAsState()
    val latestMessages = rememberUpdatedState(messages)

    var composer by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(inputMode == InputMode.KEYBOARD) }
    var attachedPhoto by remember { mutableStateOf<String?>(null) }
    var attachedLabel by remember { mutableStateOf<String?>(null) }
    var readingPhoto by remember { mutableStateOf(false) }
    var pickingFromGallery by remember { mutableStateOf(false) }
    var errorLine by remember { mutableStateOf<String?>(null) }

    val isListening = speechState is SpeechState.Listening || speechState is SpeechState.Ready
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val editingNow = rememberUpdatedState(editing)

    fun attachShot(auto: Boolean) {
        scope.launch {
            readingPhoto = true
            errorLine = null
            try {
                val shot = ScreenshotSource.latest(
                    context,
                    maxAgeMillis = if (auto) AUTO_SCREENSHOT_WINDOW else Long.MAX_VALUE / 4
                )
                if (shot == null) {
                    if (!auto) errorLine = "No screenshot found on this device."
                } else {
                    val dataUrl = ImageAttachment.toDataUrl(context, shot.uri)
                    if (dataUrl == null) {
                        if (!auto) errorLine = "Could not read that screenshot."
                    } else {
                        attachedPhoto = dataUrl
                        attachedLabel = "Screenshot · ${ago(shot.age)}"
                    }
                }
            } catch (e: Throwable) {
                if (!auto) errorLine = "Screenshot failed: ${e.message ?: "unknown error"}"
            } finally {
                readingPhoto = false
            }
        }
    }

    fun attachScreenshot() {
        if (!ScreenshotSource.hasPermission(context)) {
            onRequestScreenshotPermission { attachShot(auto = false) }
            return
        }
        attachShot(auto = false)
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        pickingFromGallery = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readingPhoto = true
            try {
                val dataUrl = ImageAttachment.toDataUrl(context, uri)
                if (dataUrl == null) {
                    errorLine = "Could not read that image."
                } else {
                    attachedPhoto = dataUrl
                    attachedLabel = "Photo"
                }
            } catch (e: Throwable) {
                errorLine = "Image failed: ${e.message ?: "unknown error"}"
            } finally {
                readingPhoto = false
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readingPhoto = true
            errorLine = null
            try {
                val text = ImageAttachment.readText(context, uri)
                if (text == null) {
                    errorLine = "That document has no readable text. PDFs are not supported yet."
                } else {
                    composer = if (composer.isBlank()) text else composer + "\n\n" + text
                    editing = true
                }
            } catch (e: Throwable) {
                errorLine = "Could not read that document: ${e.message ?: "unknown error"}"
            } finally {
                readingPhoto = false
            }
        }
    }

    fun openDocument() {
        if (isListening) speechToText.stopListening()
        try {
            documentPicker.launch(
                arrayOf("text/*", "application/json", "application/xml", "application/csv")
            )
        } catch (e: Throwable) {
            errorLine = "Could not open the file picker: ${e.message ?: "unknown error"}"
        }
    }

    fun openGallery() {
        if (pickingFromGallery) return
        if (isListening) speechToText.stopListening()
        pickingFromGallery = true
        try {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Throwable) {
            pickingFromGallery = false
            errorLine = "Could not open the gallery: ${e.message ?: "unknown error"}"
        }
    }

    fun submit() {
        val text = composer.trim()
        val photo = attachedPhoto
        if (text.isEmpty() && photo == null) return
        if (isListening) speechToText.stopListening()

        val source = when {
            photo == null -> MemorySource.VOICE
            attachedLabel?.startsWith("Screenshot") == true -> MemorySource.SCREENSHOT
            else -> MemorySource.PHOTO
        }

        session.send(text = text, imageDataUrl = photo, source = source)
        composer = ""
        attachedPhoto = null
        attachedLabel = null
        errorLine = null
        editing = false
    }

    // A screenshot taken moments before summoning the assistant is attached on its
    // own: that is the "see something, keep it" flow.
    LaunchedEffect(Unit) {
        if (!ScreenshotSource.hasPermission(context)) return@LaunchedEffect
        val shot = ScreenshotSource.latest(context, AUTO_SCREENSHOT_WINDOW) ?: return@LaunchedEffect
        val dataUrl = ImageAttachment.toDataUrl(context, shot.uri) ?: return@LaunchedEffect
        attachedPhoto = dataUrl
        attachedLabel = "Screenshot · ${ago(shot.age)}"
    }

    // A frame just captured through MediaProjection becomes the attachment.
    LaunchedEffect(capturedPhoto) {
        val photo = capturedPhoto ?: return@LaunchedEffect
        attachedPhoto = photo
        attachedLabel = "Screenshot · just now"
        onCapturedPhotoConsumed()
    }

    LaunchedEffect(editing) {
        if (editing) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    LaunchedEffect(hasPermission, inputMode) {
        if (hasPermission && inputMode == InputMode.VOICE && speechState is SpeechState.Idle) {
            speechToText.startListening()
        }
    }

    // Once the microphone is settled, ask for gallery access too, so the newest
    // screenshot can attach itself from then on.
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        if (ScreenshotSource.hasPermission(context)) return@LaunchedEffect
        delay(900)
        // Only picks up something recent, so an old screenshot never appears by itself.
        onRequestScreenshotPermission { attachShot(auto = true) }
    }

    LaunchedEffect(speechState) {
        when (val state = speechState) {
            is SpeechState.PartialResult -> {
                if (!editingNow.value) composer = state.text
            }

            is SpeechState.FinalResult -> {
                if (!editingNow.value && state.text.isNotBlank()) {
                    session.send(state.text)
                    composer = ""
                }
            }

            else -> {}
        }
    }

    // Hands-free: after each reply, listen again unless the keyboard is up.
    LaunchedEffect(session) {
        session.turnFinished.collect {
            delay(400)
            // A closing "thanks" / "bye" ends the conversation: the sheet closes once
            // the short reply has been read, instead of listening for another turn.
            val lastUser = latestMessages.value
                .lastOrNull { it.role == MessageRole.USER }
                ?.content
            if (lastUser != null && isClosingPhrase(lastUser)) {
                delay(1_400)
                onDismiss()
                return@collect
            }
            if (!editingNow.value && hasPermission && inputMode == InputMode.VOICE) {
                speechToText.startListening()
            }
        }
    }

    LaunchedEffect(messages.size, thinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    BackHandler(enabled = editing) {
        editing = false
    }

    // The card opens at roughly half the screen and can be dragged taller from its
    // handle: the conversation list takes whatever room is left.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val screenHeightPx = with(LocalDensity.current) { screenHeightDp.dp.toPx() }
    var sheetFraction by rememberSaveable { mutableStateOf(DEFAULT_SHEET_FRACTION) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!pickingFromGallery) onDismiss() }
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(sheetFraction)
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 14.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume clicks inside the window */ }
                ),
            shape = RoundedCornerShape(28.dp),
            // Fully opaque so the conversation stays readable over any app.
            color = AppColors.Surface,
            tonalElevation = 0.dp,
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, AppColors.Border)
        ) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                ChatHeader(
                    onOpenHistory = onOpenHistory,
                    onClose = onDismiss,
                    modifier = Modifier.pointerInput(screenHeightPx) {
                        detectVerticalDragGestures { _, dragAmount ->
                            val delta = -dragAmount / screenHeightPx
                            sheetFraction = (sheetFraction + delta)
                                .coerceIn(MIN_SHEET_FRACTION, MAX_SHEET_FRACTION)
                        }
                    }
                )

                if (messages.isEmpty()) {
                    Column(
                        // Takes the leftover room so the composer, mic and attach
                        // button stay pinned to the bottom edge of the card.
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (thinking) "Thinking…" else {
                                "Ask anything, dictate, or save a screenshot."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickPrompt("Remember this") {
                                composer = "Remember this: "
                                editing = true
                            }
                            QuickPrompt("What did I save?") {
                                composer = "What did I save recently?"
                                editing = true
                            }
                            QuickPrompt("Remind me") {
                                composer = "Remind me to "
                                editing = true
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageBubble(message)
                        }
                        if (thinking) {
                            item(key = "thinking") { ThinkingBubble() }
                        }
                    }
                }

                val call = pendingCall
                if (call != null) {
                    PendingCallCard(
                        call = call,
                        secondsLeft = callSecondsLeft,
                        onCancel = { session.cancelPendingCall() }
                    )
                } else if (pendingCalls.isNotEmpty()) {
                    ContactChoices(
                        choices = pendingCalls,
                        onChoose = { index -> session.chooseCall(index) },
                        onDismiss = { callChoices.clear() }
                    )
                }

                InputBar(
                    composer = composer,
                    onComposerChange = { composer = it },
                    hasPermission = hasPermission,
                    editing = editing,
                    isListening = isListening,
                    thinking = thinking,
                    speechState = speechState,
                    attachedLabel = attachedLabel,
                    readingPhoto = readingPhoto,
                    errorLine = errorLine,
                    accent = accent,
                    focusRequester = focusRequester,
                    onAttachScreenshot = { attachScreenshot() },
                    onPickGallery = { openGallery() },
                    onCaptureScreen = onRequestScreenCapture,
                    onAttachDocument = { openDocument() },
                    onRemovePhoto = {
                        attachedPhoto = null
                        attachedLabel = null
                    },
                    onMic = {
                        when {
                            !hasPermission -> onRequestPermission()
                            isListening -> speechToText.stopListening()
                            else -> {
                                editing = false
                                speechToText.startListening()
                            }
                        }
                    },
                    onFieldFocused = {
                        editing = true
                        speechToText.stopListening()
                    },
                    onSend = { submit() }
                )
            }
        }
    }
}

private const val AUTO_SCREENSHOT_WINDOW = 10 * 60 * 1000L

/**
 * How much of the screen the assistant card covers. It opens at its smallest - the
 * compact composer - and dragging the handle grows it up to nearly full screen.
 */
private const val MIN_SHEET_FRACTION = 0.36f
private const val DEFAULT_SHEET_FRACTION = MIN_SHEET_FRACTION
private const val MAX_SHEET_FRACTION = 0.95f

private fun ago(millis: Long): String = when {
    millis < 60_000 -> "just now"
    millis < 3_600_000 -> "${millis / 60_000} min ago"
    millis < 86_400_000 -> "${millis / 3_600_000} h ago"
    else -> "${millis / 86_400_000} d ago"
}

/** Short farewells and thank-yous that mean "we are done here". */
private val CLOSING_WORDS = Regex(
    "\\b(thanks|thank you|thx|ty|bye|byee|goodbye|good bye|good night|goodnight|" +
        "see you|that's all|thats all|no thanks|nothing else|that will be all)\\b"
)

private fun isClosingPhrase(text: String): Boolean {
    val clean = text.trim().lowercase()
    // Only a short line can be a farewell; a sentence mentioning "thanks" is not.
    return clean.isNotEmpty() && clean.length <= 40 && CLOSING_WORDS.containsMatchIn(clean)
}

@Composable
private fun ChatHeader(
    onOpenHistory: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 34.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HeyAndroid",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onOpenHistory,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_history),
                    contentDescription = "Conversation history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Close assistant",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val accent = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme

    when (message.role) {
        MessageRole.STATUS -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            ToolActivityLine(message.content, scheme.onSurfaceVariant, accent)
        }

        MessageRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Bubble(
                text = message.content,
                fill = AppColors.AccentSoft,
                textColor = AppColors.AccentText
            )
        }

        MessageRole.ASSISTANT -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            val context = LocalContext.current
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColors.SurfaceRaised)
                    // Long-press copies the reply; links inside it are tappable.
                    .pointerInput(message.content) {
                        detectTapGestures(
                            onLongPress = {
                                val clipboard =
                                    context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("HeyAndroid", message.content)
                                )
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                MarkdownText(
                    text = message.content,
                    color = AppColors.TextPrimary,
                    accent = accent
                )
            }
        }

        MessageRole.ERROR -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Bubble(
                text = message.content,
                fill = scheme.errorContainer,
                textColor = scheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun Bubble(text: String, fill: Color, textColor: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
        color = textColor,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .padding(horizontal = 13.dp, vertical = 9.dp)
    )
}

/**
 * "Which one should I call?": every matching contact is a row with its full name
 * and number. A tap dials it immediately; tapping Cancel drops the options.
 */
@Composable
private fun ContactChoices(
    choices: List<CallChoice>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Which one should I call?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // The rows scroll inside a fixed band so a long list never pushes the
        // composer off the sheet.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 210.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            choices.forEachIndexed { index, choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.Chip)
                        .border(1.dp, AppColors.Border, RoundedCornerShape(14.dp))
                        .clickable { onChoose(index) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(AppColors.SurfaceSunken),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextSecondary
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = choice.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = choice.number,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Call",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Accent
                    )
                }
            }
        }
    }
}

/**
 * The last look before a call goes out: who it is, the number, the seconds left
 * and a Cancel button that stops the dial.
 */
@Composable
private fun PendingCallCard(
    call: PendingCall,
    secondsLeft: Int,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.AccentSoft)
            .border(1.dp, AppColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Calling ${call.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = call.number,
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (secondsLeft > 0) "Starts in $secondsLeft s" else "Starting…",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.Accent,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = "Cancel",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.SurfaceSunken)
                .border(1.dp, AppColors.BorderStrong, RoundedCornerShape(10.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

/** Tool activity is one collapsed line until tapped. */
@Composable
private fun ToolActivityLine(content: String, textColor: Color, accent: Color) {
    val lines = content.lines().filter { it.isNotBlank() }
    var expanded by remember(content) { mutableStateOf(false) }
    Text(
        text = if (expanded || lines.size <= 1) {
            content
        } else {
            lines.first() + "  ·  tap to show"
        },
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(max = 310.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceSunken)
            .clickable { if (lines.size > 1) expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun QuickPrompt(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(AppColors.SurfaceRaised)
            .border(1.dp, AppColors.Border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary
        )
    }
}

@Composable
private fun AttachOption(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        onClick = onClick,
        leadingIcon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = AppColors.Accent,
                modifier = Modifier.size(18.dp)
            )
        },
        text = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
                )
            }
        }
    )
}

@Composable
private fun ThinkingBubble() {
    val alpha by rememberInfiniteTransition(label = "thinking")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "thinkingAlpha"
        )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.SurfaceRaised)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun InputBar(
    composer: String,
    onComposerChange: (String) -> Unit,
    hasPermission: Boolean,
    editing: Boolean,
    isListening: Boolean,
    thinking: Boolean,
    speechState: SpeechState,
    attachedLabel: String?,
    readingPhoto: Boolean,
    errorLine: String?,
    accent: Color,
    focusRequester: FocusRequester,
    onAttachScreenshot: () -> Unit,
    onPickGallery: () -> Unit,
    onCaptureScreen: () -> Unit,
    onAttachDocument: () -> Unit,
    onRemovePhoto: () -> Unit,
    onMic: () -> Unit,
    onFieldFocused: () -> Unit,
    onSend: () -> Unit
) {
    var attachOpen by remember { mutableStateOf(false) }

    val pulse by rememberInfiniteTransition(label = "micPulse").animateFloat(
        initialValue = 1f,
        targetValue = if (isListening && !editing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    val caption = when {
        errorLine != null -> errorLine
        !hasPermission -> "Microphone permission needed"
        readingPhoto -> "Reading image…"
        thinking -> "Thinking…"
        isListening -> "Listening…"
        speechState is SpeechState.Error && composer.isBlank() ->
            "Voice input unavailable · tap the field to type"
        else -> "Say what to remember, or ask about it"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (!editing) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (speechState is SpeechState.Error || errorLine != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 5.dp)
            )
        }

        if (attachedLabel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = 4.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.AccentSoft)
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_screenshot),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = attachedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                IconButton(onClick = onRemovePhoto, modifier = Modifier.size(26.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Remove attachment",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // One attach affordance, four sources. Keeps the bar readable and
            // makes each action explicit instead of four look-alike icons.
            Box {
                IconButton(
                    onClick = { attachOpen = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = "Add an attachment",
                        modifier = Modifier.size(21.dp),
                        tint = AppColors.TextSecondary
                    )
                }

                DropdownMenu(
                    expanded = attachOpen,
                    onDismissRequest = { attachOpen = false },
                    containerColor = AppColors.SurfaceRaised
                ) {
                    AttachOption(
                        icon = R.drawable.ic_screenshot,
                        title = "Latest screenshot",
                        subtitle = "What you just captured"
                    ) {
                        attachOpen = false
                        onAttachScreenshot()
                    }
                    AttachOption(
                        icon = R.drawable.ic_gallery,
                        title = "Photo from gallery",
                        subtitle = "Pick any image"
                    ) {
                        attachOpen = false
                        onPickGallery()
                    }
                    AttachOption(
                        icon = R.drawable.ic_screen_capture,
                        title = "Capture this screen",
                        subtitle = "Android asks for consent"
                    ) {
                        attachOpen = false
                        onCaptureScreen()
                    }
                    AttachOption(
                        icon = R.drawable.ic_document,
                        title = "Text document",
                        subtitle = "txt, md, csv or json"
                    ) {
                        attachOpen = false
                        onAttachDocument()
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(AppColors.SurfaceSunken)
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                BasicTextField(
                    value = composer,
                    onValueChange = onComposerChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) onFieldFocused() },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    // Without this the keyboard's send key did nothing and the
                    // message sat in the field.
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { inner ->
                        if (composer.isEmpty()) {
                            Text(
                                text = "Message HeyAndroid…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Send while the keyboard is up or something is attached; otherwise mic.
            if (editing || attachedLabel != null) {
                val canSend = (composer.isNotBlank() || attachedLabel != null) &&
                    !thinking &&
                    !readingPhoto
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (canSend) accent else AppColors.SurfaceRaised,
                        disabledContainerColor = AppColors.SurfaceRaised
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send),
                        contentDescription = "Send message",
                        modifier = Modifier.size(18.dp),
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            } else {
                FilledIconButton(
                    onClick = onMic,
                    modifier = Modifier
                        .size(40.dp)
                        .scale(pulse)
                ) {
                    Icon(
                        painter = painterResource(
                            if (isListening) R.drawable.ic_stop else R.drawable.ic_mic
                        ),
                        contentDescription = if (isListening) "Stop dictation" else "Dictate",
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}
