package com.example.heyandroid.router

import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordRouterTest {

    @Test
    fun `note prefix creates a note`() {
        val route = KeywordRouter.route("note: buy milk")
        assertEquals(Route.CreateNote(title = "buy milk", body = ""), route)
    }

    @Test
    fun `all supported prefixes are recognised`() {
        val expected = Route.CreateNote(title = "call the dentist", body = "")
        listOf(
            "note: call the dentist",
            "note call the dentist",
            "new note call the dentist",
            "remember call the dentist"
        ).forEach { input ->
            assertEquals(input, expected, KeywordRouter.route(input))
        }
    }

    @Test
    fun `matching is case insensitive`() {
        // Prefix matching ignores case, but the user's own casing is preserved.
        assertEquals(
            Route.CreateNote(title = "Standup at 9", body = ""),
            KeywordRouter.route("Note: Standup at 9")
        )
    }

    @Test
    fun `multiline input becomes title plus body`() {
        val route = KeywordRouter.route("note: shopping\nmilk\neggs")
        assertEquals(Route.CreateNote(title = "shopping", body = "milk\neggs"), route)
    }

    @Test
    fun `text without a prefix is unhandled`() {
        assertEquals(Route.Unhandled, KeywordRouter.route("what is on my screen"))
    }

    @Test
    fun `prefix without content is unhandled`() {
        assertEquals(Route.Unhandled, KeywordRouter.route("note:"))
        assertEquals(Route.Unhandled, KeywordRouter.route("new note   "))
    }

    @Test
    fun `blank input is unhandled`() {
        assertEquals(Route.Unhandled, KeywordRouter.route(""))
        assertEquals(Route.Unhandled, KeywordRouter.route("   "))
    }

    @Test
    fun `long first line is truncated for the title`() {
        val long = "a".repeat(120)
        val route = KeywordRouter.route("note: $long")
        assertEquals(Route.CreateNote(title = "a".repeat(80), body = ""), route)
    }
}
