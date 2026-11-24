package com.example.heyandroid.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEmbedderTest {

    private val embedder = LocalEmbedder()

    @Test
    fun `the same text embeds to itself`() {
        val vector = embedder.embed("breaking bad s1e2")

        assertEquals(1f, embedder.cosine(vector, vector), 0.0001f)
    }

    @Test
    fun `related text scores above unrelated text`() {
        val query = embedder.embed("breaking bad")
        val related = embedder.embed("Breaking Bad S1E2")
        val unrelated = embedder.embed("grocery list milk eggs")

        assertTrue(embedder.cosine(query, related) > embedder.cosine(query, unrelated))
    }

    @Test
    fun `a tense change stays closer than an unrelated sentence`() {
        val watching = embedder.embed("watching lantern")
        val watched = embedder.embed("watched lantern")
        val unrelated = embedder.embed("buy milk and eggs")

        assertTrue(embedder.cosine(watching, watched) > embedder.cosine(watching, unrelated))
    }

    @Test
    fun `vectors of different sizes do not match`() {
        assertEquals(0f, embedder.cosine(FloatArray(8), FloatArray(16)), 0.0001f)
    }

    @Test
    fun `vectors survive a round trip through bytes`() {
        val vector = embedder.embed("a memory worth keeping")

        assertArrayEquals(vector, VectorCodec.decode(VectorCodec.encode(vector)), 0.0001f)
    }

    @Test
    fun `a truncated blob decodes to nothing instead of crashing`() {
        assertEquals(0, VectorCodec.decode(ByteArray(7)).size)
    }
}
