package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.accountHasPassword
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * HOW AN ACCOUNT SIGNS IN, IN WORDS, for Manage users (2026-10-17). The card used to say only
 * "Password: Set / Not set", which cannot tell an administrator whether somebody has ever got in, or
 * whether a Google-only account needs a password link. These say it the way the web's users page
 * does (`frontend/app/(protected)/users/accountAdmin.ts`, `accountStatus`), in the same order of
 * precedence. Pure, pinned by `AccountSignInCopyTest`.
 */

/**
 * The moment the server began recording first sign-ins (`credential_links.FIRST_LOGIN_TRACKED_SINCE`).
 * An account older than this with no recorded sign-in may well have signed in before it, so it is
 * never called "never signed in".
 */
internal val FIRST_LOGIN_TRACKED_SINCE: Instant = Instant.parse("2026-08-30T17:00:00Z")

private val PROVIDER_NAMES = mapOf("GOOGLE" to "Google", "MICROSOFT" to "Microsoft", "YAHOO" to "Yahoo")

/** "Password", "Google and a password", "Google only", or "No password set". */
fun accountSignInMethod(user: UserDto): String {
    val provider = PROVIDER_NAMES[user.authProvider?.uppercase()]
    val password = accountHasPassword(user)
    return when {
        provider != null && password -> "$provider and a password"
        provider != null -> "$provider only"
        password -> "Password"
        else -> "No password set"
    }
}

/** "Last signed in 17 Oct 2026", "Signed in, first on …", "Never signed in" or "No sign-in recorded". */
fun accountLastSignIn(user: UserDto, zone: ZoneId = ZoneId.systemDefault()): String {
    readableDate(user.lastLoginAt, zone)?.let { return "Last signed in $it" }
    readableDate(user.firstLoginAt, zone)?.let { return "Signed in, first on $it" }
    val created = parseInstant(user.createdAt)
    return if (created != null && !created.isBefore(FIRST_LOGIN_TRACKED_SINCE)) {
        "Never signed in"
    } else {
        "No sign-in recorded"
    }
}

/**
 * The one line under the name on the collapsed card: what an administrator may have to act on first.
 * A standing password change, then an account with no way in by password, then the sign-in history.
 */
fun accountSignInStatus(user: UserDto, zone: ZoneId = ZoneId.systemDefault()): String = when {
    user.mustChangePassword == true -> "Must choose a new password"
    !accountHasPassword(user) && PROVIDER_NAMES.containsKey(user.authProvider?.uppercase()) ->
        "${accountSignInMethod(user)} · ${accountLastSignIn(user, zone)}"
    !accountHasPassword(user) -> "No password set"
    else -> accountLastSignIn(user, zone)
}

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun parseInstant(value: String?): Instant? =
    value?.takeIf { it.isNotBlank() }?.let { text ->
        runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).atZone(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
    }

private fun readableDate(value: String?, zone: ZoneId): String? =
    parseInstant(value)?.atZone(zone)?.format(DAY)
