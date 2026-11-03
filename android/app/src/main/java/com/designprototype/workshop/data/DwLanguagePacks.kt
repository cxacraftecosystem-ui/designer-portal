package com.designprototype.workshop.data

import java.util.Locale

enum class DwPackState {
    INSTALLED,

    DOWNLOADING,

    DOWNLOADABLE,

    NETWORK_ONLY,

    NO_OFFLINE_PACK,

    UNSUPPORTED,

    UNKNOWN,
}

data class DwRecognitionSupport(
    val installedOnDevice: List<String> = emptyList(),
    val pendingOnDevice: List<String> = emptyList(),
    val supportedOnDevice: List<String> = emptyList(),
    val online: List<String> = emptyList(),
) {
    val toldUsNothing: Boolean
        get() = installedOnDevice.isEmpty() && pendingOnDevice.isEmpty() &&
            supportedOnDevice.isEmpty() && online.isEmpty()
}

internal fun dwNormalizeLanguageTag(raw: String): String =
    raw.trim().replace('_', '-').lowercase(Locale.ROOT)

internal fun dwTagCovers(reported: String, wanted: String): Boolean {
    val r = dwNormalizeLanguageTag(reported)
    val w = dwNormalizeLanguageTag(wanted)
    if (r.isEmpty() || w.isEmpty()) return false
    if (r == w) return true
    return !r.contains('-') && r == w.substringBefore('-')
}

private fun List<String>.coversTag(wanted: String): Boolean = any { dwTagCovers(it, wanted) }

fun dwPackState(tag: String, support: DwRecognitionSupport?): DwPackState {
    if (support == null || support.toldUsNothing) return DwPackState.UNKNOWN
    return when {
        support.installedOnDevice.coversTag(tag) -> DwPackState.INSTALLED
        support.pendingOnDevice.coversTag(tag) -> DwPackState.DOWNLOADING
        support.supportedOnDevice.coversTag(tag) -> DwPackState.DOWNLOADABLE
        support.online.coversTag(tag) -> DwPackState.NETWORK_ONLY
        support.online.isEmpty() -> DwPackState.NO_OFFLINE_PACK
        else -> DwPackState.UNSUPPORTED
    }
}

fun dwPackStates(tags: List<String>, support: DwRecognitionSupport?): Map<String, DwPackState> {
    val states = LinkedHashMap<String, DwPackState>(tags.size)
    tags.forEach { tag -> states[tag] = dwPackState(tag, support) }
    return states
}

enum class DwConnection {
    NONE,

    METERED,

    UNMETERED,
}

enum class DwPackOffer {
    DOWNLOAD,

    NO_CONNECTION,

    INSTALLED,

    IN_PROGRESS,

    UNAVAILABLE,

    UNKNOWN,
}

fun dwPackOffer(state: DwPackState, connection: DwConnection): DwPackOffer = when (state) {
    DwPackState.INSTALLED -> DwPackOffer.INSTALLED
    DwPackState.DOWNLOADING -> DwPackOffer.IN_PROGRESS
    DwPackState.DOWNLOADABLE ->
        if (connection == DwConnection.NONE) DwPackOffer.NO_CONNECTION else DwPackOffer.DOWNLOAD
    DwPackState.NETWORK_ONLY, DwPackState.NO_OFFLINE_PACK, DwPackState.UNSUPPORTED ->
        DwPackOffer.UNAVAILABLE
    DwPackState.UNKNOWN -> DwPackOffer.UNKNOWN
}

fun dwMayAsk(offer: DwPackOffer, requested: Boolean, refused: Boolean): Boolean =
    offer == DwPackOffer.DOWNLOAD && (!requested || refused)

fun dwPackRowWorthShowing(state: DwPackState): Boolean =
    state == DwPackState.INSTALLED ||
        state == DwPackState.DOWNLOADING ||
        state == DwPackState.DOWNLOADABLE

fun dwPackRows(states: Map<String, DwPackState>): List<String> =
    states.filterValues { dwPackRowWorthShowing(it) }.keys.toList()

fun dwPackEmptyListSentence(canAsk: Boolean): String = if (!canAsk) {
    "Dictation still works, but this Android version cannot be asked which packs are installed. Add them in the phone's own speech or keyboard settings."
} else {
    "No offline packs to add. Dictation works as normal."
}

fun dwPackStateLabel(state: DwPackState): String = when (state) {
    DwPackState.INSTALLED -> "Works offline"
    DwPackState.DOWNLOADING -> "Downloading"
    DwPackState.DOWNLOADABLE -> "Not downloaded"
    DwPackState.NETWORK_ONLY -> "Needs signal"
    DwPackState.NO_OFFLINE_PACK -> "No offline pack"
    DwPackState.UNSUPPORTED -> "Not on this phone"
    DwPackState.UNKNOWN -> "Unknown"
}

fun dwPackStateSentence(label: String, state: DwPackState): String = when (state) {
    DwPackState.INSTALLED ->
        "$label works offline."
    DwPackState.DOWNLOADING ->
        "$label downloading."
    DwPackState.DOWNLOADABLE ->
        "$label not downloaded — needs a connection to dictate."
    DwPackState.NETWORK_ONLY ->
        "$label has no offline pack — needs signal."
    DwPackState.NO_OFFLINE_PACK ->
        "No offline $label pack — dictation needs a connection."
    DwPackState.UNSUPPORTED ->
        "$label isn't supported. Type or pick another language."
    DwPackState.UNKNOWN ->
        "$label status unknown."
}

fun dwDownloadCostSentence(connection: DwConnection): String = when (connection) {
    DwConnection.NONE ->
        "Offline."
    DwConnection.METERED ->
        "On mobile data. Pack size isn't shown in advance — prefer Wi-Fi."
    DwConnection.UNMETERED ->
        "On Wi-Fi."
}

const val DW_PACK_REQUEST_IS_A_REQUEST: String =
    "Android may queue or ask to approve it. Check back here."

const val DW_PACK_NO_PROGRESS_ON_13: String =
    "Android 13 shows no progress. Tap “Check again” in a few minutes."
