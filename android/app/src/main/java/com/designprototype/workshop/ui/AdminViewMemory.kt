package com.designprototype.workshop.ui

import android.content.Context

/*
 * THE ADMIN-VIEW SWITCH, REMEMBERED PER ACCOUNT ON THIS PHONE.
 *
 * It lived in a `remember` inside HomeScreen, so every restart and every sign-in put an admin back
 * on the default — master admins in admin view, other admins out of it — whatever they had chosen
 * (release smoke test 2026-10-17). Kept per account id because a handset is shared: one admin's
 * choice must not become the next one's. The same names as the documentation app's
 * ui/AdminViewMemory.kt.
 *
 * Only the REQUEST is stored. Whether the switch applies is still `canToggleAdminView && request`
 * at every read, so a stored ON cannot outlive a lowered role.
 */

private const val ADMIN_VIEW_PREFS = "design_workshop_admin_view"

/** The preference key for one account's switch. */
fun adminViewKey(userId: String): String = "admin_view:$userId"

/** What the switch opens as: the account's last choice, else the role default (master admin ON). */
fun restoredAdminView(stored: Boolean?, isMasterAdmin: Boolean): Boolean = stored ?: isMasterAdmin

/** The account's last choice, or null when it never flipped the switch on this device. */
fun readAdminView(context: Context, userId: String): Boolean? = runCatching {
    val prefs = context.getSharedPreferences(ADMIN_VIEW_PREFS, Context.MODE_PRIVATE)
    val key = adminViewKey(userId)
    if (prefs.contains(key)) prefs.getBoolean(key, false) else null
}.getOrNull()

fun writeAdminView(context: Context, userId: String, on: Boolean) {
    runCatching {
        context.getSharedPreferences(ADMIN_VIEW_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(adminViewKey(userId), on)
            .apply()
    }
}
