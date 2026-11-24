package com.example.heyandroid.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `gemini ids lose their prefix and come back sorted`() {
        val ids = listOf("models/gemini-3.8-flash", "models/gemini-2.5-flash")

        assertEquals(
            listOf("gemini-2.5-flash", "gemini-3.8-flash"),
            ModelCatalog.chatModels(ids)
        )
    }

    @Test
    fun `embeddings, rerankers and audio models are dropped`() {
        val ids = listOf(
            "nvidia/nv-embedqa-e5-v5",
            "nvidia/llama-3.2-nv-rerankqa-1b-v2",
            "openai/whisper-large-v3",
            "meta/llama-3.3-70b-instruct"
        )

        assertEquals(listOf("meta/llama-3.3-70b-instruct"), ModelCatalog.chatModels(ids))
    }

    @Test
    fun `blank ids and duplicates are cleaned up`() {
        val ids = listOf("  gpt-x  ", "", "   ", "gpt-x", "models/gpt-x")

        assertEquals(listOf("gpt-x"), ModelCatalog.chatModels(ids))
    }

    @Test
    fun `an empty catalogue is not an error`() {
        assertEquals(emptyList<String>(), ModelCatalog.chatModels(emptyList()))
    }
}
