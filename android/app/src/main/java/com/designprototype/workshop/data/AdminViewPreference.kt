package com.designprototype.workshop.data

import android.content.Context

/**
 * THE ADMIN-VIEW SWITCH, REMEMBERED PER ACCOUNT ON THIS PHONE.
 *
 * It lived in a `remember` inside HomeScreen, so every restart and every sign-in put an admin back
 * on the default — master admins in admin view, other admins out of it — whatever they had chosen
 * (release smoke test 2026-10-17). Kept per account id because a handset is shared: one admin's
 * choice must not become the next one's.
 *
 * Only the REQUEST is stored. Whether the switch applies is still `canToggleAdminView && request`
 * at every read, so a stored ON cannot outlive a lowered role.
 */
object AdminViewPreference {
    private const val FILE = "admin_view"

    /** The stored key for [userId]'s choice. */
    internal fun key(userId: String): String = "admin_view.$userId"

    /** What the switch starts at: the account's saved choice, or the role's default when it has none. */
    fun initial(saved: Boolean?, isMasterAdmin: Boolean): Boolean = saved ?: isMasterAdmin

    /** The saved choice for [userId], or null when this account has never flipped the switch here. */
    fun read(context: Context, userId: String): Boolean? = runCatching {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(key(userId))) prefs.getBoolean(key(userId), false) else null
    }.getOrNull()

    fun write(context: Context, userId: String, on: Boolean) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key(userId), on).apply()
        }
    }
}
