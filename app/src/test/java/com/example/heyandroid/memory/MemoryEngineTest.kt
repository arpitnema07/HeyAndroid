package com.example.heyandroid.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.heyandroid.data.AppDatabase
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.data.memory.MemorySource
import com.example.heyandroid.llm.LlmProvider
import com.example.heyandroid.llm.LlmSettingsStore
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for the memory agent: real database, real embedder, no API
 * key - which is the offline path that must never lose what the user said.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var engine: MemoryEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(db, LocalEmbedder())
        val settings = LlmSettingsStore.getInstance(context)
        LlmProvider.entries.forEach { settings.clearApiKey(it) }
        engine = MemoryEngine(context, repository, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `without a key the text is still kept as a note`() = runTest {
        val result = engine.ingest("Remember to buy milk", source = MemorySource.VOICE)

        assertFalse(result.usedModel)
        assertEquals(1, result.changed)
        val stored = repository.recent().single()
        assertEquals(MemoryKind.NOTE, stored.kind)
        assertTrue(stored.content.contains("buy milk"))
    }

    @Test
    fun `empty input changes nothing`() = runTest {
        assertTrue(engine.ingest("   ").isEmpty)
        assertEquals(0, repository.activeCount())
    }

    @Test
    fun `recall returns what was stored a moment ago`() = runTest {
        engine.ingest("Remember to buy milk")

        val context = engine.recall("milk")

        assertTrue(context.contains("buy milk"))
    }

    @Test
    fun `an empty recall question returns nothing`() = runTest {
        engine.ingest("Remember to buy milk")

        assertEquals("", engine.recall("  "))
    }

    private fun at(date: LocalDateTime): Long =
        date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `tomorrow at a clock time resolves to that local moment`() {
        val now = at(LocalDateTime.of(2026, 11, 24, 10, 0))

        val due = engine.inferDue("tomorrow at 6 pm", now)

        assertEquals(at(LocalDateTime.of(2026, 11, 25, 18, 0)), due)
    }

    @Test
    fun `a weekday resolves to the next one at a sensible hour`() {
        val now = at(LocalDateTime.of(2026, 11, 24, 10, 0)) // a Tuesday

        val due = engine.inferDue("sunday morning", now)

        assertEquals(at(LocalDateTime.of(2026, 11, 29, 9, 0)), due)
    }

    @Test
    fun `phrasing without a date has no due time`() {
        assertNull(engine.inferDue("call the plumber", 0))
    }
}
