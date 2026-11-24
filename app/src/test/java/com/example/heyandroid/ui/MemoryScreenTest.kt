package com.example.heyandroid.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.heyandroid.data.AppDatabase
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.memory.LocalEmbedder
import com.example.heyandroid.memory.MemoryRepository
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.memory.MemoryScreen
import com.example.heyandroid.ui.theme.HeyAndroidTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI test for the memory tab: the real screen, the real repository and a real
 * in-memory database. It checks what the user actually sees, not internals.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(db, LocalEmbedder())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seed() = runBlocking {
        repository.save(
            Memory(
                kind = MemoryKind.PROGRESS,
                subject = "breaking bad",
                title = "Breaking Bad",
                progress = "S1E2"
            )
        )
        repository.save(
            Memory(
                kind = MemoryKind.TASK,
                subject = "file taxes",
                title = "File taxes",
                dueAt = System.currentTimeMillis() + 3_600_000
            )
        )
    }

    private fun show() {
        compose.setContent {
            HeyAndroidTheme(darkTheme = true) {
                MemoryScreen(repository = repository, accent = AppColors.Accent)
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Breaking Bad").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `stored memories are shown on the memory tab`() {
        seed()
        show()

        compose.onNodeWithText("Breaking Bad").assertIsDisplayed()
        compose.onNodeWithText("File taxes").assertIsDisplayed()
    }

    @Test
    fun `the tasks filter hides everything that is not a task`() {
        seed()
        show()

        compose.onNodeWithText("Tasks").performClick()

        compose.onNodeWithText("Breaking Bad").assertDoesNotExist()
        compose.onNodeWithText("File taxes").assertIsDisplayed()
    }

    @Test
    fun `searching outside the stored words shows the empty state`() {
        seed()
        show()

        compose.onNode(hasSetTextAction()).performTextInput("zebra")

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("No match for \"zebra\"").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
