package com.designprototype.workshop.data

import retrofit2.HttpException

/**
 * THE ONE PLACE A FAILURE BECOMES WORDS ON SCREEN.
 *
 * Every screen used to write `it.message ?: "Unable to …"`, which hands the reader whatever the
 * throwable happened to carry: Retrofit's "HTTP 404 Not Found", the platform's "Unable to resolve
 * host api.example.org", or an engine's "edge.x was set to NaN". None of those tells a designer in a
 * village what to do next (UX audit 2026-10-16, DA-04).
 *
 * Built on [apiRefusal], so a sentence the SERVER wrote for people (an Aadhaar clash, a late
 * submission) still reaches them verbatim. What changes is everything else:
 *
 * - no answer at all (no signal, DNS, timeout) → [CONNECTION_FAILED_SENTENCE];
 * - an answer with nothing readable in it, or only a bare reason phrase ("Not Found") → a plain
 *   sentence chosen by the status code;
 * - an exception thrown on the device → its message only when it already reads as a sentence
 *   written for a person, otherwise the caller's [fallback].
 *
 * Reads the buffered error body, like [apiRefusal]: call it once per failure.
 * `UserMessageSourceTest` refuses `.message ?: "` in the UI sources so a new screen cannot go back.
 */
fun Throwable.userMessage(fallback: String): String {
    val said = apiRefusal(fallback).message
    if (this is HttpException) {
        if (said == fallback || said.isBareReasonPhrase()) return statusSentence(code()) ?: fallback
        return said
    }
    if (said == fallback || said == CONNECTION_FAILED_SENTENCE) return said
    return if (said.readsAsSentenceForPeople()) said else fallback
}

/** A plain sentence for a status code whose answer carried nothing readable, or null to keep the caller's. */
internal fun statusSentence(code: Int): String? = when (code) {
    401 -> "Your sign-in expired. Sign in again."
    403 -> "You don't have permission for that. Ask an admin."
    404 -> "That could not be found — it may have been deleted."
    408, 429 -> "The server is busy. Wait a moment and try again."
    in 500..599 -> "The server couldn't respond. Try again shortly."
    else -> null
}

/** HTTP's own reason phrases, which FastAPI echoes as `detail` for its built-in 404 and 405. */
private val BARE_REASON_PHRASES = setOf(
    "not found", "method not allowed", "internal server error", "bad gateway", "service unavailable",
    "gateway timeout", "forbidden", "unauthorized", "bad request", "too many requests",
)

private fun String.isBareReasonPhrase(): Boolean =
    trim().trimEnd('.').lowercase() in BARE_REASON_PHRASES || trim().startsWith("HTTP ")

/** Words that only ever appear in text written for a developer, never in a sentence for a designer. */
private val INTERNAL_MARKERS = listOf(
    "Exception", "exception", "java.", "kotlin.", "android.", "okhttp", "NaN", "null", "=", "://",
    "errno", "EACCES", "ENOENT", "HTTP ", "resolve host", "failed to connect", "Failed to connect",
)

/**
 * Does this exception message already read as a sentence for a person? The app throws
 * `IllegalStateException("That file is larger than …")` on purpose and those should reach the
 * reader; the platform throws "start failed." and "Permission Denial: …" and those should not.
 */
internal fun String.readsAsSentenceForPeople(): Boolean {
    val text = trim()
    if (text.length < 8 || !text.first().isUpperCase() || ' ' !in text) return false
    if (text.startsWith("Permission Denial")) return false
    return INTERNAL_MARKERS.none { it in text }
}
