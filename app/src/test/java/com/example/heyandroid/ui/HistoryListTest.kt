package com.example.heyandroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.heyandroid.data.conversation.Conversation
import com.example.heyandroid.ui.components.AppColors
import com.example.heyandroid.ui.history.HistoryList
import com.example.heyandroid.ui.theme.HeyAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI tests run on the JVM through Robolectric, so no device or emulator is
 * needed: the real Compose tree is laid out and its semantics are asserted.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryListTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an empty history explains itself`() {
        compose.setContent {
            HeyAndroidTheme(darkTheme = true) {
                HistoryList(
                    conversations = emptyList(),
                    accent = AppColors.Accent,
                    onOpen = {}
                )
            }
        }

        compose.onNodeWithText("No conversations yet").assertIsDisplayed()
    }

    @Test
    fun `conversations are listed and open on tap`() {
        var opened: Conversation? = null
        val conversations = listOf(
            Conversation(id = 7, title = "Fix the SyncPad widget", messageCount = 4),
            Conversation(id = 8, title = "Plan the weekend", messageCount = 2)
        )

        compose.setContent {
            HeyAndroidTheme(darkTheme = true) {
                HistoryList(
                    conversations = conversations,
                    accent = AppColors.Accent,
                    onOpen = { opened = it }
                )
            }
        }

        compose.onNodeWithText("Fix the SyncPad widget").assertIsDisplayed()
        compose.onNodeWithText("Plan the weekend").assertIsDisplayed()

        compose.onNodeWithText("Fix the SyncPad widget").performClick()

        assertEquals(7L, opened?.id)
    }
}
