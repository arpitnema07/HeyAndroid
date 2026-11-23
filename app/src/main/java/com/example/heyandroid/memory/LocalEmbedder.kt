package com.example.heyandroid.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * A small on-device text embedder. No model download and no network: it hashes
 * words, word pairs and character trigrams into a fixed-width vector and
 * L2-normalises it, so cosine similarity is a plain dot product.
 *
 * It is not a neural embedding, but for personal memory it matches well: the
 * same subject keeps the same words ("breaking bad", "S1E2"), and typos or
 * partial phrases still overlap through the trigrams. Retrieval is also fused
 * with a keyword pass in [MemoryRepository], which covers what hashing misses.
 */
class LocalEmbedder(val dimensions: Int = 384) {

    fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val normalized = text.lowercase()
        val words = TOKEN.split(normalized).filter { it.isNotBlank() }

        words.forEach { word ->
            add(vector, "w:$word", 2.0f)
            if (word.length >= 4) add(vector, "p:${stem(word)}", 1.2f)
        }
        words.zipWithNext().forEach { (a, b) -> add(vector, "b:$a $b", 1.0f) }
        words.forEach { word ->
            if (word.length < 4) return@forEach
            for (index in 0..word.length - 3) {
                add(vector, "g:${word.substring(index, index + 3)}", 0.4f)
            }
        }

        return normalize(vector)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (index in a.indices) dot += a[index] * b[index]
        return dot
    }

    private fun add(vector: FloatArray, token: String, weight: Float) {
        val hash = hash(token)
        val slot = (hash and Int.MAX_VALUE) % dimensions
        val sign = if (hash and 0x80000000.toInt() != 0) -1f else 1f
        vector[slot] += weight * sign
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (value in vector) sum += value * value
        val length = sqrt(sum)
        if (length <= 0f) return vector
        for (index in vector.indices) vector[index] /= length
        return vector
    }

    /** Crude suffix strip so "watching"/"watched" land close together. */
    private fun stem(word: String): String {
        val suffixes = listOf("ing", "ed", "es", "s")
        val suffix = suffixes.firstOrNull { word.length > it.length + 3 && word.endsWith(it) }
            ?: return word
        return word.dropLast(suffix.length)
    }

    private fun hash(token: String): Int {
        var result = -0x7a143595
        token.forEach { char ->
            result = (result xor char.code) * 0x01000193
        }
        return result
    }

    private companion object {
        val TOKEN = Regex("[^\\p{L}\\p{N}]+")
    }
}

/** Vectors are stored as little-endian float blobs. */
object VectorCodec {

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        if (bytes.size % 4 != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(bytes.size / 4)
        for (index in vector.indices) vector[index] = buffer.float
        return vector
    }
}
