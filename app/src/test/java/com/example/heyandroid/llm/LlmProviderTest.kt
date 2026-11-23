package com.example.heyandroid.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProviderTest {

    @Test
    fun `every built-in provider offers at least one free model`() {
        val builtIn = LlmProvider.entries.filterNot { it.isCustom }
        assertTrue(builtIn.isNotEmpty())
        builtIn.forEach { provider ->
            assertTrue(provider.freeModels.isNotEmpty())
            assertEquals(provider.freeModels.first().id, provider.defaultModel)
        }
    }

    @Test
    fun `the custom provider has no catalog of its own`() {
        val custom = LlmProvider.entries.single { it.isCustom }
        assertTrue(custom.freeModels.isEmpty())
        assertTrue(custom.baseUrl.isBlank())
        assertTrue(custom.keyUrl.isBlank())
    }

    @Test
    fun `free model ids are unique per provider`() {
        LlmProvider.entries.forEach { provider ->
            val ids = provider.freeModels.map { it.id }
            assertEquals(ids.size, ids.toSet().size)
            ids.forEach { assertTrue("$it is not a free model", provider.isFreeModel(it)) }
        }
    }

    @Test
    fun `paid or unknown models are rejected`() {
        LlmProvider.entries.forEach { provider ->
            assertFalse(provider.isFreeModel("some/paid-model"))
            assertFalse(provider.isFreeModel(""))
        }
    }

    @Test
    fun `unknown provider id falls back to the first entry`() {
        assertEquals(LlmProvider.GROQ, LlmProvider.fromId("nope"))
        assertEquals(LlmProvider.GROQ, LlmProvider.fromId(null))
        assertEquals(LlmProvider.GROQ, LlmProvider.fromId(LlmProvider.GROQ.id))
    }
}
