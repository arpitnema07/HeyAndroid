package com.example.heyandroid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.heyandroid.data.capture.Capture
import com.example.heyandroid.data.capture.CaptureDao
import com.example.heyandroid.data.chat.Message
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.conversation.Conversation
import com.example.heyandroid.data.conversation.ConversationDao
import com.example.heyandroid.data.memory.Memory
import com.example.heyandroid.data.memory.MemoryDao
import com.example.heyandroid.data.memory.MemoryEvent
import com.example.heyandroid.data.memory.MemoryEventDao
import com.example.heyandroid.data.memory.MemoryVector
import com.example.heyandroid.data.memory.MemoryVectorDao
import com.example.heyandroid.data.turn.TurnLog
import com.example.heyandroid.data.turn.TurnLogDao

@Database(
    entities = [
        Capture::class,
        Message::class,
        Conversation::class,
        Memory::class,
        MemoryEvent::class,
        MemoryVector::class,
        TurnLog::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun captureDao(): CaptureDao
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryEventDao(): MemoryEventDao
    abstract fun memoryVectorDao(): MemoryVectorDao
    abstract fun turnLogDao(): TurnLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Adds the notes table. Captures already on the device are kept as they are. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        /** Adds the conversation table. Captures and notes are untouched. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Adds conversation history on top of messages, plus the external memory
         * tables (memories, their timeline and their vectors). Existing messages
         * are grouped into one "Earlier" conversation so nothing disappears.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`messageCount` INTEGER NOT NULL)"
                )

                db.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `conversationId` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` " +
                        "ON `messages` (`conversationId`)"
                )

                // An aggregate SELECT still returns a row when the table is empty,
                // so the backfill is guarded rather than filtered.
                val hasMessages = db.query("SELECT COUNT(*) FROM `messages`").use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) > 0
                }
                if (hasMessages) {
                    db.execSQL(
                        "INSERT INTO `conversations` (`title`, `createdAt`, `updatedAt`, `messageCount`) " +
                            "SELECT 'Earlier', MIN(`timestamp`), MAX(`timestamp`), COUNT(*) " +
                            "FROM `messages`"
                    )
                    db.execSQL(
                        "UPDATE `messages` SET `conversationId` = " +
                            "(SELECT `id` FROM `conversations` LIMIT 1) WHERE `conversationId` = 0"
                    )
                }

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memories` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`subject` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`collection` TEXT NOT NULL, " +
                        "`progress` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`dueAt` INTEGER, " +
                        "`source` TEXT NOT NULL, " +
                        "`notifiedAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_subject` ON `memories` (`subject`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_kind` ON `memories` (`kind`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`memoryId` INTEGER NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_events_memoryId` " +
                        "ON `memory_events` (`memoryId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_vectors` (" +
                        "`memoryId` INTEGER NOT NULL, " +
                        "`vector` BLOB NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "PRIMARY KEY(`memoryId`))"
                )
            }
        }

        /**
         * Images attached to memories, structured turn logs for the Log tab, and
         * the removal of the dead `notes` table: notes have been memory rows since
         * schema 4, so the table only held orphaned rows.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `imagePath` TEXT")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `imageName` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `turn_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`provider` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`steps` INTEGER NOT NULL, " +
                        "`toolNames` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`error` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_turn_logs_timestamp` " +
                        "ON `turn_logs` (`timestamp`)"
                )
                db.execSQL("DROP TABLE IF EXISTS `notes`")
            }
        }

        /**
         * A memory can now carry several images. The old single-path column is kept
         * and copied over, so existing rows show their image exactly as before.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `images` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "UPDATE `memories` SET `images` = `imagePath` " +
                        "WHERE `imagePath` IS NOT NULL AND `imagePath` != ''"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "heyandroid.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
