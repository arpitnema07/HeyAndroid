package com.example.heyandroid.syncpad

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPadSettingsTest {

    private lateinit var settings: SyncPadSettings

    @Before
    fun setUp() {
        settings = SyncPadSettings.getInstance(
            ApplicationProvider.getApplicationContext<Context>()
        )
        settings.clear()
    }

    @Test
    fun `a domain without a scheme gets https`() {
        assertEquals("https://syncpad.app", SyncPadSettings.normalizeDomain("syncpad.app"))
    }

    @Test
    fun `a pasted url loses its trailing slash`() {
        assertEquals(
            "https://syncpad.app",
            SyncPadSettings.normalizeDomain("  https://syncpad.app/  ")
        )
    }

    @Test
    fun `a local http server is left alone`() {
        assertEquals(
            "http://192.168.1.5:3000",
            SyncPadSettings.normalizeDomain("http://192.168.1.5:3000/")
        )
    }

    @Test
    fun `an empty domain stays empty`() {
        assertEquals("", SyncPadSettings.normalizeDomain("   "))
    }

    @Test
    fun `saving a domain and key configures syncpad`() {
        assertFalse(settings.isConfigured)
        assertNull(settings.baseUrl)

        settings.save("syncpad.app/", "  spk_test  ")

        assertTrue(settings.isConfigured)
        assertEquals("https://syncpad.app", settings.baseUrl)
        assertEquals("spk_test", settings.apiKey.value)
    }

    @Test
    fun `clearing removes both values`() {
        settings.save("syncpad.app", "spk_test")

        settings.clear()

        assertFalse(settings.isConfigured)
        assertEquals("", settings.domain.value)
        assertEquals("", settings.apiKey.value)
    }
}
