package com.example.heyandroid.syncpad

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where SyncPad lives and how this phone is allowed in.
 *
 * Both values are app-private SharedPreferences, like the LLM key: nothing is
 * uploaded anywhere except to the domain the user typed.
 */
class SyncPadSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("syncpad_settings", Context.MODE_PRIVATE)

    private val _domain = MutableStateFlow(normalizeDomain(prefs.getString(KEY_DOMAIN, "").orEmpty()))
    val domain: StateFlow<String> = _domain

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey

    val isConfigured: Boolean
        get() = _domain.value.isNotBlank() && _apiKey.value.isNotBlank()

    /** Origin with no trailing slash, or null until a domain is saved. */
    val baseUrl: String?
        get() = _domain.value.takeIf { it.isNotBlank() }

    fun save(domain: String, apiKey: String) {
        val cleanDomain = normalizeDomain(domain)
        val cleanKey = apiKey.trim()
        prefs.edit()
            .putString(KEY_DOMAIN, cleanDomain)
            .putString(KEY_API_KEY, cleanKey)
            .apply()
        _domain.value = cleanDomain
        _apiKey.value = cleanKey
    }

    fun clear() {
        prefs.edit().remove(KEY_DOMAIN).remove(KEY_API_KEY).apply()
        _domain.value = ""
        _apiKey.value = ""
    }

    companion object {
        private const val KEY_DOMAIN = "domain"
        private const val KEY_API_KEY = "api_key"

        @Volatile
        private var INSTANCE: SyncPadSettings? = null

        fun getInstance(context: Context): SyncPadSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncPadSettings(context).also { INSTANCE = it }
            }

        /**
         * Accepts `syncpad.vercel.app`, `https://syncpad.vercel.app/` and
         * `http://192.168.1.5:3000` alike, so the field does not need a scheme but
         * a local dev server still works.
         */
        fun normalizeDomain(input: String): String {
            var value = input.trim()
            if (value.isEmpty()) return ""
            value = value.trimEnd('/')
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "https://$value"
            }
            return value
        }
    }
}
