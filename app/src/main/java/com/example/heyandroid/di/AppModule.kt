package com.example.heyandroid.di

import android.content.Context
import com.example.heyandroid.data.AppDatabase
import com.example.heyandroid.data.capture.CaptureDao
import com.example.heyandroid.data.chat.MessageDao
import com.example.heyandroid.data.conversation.ConversationDao
import com.example.heyandroid.data.memory.MemoryDao
import com.example.heyandroid.data.memory.MemoryEventDao
import com.example.heyandroid.data.memory.MemoryVectorDao
import com.example.heyandroid.data.turn.TurnLogDao
import com.example.heyandroid.llm.LlmSettingsStore
import com.example.heyandroid.memory.LocalEmbedder
import com.example.heyandroid.tools.CallCustomApiTool
import com.example.heyandroid.tools.CalendarEventTool
import com.example.heyandroid.tools.ClockTool
import com.example.heyandroid.tools.CustomToolStore
import com.example.heyandroid.tools.EditNoteTool
import com.example.heyandroid.tools.ListMemoriesTool
import com.example.heyandroid.tools.McpTool
import com.example.heyandroid.tools.SaveMemoryTool
import com.example.heyandroid.tools.SearchContactsTool
import com.example.heyandroid.tools.SearchMemoryTool
import com.example.heyandroid.tools.StartCallTool
import com.example.heyandroid.tools.SyncPadImageTool
import com.example.heyandroid.tools.SyncPadScreenshotTool
import com.example.heyandroid.tools.SyncPadTextTool
import com.example.heyandroid.tools.Tool
import com.example.heyandroid.tools.ToolRegistry
import com.example.heyandroid.tools.UpdateMemoryTool
import com.example.heyandroid.tools.WebSearchTool
import com.example.heyandroid.work.CaptureWorkScheduler
import com.example.heyandroid.work.WorkManagerCaptureScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-wide singletons: the database, its DAOs, user LLM settings, the memory
 * store, its embedder, the tool registry and the capture scheduler.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun captureDao(database: AppDatabase): CaptureDao = database.captureDao()

    @Provides
    fun messageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun conversationDao(database: AppDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun memoryDao(database: AppDatabase): MemoryDao = database.memoryDao()

    @Provides
    fun memoryEventDao(database: AppDatabase): MemoryEventDao = database.memoryEventDao()

    @Provides
    fun memoryVectorDao(database: AppDatabase): MemoryVectorDao = database.memoryVectorDao()

    @Provides
    fun turnLogDao(database: AppDatabase): TurnLogDao = database.turnLogDao()

    @Provides
    @Singleton
    fun llmSettings(@ApplicationContext context: Context): LlmSettingsStore =
        LlmSettingsStore.getInstance(context)

    @Provides
    @Singleton
    fun customToolStore(@ApplicationContext context: Context): CustomToolStore =
        CustomToolStore.getInstance(context)

    /** One embedder for the whole app so the vector width never drifts mid-run. */
    @Provides
    @Singleton
    fun localEmbedder(): LocalEmbedder = LocalEmbedder()

    @Provides
    @Singleton
    fun toolRegistry(
        saveMemory: SaveMemoryTool,
        searchMemory: SearchMemoryTool,
        listMemories: ListMemoriesTool,
        updateMemory: UpdateMemoryTool,
        editNote: EditNoteTool,
        searchContacts: SearchContactsTool,
        startCall: StartCallTool,
        calendarEvent: CalendarEventTool,
        clock: ClockTool,
        callCustomApi: CallCustomApiTool,
        webSearch: WebSearchTool,
        mcp: McpTool,
        syncPadText: SyncPadTextTool,
        syncPadScreenshot: SyncPadScreenshotTool,
        syncPadImage: SyncPadImageTool
    ): ToolRegistry {
        val tools: List<Tool> = listOf(
            saveMemory,
            searchMemory,
            listMemories,
            updateMemory,
            webSearch,
            syncPadText,
            syncPadScreenshot,
            syncPadImage,
            editNote,
            searchContacts,
            startCall,
            calendarEvent,
            clock,
            callCustomApi,
            mcp
        )
        return ToolRegistry(tools)
    }

    @Provides
    @Singleton
    fun captureWorkScheduler(
        scheduler: WorkManagerCaptureScheduler
    ): CaptureWorkScheduler = scheduler
}
