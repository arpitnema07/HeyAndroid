package com.example.heyandroid.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallChoicesTest {

    @Test
    fun `a call by name is recognised`() {
        assertTrue(CallChoiceStore.looksLikeCall("call mom"))
        assertTrue(CallChoiceStore.looksLikeCall("Call my mom please"))
        assertTrue(CallChoiceStore.looksLikeCall("dial Aarti"))
        assertTrue(CallChoiceStore.looksLikeCall("ring the office"))
    }

    @Test
    fun `other sentences are not calls`() {
        assertFalse(CallChoiceStore.looksLikeCall("what's the time"))
        assertFalse(CallChoiceStore.looksLikeCall("recall my notes"))
        assertFalse(CallChoiceStore.looksLikeCall("I called the plumber"))
    }

    @Test
    fun `numbers and ordinals pick a choice`() {
        assertEquals(0, CallChoiceStore.indexOf("1"))
        assertEquals(1, CallChoiceStore.indexOf("the 2nd one"))
        assertEquals(2, CallChoiceStore.indexOf("third"))
        assertEquals(3, CallChoiceStore.indexOf("number four, please"))
    }

    @Test
    fun `cardinal words pick a choice`() {
        assertEquals(0, CallChoiceStore.indexOf("one"))
        assertEquals(2, CallChoiceStore.indexOf("the three of them"))
    }

    @Test
    fun `an unclear or out-of-range answer picks nothing`() {
        assertNull(CallChoiceStore.indexOf("all of them"))
        assertNull(CallChoiceStore.indexOf("12"))
        assertNull(CallChoiceStore.indexOf(""))
    }
}
