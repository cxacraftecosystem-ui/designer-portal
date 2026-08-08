package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.json.Json

class TokenStore(context: Context) {
    /*
     * "field_repository_auth" is the product's PRE-REBRAND name and it deliberately survived the
     * "Design Prototype Workshop" rename. A SharedPreferences name is not a label — it is the file
     * name of an XML document already sitting in the app's data directory on every installed
     * device, and getSharedPreferences() with a new name silently returns a NEW, EMPTY file rather
     * than failing. So renaming it would not migrate anything: it would sign out every logged-in
     * user the moment they took the update, with no error, no log line and nothing on screen except
     * the login form. The users most affected are the ones who cannot easily sign back in — a
     * designer mid-workshop in a village with no signal, whose password is in an email they cannot
     * open. Cosmetic parity with the package name is not worth that. If this ever must move, the
     * migration is: read the old file, write the new one, delete the old, in one release that ships
     * BEFORE anything reads only the new name.
     */
    private val preferences = context.getSharedPreferences("field_repository_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun getToken(): String? = preferences.getString(KEY_TOKEN, null)

    fun setToken(token: String?) {
        preferences.edit().apply {
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    /** Cached profile so the session survives app minimise/resume and offline relaunches. */
    fun getUser(): UserDto? {
        val raw = preferences.getString(KEY_USER, null) ?: return null
        return runCatching { json.decodeFromString(UserDto.serializer(), raw) }.getOrNull()
    }

    fun setUser(user: UserDto?) {
        preferences.edit().apply {
            if (user == null) remove(KEY_USER) else putString(KEY_USER, json.encodeToString(UserDto.serializer(), user))
        }.apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_TOKEN = "jwt"
        const val KEY_USER = "user"
    }
}
