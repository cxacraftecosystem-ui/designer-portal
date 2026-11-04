package com.designprototype.workshop.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

internal class InMemoryContext(private val files: File) : ContextWrapper(null) {

    private val stores = HashMap<String, InMemoryPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = files

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        synchronized(stores) { stores.getOrPut(name.orEmpty()) { InMemoryPreferences() } }
}

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
