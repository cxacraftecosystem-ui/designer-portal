package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class DraftConsent(
    val decision: String = DW_CONSENT_NOT_RECORDED,
    val recordedAt: String? = null,
    val recordedById: String? = null,
    val recordedByName: String? = null,
    val synced: Boolean = false,
)

const val DW_CONSENT_NOT_RECORDED: String = "NOT_RECORDED"
const val DW_CONSENT_GRANTED: String = "GRANTED"
const val DW_CONSENT_REFUSED: String = "REFUSED"

@Serializable
data class DwConsentDecisionRequest(
    val decision: String,
    val note: String? = null,
    val recordedAt: String? = null,
)

@Serializable
data class DwConsentDecisionDto(
    val workshop: DesignWorkshopDto? = null,
    val decisions: List<DwConsentDecisionRowDto> = emptyList(),
)

@Serializable
data class DwConsentDecisionRowDto(
    val id: String = "",
    val designWorkshopId: String? = null,
    val decision: String = DW_CONSENT_NOT_RECORDED,
    val note: String? = null,
    val actorId: String? = null,
    val recordedAt: String? = null,
    val createdAt: String? = null,
)

fun dwTier3ConsentOf(token: String?): DwTier3Consent = when (token) {
    DW_CONSENT_GRANTED -> DwTier3Consent.GRANTED
    DW_CONSENT_REFUSED -> DwTier3Consent.REFUSED
    else -> DwTier3Consent.NOT_RECORDED
}

fun dwTier3ConsentToken(consent: DwTier3Consent): String = when (consent) {
    DwTier3Consent.GRANTED -> DW_CONSENT_GRANTED
    DwTier3Consent.REFUSED -> DW_CONSENT_REFUSED
    DwTier3Consent.NOT_RECORDED -> DW_CONSENT_NOT_RECORDED
}

enum class DwConsentMerge {
    KEEP_DEVICE,

    PUSH_DEVICE,

    MARK_SYNCED,

    TAKE_SERVER,
}

fun dwConsentMerge(
    onDevice: DraftConsent,
    serverDecision: String?,
    serverRecordedAt: String?,
    serverKnown: Boolean,
): DwConsentMerge {
    val here = dwTier3ConsentOf(onDevice.decision)
    val unsent = !onDevice.synced && here != DwTier3Consent.NOT_RECORDED
    if (!serverKnown) return if (unsent) DwConsentMerge.PUSH_DEVICE else DwConsentMerge.KEEP_DEVICE

    val there = dwTier3ConsentOf(serverDecision)
    if (there == here) return if (unsent) DwConsentMerge.MARK_SYNCED else DwConsentMerge.KEEP_DEVICE
    if (!unsent) return DwConsentMerge.TAKE_SERVER

    val mine = dwConsentMoment(onDevice.recordedAt)
    val theirs = dwConsentMoment(serverRecordedAt)
    return when {
        mine != null && theirs != null ->
            if (theirs > mine) DwConsentMerge.TAKE_SERVER else DwConsentMerge.PUSH_DEVICE
        there == DwTier3Consent.REFUSED -> DwConsentMerge.TAKE_SERVER
        else -> DwConsentMerge.PUSH_DEVICE
    }
}

const val DW_CONSENT_QUESTION: String =
    "On by default: recordings and dictation from this workshop may be sent to a transcription service. A clip dictated into a field is not kept; attached audio is kept with the workshop. Tell the artisan whose voice it is, and switch this off if they say no."

const val DW_CONSENT_SWITCH_LABEL: String = "Recordings may be transcribed"

const val DW_CONSENT_ROW_TITLE: String = "Sending recordings out to be written down"

fun dwConsentStateSentence(consent: DwTier3Consent, recorded: DraftConsent?): String = when (consent) {
    DwTier3Consent.GRANTED -> {
        val who = dwConsentAttribution(recorded)
        "Recordings may be sent for transcription$who." + dwConsentSyncNote(recorded)
    }

    DwTier3Consent.REFUSED -> {
        val who = dwConsentAttribution(recorded)
        "Recordings may not be sent for transcription$who. Dictation uses the phone's own recogniser." +
            DW_CONSENT_PHONE_RECOGNISER_CAVEAT + DW_CONSENT_MEDIA_GATED_TOO +
            dwConsentSyncNote(recorded)
    }

    DwTier3Consent.NOT_RECORDED ->
        "On by default: recordings may be sent for transcription until someone switches it off."
}

private const val DW_CONSENT_PHONE_RECOGNISER_CAVEAT: String =
    " Doesn't cover the phone's own dictation, which may use Google's online service unless an offline pack is installed."

private const val DW_CONSENT_MEDIA_GATED_TOO: String =
    " Also covers audio attached to this workshop as audio: it's still uploaded and kept with it, but not sent out to be written down while this stands. A “no” stops pending transcriptions."

private fun dwConsentAttribution(recorded: DraftConsent?): String {
    val name = recorded?.recordedByName?.trim()?.takeIf { it.isNotEmpty() }
    val day = dwConsentDay(recorded?.recordedAt)
    return when {
        name != null && day != null -> " — recorded by $name on $day"
        name != null -> " — recorded by $name"
        day != null -> " — recorded on $day"
        else -> ""
    }
}

private fun dwConsentSyncNote(recorded: DraftConsent?): String =
    if (recorded == null || recorded.synced) {
        ""
    } else {
        " Uploads the next time this workshop syncs; until then, dictation isn't sent out."
    }

private fun dwConsentMoment(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

private fun dwConsentDay(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val date = runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
        ?: return null
    return runCatching { date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")) }.getOrNull()
}

const val DW_CONSENT_NOT_YOURS_TO_RECORD: String =
    "Only the workshop's designer or an admin can record this."


fun dwConsentRecordedNote(
    consent: DwTier3Consent,
    synced: Boolean,
    storedOnDevice: Boolean = true,
    serverRefusal: String? = null,
): String {
    val what = when (consent) {
        DwTier3Consent.GRANTED -> "Recorded: may be sent for transcription."
        DwTier3Consent.REFUSED -> "Recorded: may not be sent out."
        DwTier3Consent.NOT_RECORDED -> "Nothing was recorded."
    }
    return when {
        synced && storedOnDevice -> "$what It is saved online."
        storedOnDevice && serverRefusal != null ->
            "$what It is saved on this phone, but not online: $serverRefusal"
        serverRefusal != null ->
            "Couldn't save that answer: $serverRefusal"
        synced -> "$what Saved online; this phone may ask again after a restart."
        storedOnDevice -> "$what Saved on this phone; it uploads the next time this workshop is opened online."
        else -> "Couldn't save that answer. Try again, or tell an admin before dictating."
    }
}
