package com.example.heyandroid.llm

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] for plain JVM tests: no Android runtime, no
 * Robolectric, just a map that behaves like the real thing for get/put/remove.
 */
class FakeSharedPreferences(
    initial: Map<String, Any?> = emptyMap()
) : SharedPreferences {

    private val values: MutableMap<String, Any?> = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { updates[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { updates[key] = values?.toMutableSet() }

        override fun putInt(key: String, value: Int) = apply { updates[key] = value }

        override fun putLong(key: String, value: Long) = apply { updates[key] = value }

        override fun putFloat(key: String, value: Float) = apply { updates[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value }

        override fun remove(key: String) = apply { removals += key }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) {
                values.clear()
                clearAll = false
            }
            removals.forEach { values.remove(it) }
            removals.clear()
            values.putAll(updates)
            updates.clear()
        }
    }
}
