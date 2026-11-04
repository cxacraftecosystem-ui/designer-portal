package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.accountHasPassword
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val FIRST_LOGIN_TRACKED_SINCE: Instant = Instant.parse("2026-08-30T17:00:00Z")

private val PROVIDER_NAMES = mapOf("GOOGLE" to "Google", "MICROSOFT" to "Microsoft", "YAHOO" to "Yahoo")

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
