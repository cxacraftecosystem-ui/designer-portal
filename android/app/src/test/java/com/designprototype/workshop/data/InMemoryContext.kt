package com.designprototype.workshop.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * A `Context` with just enough of the platform under it for a [TokenStore] to remember a profile and
 * a token, and for the outbox to find its files directory — and nothing else.
 *
 * WHY THIS EXISTS. The unit-test `android.jar` answers every framework call with a default
 * (`isReturnDefaultValues = true`), so `ContextWrapper(null).getSharedPreferences(…)` is null and a
 * [TokenStore] built on it throws on its first read. That was fine while the password gate's tests
 * only asked a failure how to classify itself; the three behaviours pinned beside this file —
 * `syncOutbox` holding back, the join-card flusher holding and resuming, and the change-password
 * answer's token being adopted — are all decisions taken by READING the stored profile or token, so
 * they need a store that actually stores.
 *
 * [getFilesDir] is a real directory for the same reason in the other direction: left to the stub it
 * is null, and `File(null, "outbox")` is a RELATIVE path — the outbox would create its folders in
 * whatever directory Gradle ran the tests from.
 *
 * Everything else still answers the stub's default, deliberately. In particular there is no
 * `ConnectivityManager`, so `ConnectivityObserver.isOnline` is false here and no pass can reach for a
 * network that a JVM test does not have.
 */
internal class InMemoryContext(private val files: File) : ContextWrapper(null) {

    private val stores = HashMap<String, InMemoryPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = files

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        synchronized(stores) { stores.getOrPut(name.orEmpty()) { InMemoryPreferences() } }
}

/** `SharedPreferences` as a map. An edit lands when it is applied, as on a handset. */
internal class InMemoryPreferences : SharedPreferences {

    private val values = HashMap<String, Any?>()

    private fun <T> read(key: String?, fallback: T): T = synchronized(values) {
        @Suppress("UNCHECKED_CAST")
        if (key != null && values.containsKey(key)) values[key] as T else fallback
    }

    override fun getAll(): MutableMap<String, *> = synchronized(values) { HashMap(values) }

    override fun getString(key: String?, defValue: String?): String? = read(key, defValue)

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key, defValues)

    override fun getInt(key: String?, defValue: Int): Int = read(key, defValue)

    override fun getLong(key: String?, defValue: Long): Long = read(key, defValue)

    override fun getFloat(key: String?, defValue: Float): Float = read(key, defValue)

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read(key, defValue)

    override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Edit()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Edit : SharedPreferences.Editor {
        private val staged = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clearing = false

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) {
                // A null value is a removal, as the platform reads it.
                if (value == null) removed += key else staged[key] = value
            }
            return this
        }

        override fun putString(key: String?, value: String?) = stage(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) = stage(key, values?.toMutableSet())

        override fun putInt(key: String?, value: Int) = stage(key, value)

        override fun putLong(key: String?, value: Long) = stage(key, value)

        override fun putFloat(key: String?, value: Float) = stage(key, value)

        override fun putBoolean(key: String?, value: Boolean) = stage(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearing = true
            return this
        }

        override fun commit(): Boolean {
            synchronized(values) {
                if (clearing) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(staged)
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
