package com.designprototype.workshop.data

import java.util.Locale

/**
 * Which of the dictation languages this handset can actually recognise WITHOUT a connection, and
 * which of the rest it could fetch if the designer asked it to.
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * `DwDictation.kt` offers nineteen languages. On the Galaxy M32 the test fleet runs on, the HINDI
 * pack is not installed and the on-device recogniser answers ERROR_LANGUAGE_UNAVAILABLE (13) on
 * every attempt — see docs/DICTATION-DEVICE-VERIFICATION.md, which has the device's own logs. The
 * fix that shipped catches the 13 and falls back to the NETWORK recogniser, which works, and works
 * only while there is signal. This app's entire premise is a courtyard without one.
 *
 * Odia — the language of the state these workshops are run in — is no more likely to be installed
 * than Hindi. So is every other one of the eighteen. The app therefore has to stop finding out by
 * failing, and start ASKING, which is what `SpeechRecognizer.checkRecognitionSupport` is for.
 *
 * ── WHY THE DECISION IS PURE ──────────────────────────────────────────────────────────────────
 *
 * Everything below is plain Kotlin over plain strings: no Context, no `android.speech`, no Compose.
 * That is what lets the desktop JVM test it (`DwLanguagePackTest`), and this repository's whole
 * catalogue of shipped defects says the untestable half is the half that is wrong. The Android call
 * that produces a [DwRecognitionSupport] lives in `ui/designworkshop/DwLanguagePackUi.kt`; the
 * decision about what that answer MEANS lives here.
 *
 * ── THE HONEST-UNKNOWN RULE, WHICH IS THE POINT OF THE WHOLE FILE ─────────────────────────────
 *
 * `checkRecognitionSupport` arrived in API 33. minSdk here is 26, and a large share of the field
 * fleet is Android 8 and 9. On those handsets there is NO way to ask, and the only truthful answer
 * is [DwPackState.UNKNOWN] — said in those words on screen. Guessing "installed" would send a
 * designer into a village believing they can dictate; guessing "not installed" would nag them to
 * download a pack they may already have. A wrong claim here is worse than no claim, so nothing
 * below ever infers a state it was not told.
 */

/** What this device can do with one dictation language, right now. */
enum class DwPackState {
    /** The offline pack is on the phone. Dictation in this language works with no signal at all. */
    INSTALLED,

    /**
     * The platform has ACCEPTED a download for this language and has not finished it.
     *
     * `RecognitionSupport.getPendingOnDeviceLanguages()` — "scheduled for download". Distinct from
     * [DOWNLOADABLE] because asking again achieves nothing: the request is already in the queue, and
     * a second "Download" button next to a pack that is already coming is how a designer on prepaid
     * data pays twice for one file.
     */
    DOWNLOADING,

    /**
     * This device's offline engine supports the language but the pack is not on the phone.
     *
     * `RecognitionSupport.getSupportedOnDeviceLanguages()` — "supported but need to be downloaded
     * before use". This is the ONLY state a download may be offered for.
     */
    DOWNLOADABLE,

    /**
     * No offline pack exists for this language on this device, but the NETWORK engine reports it.
     *
     * Worth its own state rather than being folded into [UNSUPPORTED]: the designer can still
     * dictate in it at the guest house in the evening, and telling them the language is unsupported
     * would retire a control that works four hours a day.
     */
    NETWORK_ONLY,

    /** This device's recogniser cannot do this language at all, offline or on. Nothing to offer. */
    UNSUPPORTED,

    /**
     * The device was not asked, or would not answer.
     *
     * API < 33 (no `checkRecognitionSupport`), no recogniser at all, or the platform answered
     * ERROR_CANNOT_CHECK_SUPPORT. Rendered as the word "unknown", never as a guess.
     */
    UNKNOWN,
}

/**
 * A pure mirror of `android.speech.RecognitionSupport`, taken at one moment.
 *
 * Four lists, named as the platform names them, because renaming them here would put a translation
 * step between the documentation and the code — and the four have genuinely different meanings that
 * are easy to conflate ("supported" is the one that is NOT on the phone).
 */
data class DwRecognitionSupport(
    /** Ready for use on this device. */
    val installedOnDevice: List<String> = emptyList(),
    /** Scheduled for download. */
    val pendingOnDevice: List<String> = emptyList(),
    /** Supported, but must be downloaded before use. */
    val supportedOnDevice: List<String> = emptyList(),
    /**
     * Served by a remote implementation.
     *
     * A recogniser built with `createOnDeviceSpeechRecognizer` is documented to return an EMPTY list
     * here, and that is the recogniser this app asks whenever the device has one — so an empty list
     * means "not asked", not "nothing online". [DwPackState.NETWORK_ONLY] can therefore only ever be
     * produced when some engine actually answered with online languages.
     */
    val online: List<String> = emptyList(),
) {
    /**
     * True when every list is empty, which is indistinguishable from not having asked.
     *
     * A recogniser that answers with four empty lists is a recogniser that told us nothing. Reading
     * that as "this phone supports none of the nineteen" would paint nineteen rows of "not supported
     * on this phone" onto a handset that may well have Hindi installed — the exact wrong claim this
     * file exists to prevent. It resolves to [DwPackState.UNKNOWN] instead.
     */
    val toldUsNothing: Boolean
        get() = installedOnDevice.isEmpty() && pendingOnDevice.isEmpty() &&
            supportedOnDevice.isEmpty() && online.isEmpty()
}

/**
 * Normalise a BCP-47 tag for comparison: trim, `_` to `-`, lower case.
 *
 * Recognisers are not consistent about either the separator or the case — `hi_IN`, `hi-IN` and
 * `hi-in` have all been seen from OEM services — and a case-sensitive `contains` would report a pack
 * that IS installed as missing, then offer a download for a file already on the phone.
 */
internal fun dwNormalizeLanguageTag(raw: String): String =
    raw.trim().replace('_', '-').lowercase(Locale.ROOT)

/**
 * Does [reported] (a tag the platform listed) cover [wanted] (a tag from `DW_DICTATION_LANGUAGES`)?
 *
 * Exact match after normalisation, plus ONE deliberate widening: a device that reports a bare
 * language with no region (`hi`) is reporting a pack that serves every region of it, so it covers
 * `hi-IN`.
 *
 * THE WIDENING DOES NOT RUN THE OTHER WAY, and that asymmetry is the whole care in this function.
 * `en-US` installed does not mean `en-IN` is installed — they are different packs, and English
 * (India) transcribed by a US model is exactly the silent-wrong-output failure `EXTRA_LANGUAGE_
 * PREFERENCE` exists to avoid in DwDictation. Matching on the primary subtag in both directions
 * would claim eight of the nineteen were installed on a handset carrying only `en-US`.
 */
private fun dwTagCovers(reported: String, wanted: String): Boolean {
    val r = dwNormalizeLanguageTag(reported)
    val w = dwNormalizeLanguageTag(wanted)
    if (r.isEmpty() || w.isEmpty()) return false
    if (r == w) return true
    return !r.contains('-') && r == w.substringBefore('-')
}

private fun List<String>.coversTag(wanted: String): Boolean = any { dwTagCovers(it, wanted) }

/**
 * The state of ONE language, given what the device said.
 *
 * [support] `null` means the device was not asked or would not answer — [DwPackState.UNKNOWN], every
 * time, with no fallback reasoning. The order of the checks is the order of precedence the platform
 * lists imply: a language can appear in more than one list (installed AND pending, when an update is
 * queued for a pack that already works), and the state a designer needs in that case is the one that
 * describes what dictation will do RIGHT NOW.
 */
fun dwPackState(tag: String, support: DwRecognitionSupport?): DwPackState {
    if (support == null || support.toldUsNothing) return DwPackState.UNKNOWN
    return when {
        support.installedOnDevice.coversTag(tag) -> DwPackState.INSTALLED
        support.pendingOnDevice.coversTag(tag) -> DwPackState.DOWNLOADING
        support.supportedOnDevice.coversTag(tag) -> DwPackState.DOWNLOADABLE
        support.online.coversTag(tag) -> DwPackState.NETWORK_ONLY
        else -> DwPackState.UNSUPPORTED
    }
}

/**
 * Every language's state, in the order given, as one pass over one answer.
 *
 * A `LinkedHashMap` because the nineteen have a deliberate order (Hindi first, matching the web
 * form's default) and a settings list that reordered itself by state would move the row a designer
 * is reaching for as they reach for it.
 */
fun dwPackStates(tags: List<String>, support: DwRecognitionSupport?): Map<String, DwPackState> {
    val states = LinkedHashMap<String, DwPackState>(tags.size)
    tags.forEach { tag -> states[tag] = dwPackState(tag, support) }
    return states
}

/** What this handset's connection can pay for. Decided by the caller; used for the wording. */
enum class DwConnection {
    /** No validated internet. Nothing can be fetched, and nothing may be offered. */
    NONE,

    /** A connection the owner pays for by the megabyte — mobile data, or a hotspot. */
    METERED,

    /** Wi-Fi that the platform reports as unmetered. */
    UNMETERED,
}

/** What may be offered for one language right now — the decision behind every download control. */
enum class DwPackOffer {
    /** Offer the download. The ONLY value that may draw a button that spends data. */
    DOWNLOAD,

    /**
     * The pack could be fetched, but there is no connection. Say so; do NOT draw a download button.
     *
     * A control that cannot work is worse than an absent one: a designer in a courtyard taps it,
     * nothing happens, and they conclude the feature is broken rather than that they are offline.
     */
    NO_CONNECTION,

    /** Already on the phone. Nothing to offer, and saying "download" would be a lie. */
    INSTALLED,

    /** Already queued with the platform. Asking again would pay twice for one file. */
    IN_PROGRESS,

    /** This device cannot do it offline at all. Nothing any button could change. */
    UNAVAILABLE,

    /** We do not know, and will not pretend to (API < 33, or the check failed). */
    UNKNOWN,
}

/**
 * Whether a download may be offered for a language in [state] on a [connection].
 *
 * NOTHING AUTO-DOWNLOADS ANYWHERE IN THIS FEATURE. This function only ever decides whether a control
 * is drawn; a pack is fetched exclusively by a designer's own tap on it. The app runs on prepaid
 * mobile data in a district town, and a background download of nineteen speech models is a bill.
 */
fun dwPackOffer(state: DwPackState, connection: DwConnection): DwPackOffer = when (state) {
    DwPackState.INSTALLED -> DwPackOffer.INSTALLED
    DwPackState.DOWNLOADING -> DwPackOffer.IN_PROGRESS
    DwPackState.DOWNLOADABLE ->
        if (connection == DwConnection.NONE) DwPackOffer.NO_CONNECTION else DwPackOffer.DOWNLOAD
    // Both mean "no offline pack is coming". NETWORK_ONLY keeps its own state so the UI can add the
    // consolation that the network engine still serves it, but neither is downloadable.
    DwPackState.NETWORK_ONLY, DwPackState.UNSUPPORTED -> DwPackOffer.UNAVAILABLE
    DwPackState.UNKNOWN -> DwPackOffer.UNKNOWN
}

// ---------------------------------------------------------------------------------------------
// The words. Android owns wording in this repository, and one language's state must read the same
// on the settings list, in the first-run offer and in the dialog that opens when a language is
// picked — three surfaces, one string, so they cannot drift into three different claims.
// ---------------------------------------------------------------------------------------------

/** The short state, for the end of a row. Sentence-free; the row's language name precedes it. */
fun dwPackStateLabel(state: DwPackState): String = when (state) {
    DwPackState.INSTALLED -> "Works offline"
    DwPackState.DOWNLOADING -> "Downloading"
    DwPackState.DOWNLOADABLE -> "Not downloaded"
    DwPackState.NETWORK_ONLY -> "Needs signal"
    DwPackState.UNSUPPORTED -> "Not on this phone"
    DwPackState.UNKNOWN -> "Unknown"
}

/** The full sentence for one language's state, naming [label] so it can stand on its own. */
fun dwPackStateSentence(label: String, state: DwPackState): String = when (state) {
    DwPackState.INSTALLED ->
        "$label is installed on this phone. Dictation in $label works with no signal."
    DwPackState.DOWNLOADING ->
        "$label has been asked for and the phone has not finished fetching it. It will start " +
            "working offline once the download completes — there is nothing more to tap."
    DwPackState.DOWNLOADABLE ->
        "$label is not on this phone yet. Without it, dictation in $label needs a connection."
    DwPackState.NETWORK_ONLY ->
        "$label has no offline pack on this phone. Dictation in $label works while there is signal " +
            "and not in a courtyard without one."
    DwPackState.UNSUPPORTED ->
        "This phone's speech recogniser does not offer $label. Type the answer in, or pick another " +
            "language."
    DwPackState.UNKNOWN ->
        "This phone cannot say whether $label is installed. Android 13 added the way to ask; on " +
            "older versions dictation will simply tell you if the language is missing."
}

/**
 * What a download costs, said before the tap rather than after it.
 *
 * THE SIZE IS NOT NAMED, and that is not an omission. `SpeechRecognizer.triggerModelDownload`
 * reports no size — not in the request, not in `ModelDownloadListener`, not anywhere in
 * `RecognitionSupport` — so any figure printed here would be invented. "A speech model" with the
 * metered warning is the largest true statement available; a made-up "≈40 MB" beside a prepaid data
 * bundle would be worse than silence.
 */
fun dwDownloadCostSentence(connection: DwConnection): String = when (connection) {
    DwConnection.NONE ->
        "There is no connection, so nothing can be fetched now."
    DwConnection.METERED ->
        "This phone is on mobile data, so the download will be paid for by the megabyte. The phone " +
            "does not report the size of a speech model before it fetches it — if you are on a " +
            "prepaid bundle, wait for Wi-Fi."
    DwConnection.UNMETERED ->
        "This phone is on Wi-Fi. The phone does not report the size of a speech model before it " +
            "fetches it, but nothing here is charged by the megabyte."
}

/**
 * The one-sentence pitch, used verbatim by the first-run offer and by the settings card.
 *
 * Two clauses because there are exactly two facts a designer needs to decide: what a pack buys
 * (dictation with no signal) and what it costs (a download now, storage on the phone).
 */
const val DW_PACK_OFFER_BLURB: String =
    "A language pack lets you dictate into the workshop's fields with no signal at all — the phone " +
        "does the listening instead of a server. It costs a download now and some space on the " +
        "phone, and nothing is fetched unless you ask for it."

/**
 * What to say when the designer picks a language whose pack is missing AND there is no connection.
 *
 * Never paired with a download button — see [DwPackOffer.NO_CONNECTION].
 */
const val DW_PACK_NO_CONNECTION_SENTENCE: String =
    "The pack cannot be fetched now, because there is no connection. Dictation will use the network " +
        "engine while there is signal; in a courtyard without one, type the answer in and dictate " +
        "the rest later."

/**
 * What the platform actually promises when a download is requested, in a designer's words.
 *
 * `triggerModelDownload` is documented as an ATTEMPT: "Attempts to download the support for the
 * given recognizerIntent. This might trigger user interaction to approve the download. Callers can
 * verify the status of the request via checkRecognitionSupport." It is a request, not a promise —
 * there is no guarantee the pack arrives, and on Android 13 there is not even a callback to say
 * whether it started. Telling a designer "downloading Odia" and leaving them to discover in a
 * village that it never came is the failure this string prevents.
 */
const val DW_PACK_REQUEST_IS_A_REQUEST: String =
    "Asking is a request to the phone's speech service, not a guarantee. Android may queue it for " +
        "later, or ask you to approve it. Come back to this list to see whether it arrived."

/** Said only on Android 13, where the request goes out with no progress callback of any kind. */
const val DW_PACK_NO_PROGRESS_ON_13: String =
    "This phone runs Android 13, which cannot report download progress. The request has gone to the " +
        "speech service — tap “Check again” in a few minutes to see whether the pack arrived."

/** Said on API < 33, where there is no way to ask the platform anything. */
const val DW_PACK_CANNOT_ASK_SENTENCE: String =
    "This phone's Android version cannot be asked which speech packs are installed — that was added " +
        "in Android 13. Dictation still works: it uses the offline engine when the language is " +
        "there and the network engine when it is not, and it says which happened. To add a pack, " +
        "use the phone's own speech or keyboard settings."
