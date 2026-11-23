package com.example.heyandroid.assistant

import com.example.heyandroid.data.chat.Message
import com.example.heyandroid.data.chat.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryTest {

    @Test
    fun `user and assistant lines become model messages`() {
        val user = messageToLlmMessage(Message(role = MessageRole.USER, content = "hi"))
        val assistant = messageToLlmMessage(
            Message(role = MessageRole.ASSISTANT, content = "hello")
        )

        assertEquals("user", user?.role)
        assertEquals("hi", user?.content)
        assertEquals("assistant", assistant?.role)
        assertEquals("hello", assistant?.content)
    }

    @Test
    fun `status and error lines are not sent to the model`() {
        assertNull(messageToLlmMessage(Message(role = MessageRole.STATUS, content = "Created note")))
        assertNull(messageToLlmMessage(Message(role = MessageRole.ERROR, content = "Rate limited")))
    }

    @Test
    fun `history keeps only conversational turns in order`() {
        val stored = listOf(
            Message(role = MessageRole.USER, content = "remember milk"),
            Message(role = MessageRole.STATUS, content = "Created note #1: milk"),
            Message(role = MessageRole.ERROR, content = "ignore me"),
            Message(role = MessageRole.ASSISTANT, content = "Noted.")
        )

        val history = stored.mapNotNull { messageToLlmMessage(it) }

        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals(listOf("remember milk", "Noted."), history.map { it.content })
    }
}
