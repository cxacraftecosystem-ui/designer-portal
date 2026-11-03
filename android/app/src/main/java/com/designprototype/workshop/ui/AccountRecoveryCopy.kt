package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto

const val FORGOT_PASSWORD_HEADING = "Forgot your password?"

const val FORGOT_PASSWORD_INTRO = "Enter your sign-in email. If it has a password, we'll send a reset link (single use, valid 2 hours)."

const val FORGOT_PASSWORD_TWO_STEP_NOTE = "You'll still need your authenticator app to sign in."

const val FORGOT_PASSWORD_NO_EMAIL_NOTE = "No email? Ask an admin for a link."

const val FORGOT_PASSWORD_FALLBACK = "If that address has an account, a reset link is on its way."

fun forgotPasswordEmailProblem(email: String): String? {
    val cleaned = email.trim()
    if (cleaned.isEmpty()) return "Enter your email address."
    val looksUsable = cleaned.count { it == '@' } == 1 &&
        cleaned.substringBefore('@').isNotBlank() &&
        cleaned.substringAfter('@').contains('.')
    return if (looksUsable) null else "That does not look like an email address."
}

fun forgotPasswordShown(serverMessage: String?): String =
    serverMessage?.trim()?.takeIf { it.isNotEmpty() } ?: FORGOT_PASSWORD_FALLBACK

fun passwordCardTitle(hasPassword: Boolean): String =
    if (hasPassword) "Change your password" else "Set a password"

fun passwordCardIntro(hasPassword: Boolean, user: UserDto?): String =
    if (hasPassword) {
        "Enter your current password, then a new one. $PASSWORD_CHANGE_SESSIONS"
    } else {
        val provider = providerLabel(user?.authProvider)
        "You sign in with $provider. Add a password to sign in either way."
    }

fun passwordSavedLine(hadPassword: Boolean): String =
    if (hadPassword) {
        "Password changed. $PASSWORD_CHANGE_SESSIONS"
    } else {
        "Password set. Sign in with email and password, or as before."
    }

fun providerLabel(authProvider: String?): String = when (authProvider?.trim()?.uppercase()) {
    "GOOGLE" -> "Google"
    "MICROSOFT" -> "Microsoft"
    "YAHOO" -> "Yahoo"
    else -> "another sign-in"
}

fun firstPasswordConfirmNote(provider: String?): String =
    "When you tap Set password, sign in with ${providerLabel(provider)} again to confirm it's you."

fun firstPasswordConfirmCancelled(provider: String?): String =
    "Signing in with ${providerLabel(provider)} didn't finish, so no password was set. Try again."

fun firstPasswordNeedsCode(hasPassword: Boolean, user: UserDto?): Boolean =
    !hasPassword && user?.twoFactorEnabled == true

const val FIRST_PASSWORD_CODE_LABEL = "Code from your authenticator app"
const val FIRST_PASSWORD_CODE_NOTE = "Two-step verification is on for your account."

const val HELP_THEM_BACK_IN_HEADING = "Help them back in"

fun emailedLinkLine(name: String, email: String): String =
    "Password link sent to $name at $email (single use)."

fun temporaryPasswordIntro(name: String): String =
    "$name uses this once, then chooses their own. Send it to them yourself. They'll be signed out everywhere."

fun temporaryPasswordSetLine(name: String): String =
    "Temporary password set for $name."

fun temporaryPasswordProblem(password: String): String? {
    val length = password.codePointCount(0, password.length)
    return when {
        length < MIN_PASSWORD_LENGTH -> passwordRuleLine()
        length > MAX_PASSWORD_LENGTH -> "At most $MAX_PASSWORD_LENGTH characters."
        password.isBlank() -> passwordRuleLine()
        else -> null
    }
}

fun signOutEverywhereTitle(name: String): String = "Sign $name out of every device?"

fun signOutEverywhereBody(name: String): String =
    "$name will have to sign in again on every device. Their password, two-step and access stay as they are."

fun signedOutEverywhereLine(name: String): String = "$name has been signed out of every device."

fun emailLinkOffered(target: UserDto, mailAvailable: Boolean): Boolean =
    mailAvailable && passwordLinkOffered(target)

enum class AccessSignIn { PROVIDER, PASSWORD }

const val ACCESS_SIGN_IN_QUESTION = "How will they sign in?"
const val ACCESS_SIGN_IN_PROVIDER = "With Google, Microsoft or Yahoo"
const val ACCESS_SIGN_IN_PASSWORD = "With a password"
const val ACCESS_MUST_CHANGE_LABEL = "Require a new password at first sign-in"

fun accessSignInNote(method: AccessSignIn): String = when (method) {
    AccessSignIn.PROVIDER ->
        "Account is created at their first sign-in."
    AccessSignIn.PASSWORD ->
        "Account is created now with this password. Send it to them yourself."
}

fun accessAddProblem(method: AccessSignIn, password: String, fullName: String): String? {
    if (method == AccessSignIn.PROVIDER) return null
    if (fullName.isBlank()) return "Enter their full name."
    return temporaryPasswordProblem(password)
}

fun accessAddedLine(email: String, method: AccessSignIn): String = when (method) {
    AccessSignIn.PROVIDER -> "$email may sign in."
    AccessSignIn.PASSWORD -> "$email can sign in with your password."
}
