package com.designprototype.workshop.data

import retrofit2.HttpException

fun Throwable.userMessage(fallback: String): String {
    val said = apiRefusal(fallback).message
    if (this is HttpException) {
        if (said == fallback || said.isBareReasonPhrase()) return statusSentence(code()) ?: fallback
        return said
    }
    if (said == fallback || said == CONNECTION_FAILED_SENTENCE) return said
    return if (said.readsAsSentenceForPeople()) said else fallback
}

fun Throwable.recordLoadFailure(noun: String): String {
    if (this is HttpException) {
        when (code()) {
            404 -> return "This $noun could not be found. It may have been deleted."
            403 -> return "Your account can't open this $noun."
        }
    }
    return userMessage("Unable to load this $noun.")
}

internal fun statusSentence(code: Int): String? = when (code) {
    401 -> "Your sign-in expired. Sign in again."
    403 -> "You don't have permission for that. Ask an admin."
    404 -> "That could not be found — it may have been deleted."
    408, 429 -> "The server is busy. Wait a moment and try again."
    in 500..599 -> "The server couldn't respond. Try again shortly."
    else -> null
}

private val BARE_REASON_PHRASES = setOf(
    "not found", "method not allowed", "internal server error", "bad gateway", "service unavailable",
    "gateway timeout", "forbidden", "unauthorized", "bad request", "too many requests",
)

private fun String.isBareReasonPhrase(): Boolean =
    trim().trimEnd('.').lowercase() in BARE_REASON_PHRASES || trim().startsWith("HTTP ")

private val INTERNAL_MARKERS = listOf(
    "Exception", "exception", "java.", "kotlin.", "android.", "okhttp", "NaN", "null", "=", "://",
    "errno", "EACCES", "ENOENT", "HTTP ", "resolve host", "failed to connect", "Failed to connect",
)

internal fun String.readsAsSentenceForPeople(): Boolean {
    val text = trim()
    if (text.length < 8 || !text.first().isUpperCase() || ' ' !in text) return false
    if (text.startsWith("Permission Denial")) return false
    return INTERNAL_MARKERS.none { it in text }
}
