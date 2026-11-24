package com.example.heyandroid.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for settings: the real SharedPreferences file on a Robolectric
 * context, exercising the same code the settings screen calls.
 */
@RunWith(RobolectricTestRunner::class)
class LlmSettingsStoreTest {

    private lateinit var settings: LlmSettingsStore

    @Before
    fun setUp() {
        settings = LlmSettingsStore.getInstance(
            ApplicationProvider.getApplicationContext<Context>()
        )
        LlmProvider.entries.forEach { settings.clearApiKey(it) }
        settings.setCustomBaseUrl("")
        settings.selectProvider(LlmProvider.GROQ)
        settings.setModel(LlmProvider.GROQ, LlmProvider.GROQ.defaultModel)
        settings.setInputMode(InputMode.VOICE)
    }

    @Test
    fun `a fresh install is not configured and uses the catalogue default`() {
        val config = settings.config.value
        assertEquals(LlmProvider.GROQ, config.provider)
        assertEquals(LlmProvider.GROQ.defaultModel, config.model)
        assertFalse(config.isConfigured)
    }

    @Test
    fun `a key configures only the provider it was saved for`() {
        settings.setApiKey(LlmProvider.GROQ, "  gsk_test  ")

        assertTrue(settings.config.value.isConfigured)
        assertEquals("gsk_test", settings.apiKeyFor(LlmProvider.GROQ))
        assertEquals("", settings.apiKeyFor(LlmProvider.GEMINI))
    }

    @Test
    fun `keys and models survive switching providers`() {
        settings.setApiKey(LlmProvider.GROQ, "gsk_test")
        settings.setModel(LlmProvider.GROQ, "openai/gpt-oss-20b")

        settings.selectProvider(LlmProvider.GEMINI)
        assertFalse(settings.config.value.isConfigured)
        settings.setApiKey(LlmProvider.GEMINI, "AIza_test")

        settings.selectProvider(LlmProvider.GROQ)
        assertEquals("gsk_test", settings.config.value.apiKey)
        assertEquals("openai/gpt-oss-20b", settings.config.value.model)
    }

    @Test
    fun `a blank model falls back to the catalogue default`() {
        settings.setModel(LlmProvider.GROQ, "openai/gpt-oss-20b")
        settings.setModel(LlmProvider.GROQ, "   ")

        assertEquals(LlmProvider.GROQ.defaultModel, settings.config.value.model)
    }

    @Test
    fun `known models start from the built-in catalogue`() {
        assertEquals(
            LlmProvider.NVIDIA.freeModels.map { it.id },
            settings.knownModels(LlmProvider.NVIDIA)
        )
    }

    @Test
    fun `the custom provider uses the saved base url`() {
        settings.setCustomBaseUrl("  https://llm.example.com/v1  ")
        settings.selectProvider(LlmProvider.CUSTOM)

        assertEquals("https://llm.example.com/v1", settings.config.value.baseUrl)
        assertFalse(settings.config.value.isConfigured)

        settings.setApiKey(LlmProvider.CUSTOM, "sk-test")
        assertTrue(settings.config.value.isConfigured)
    }

    @Test
    fun `the input mode is remembered`() {
        settings.setInputMode(InputMode.KEYBOARD)
        assertEquals(InputMode.KEYBOARD, settings.inputMode.value)

        settings.setInputMode(InputMode.VOICE)
        assertEquals(InputMode.VOICE, settings.inputMode.value)
    }
}
