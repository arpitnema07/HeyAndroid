package com.example.heyandroid.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.heyandroid.data.AppDatabase
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryKind
import com.example.heyandroid.data.memory.MemoryStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests: the real Room database (in memory) behind the real
 * repository, with the real local embedder. No phone and no emulator.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryRepositoryTest {

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

    private fun memory(
        title: String,
        kind: MemoryKind = MemoryKind.FACT,
        subject: String = title.lowercase(),
        content: String = "",
        progress: String = "",
        status: MemoryStatus = MemoryStatus.ACTIVE
    ) = Memory(
        kind = kind,
        subject = subject,
        title = title,
        content = content,
        progress = progress,
        status = status
    )

    @Test
    fun `saving rows indexes them and writes the timeline`() = runTest {
        val id = repository.save(memory("Breaking Bad", MemoryKind.PROGRESS), "Started")

        assertEquals("Breaking Bad", repository.byId(id)?.title)
        assertEquals(1, db.memoryVectorDao().count())
        assertEquals(listOf("Started"), repository.timeline(id).map { it.summary })
    }

    @Test
    fun `an update keeps one vector per memory`() = runTest {
        val id = repository.save(memory("Breaking Bad", MemoryKind.PROGRESS))
        repository.save(repository.byId(id)!!.copy(progress = "S1E2"), "At S1E2")

        assertEquals(1, db.memoryVectorDao().count())
        assertEquals("S1E2", repository.byId(id)?.progress)
        // The first save had no event; the update added one.
        assertEquals(listOf("At S1E2"), repository.timeline(id).map { it.summary })
    }

    @Test
    fun `search finds what was saved and carries the timeline`() = runTest {
        val id = repository.save(
            memory(
                title = "Breaking Bad",
                kind = MemoryKind.PROGRESS,
                content = "watching with Aarti",
                progress = "S1E2"
            ),
            "At S1E2"
        )

        val cards = repository.search("breaking bad")

        assertTrue(cards.isNotEmpty())
        assertEquals(id, cards.first().memory.id)
        assertEquals(listOf("At S1E2"), cards.first().events.map { it.summary })
    }

    @Test
    fun `search uses meaning when the words differ`() = runTest {
        val id = repository.save(
            memory("Lantern", MemoryKind.PROGRESS, content = "reading the third book")
        )

        val cards = repository.search("lantern reading")

        assertEquals(id, cards.firstOrNull()?.memory?.id)
    }

    @Test
    fun `an empty query lists the newest memories`() = runTest {
        repository.save(memory("Older").copy(updatedAt = 1_000))
        repository.save(memory("Newer").copy(updatedAt = 2_000))

        assertEquals(listOf("Newer", "Older"), repository.search("  ").map { it.memory.title })
    }

    @Test
    fun `archived memories leave the count but stay readable`() = runTest {
        repository.save(memory("One"))
        val id = repository.save(memory("Two"))
        repository.save(repository.byId(id)!!.copy(status = MemoryStatus.ARCHIVED))

        assertEquals(1, repository.activeCount())
        assertEquals("Two", repository.byId(id)?.title)
    }

    @Test
    fun `findSimilar matches a plural subject`() = runTest {
        val id = repository.save(memory("Lantern", MemoryKind.PROGRESS))

        assertEquals(id, repository.findSimilar("lanterns", MemoryKind.PROGRESS)?.id)
        assertNull(repository.findSimilar("lanterns", MemoryKind.TRANSACTION))
    }

    @Test
    fun `deleting a memory removes its events and its vector`() = runTest {
        val id = repository.save(memory("Temporary"), "Saved")
        assertEquals(1, db.memoryVectorDao().count())

        repository.delete(id)

        assertNull(repository.byId(id))
        assertEquals(0, db.memoryVectorDao().count())
        assertTrue(repository.timeline(id).isEmpty())
    }
}
