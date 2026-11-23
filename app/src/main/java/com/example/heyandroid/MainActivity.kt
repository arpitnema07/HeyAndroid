package com.example.heyandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.heyandroid.data.AppDatabase
import com.example.heyandroid.data.capture.CaptureProcessor
import com.example.heyandroid.memory.MemoryEngine
import com.example.heyandroid.memory.MemoryRepository
import com.example.heyandroid.brief.BriefActivity
import com.example.heyandroid.ui.home.HomeScreen
import com.example.heyandroid.ui.theme.HeyAndroidTheme
import com.example.heyandroid.widget.BriefWorker
import com.example.heyandroid.work.ReminderWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Launcher screen: the memory store, conversation history and the capture log.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var memoryRepository: MemoryRepository

    @Inject
    lateinit var captureProcessor: CaptureProcessor

    @Inject
    lateinit var memoryEngine: MemoryEngine

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The home screen is glass on a dark backdrop, so keep the system bars light-on-dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        ReminderWorker.ensureChannel(this)
        ReminderWorker.schedule(this)

        // A capture left pending by a killed process is marked failed, so the Log
        // tab never shows a row that can no longer progress as pending.
        lifecycleScope.launch {
            captureProcessor.failStale()
            memoryEngine.repairUnscheduledReminders()
        }

        // The widget must never sit on "brief is empty": rebuild it on launch
        // when the cached page is missing or stale. The extra forces a build,
        // which is also how the pipeline can be exercised from adb.
        BriefWorker.refreshIfStale(
            this,
            force = intent?.getBooleanExtra(EXTRA_BUILD_BRIEF, false) == true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Deep-link entry into the brief page; also how the page is opened from
        // adb (`am start ... --ez open_brief true`).
        if (intent?.getBooleanExtra(EXTRA_OPEN_BRIEF, false) == true) {
            startActivity(Intent(this, BriefActivity::class.java))
        }

        val initialTab = intent?.getStringExtra(EXTRA_TAB)

        setContent {
            HeyAndroidTheme(darkTheme = true) {
                HomeScreen(
                    memoryRepository = memoryRepository,
                    conversationDao = database.conversationDao(),
                    messageDao = database.messageDao(),
                    captureDao = database.captureDao(),
                    turnLogDao = database.turnLogDao(),
                    initialTab = initialTab
                )
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "extra_tab"
        const val TAB_HISTORY = "history"
        const val EXTRA_BUILD_BRIEF = "build_brief"
        const val EXTRA_OPEN_BRIEF = "open_brief"
    }
}
