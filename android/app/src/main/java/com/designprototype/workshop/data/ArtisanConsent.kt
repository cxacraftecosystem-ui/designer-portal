package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/**
 * The artisan's own answer to "may their recordings be sent to an outside service", in the one
 * consent store both portals share (`GET`/`POST /artisans/{id}/consent`, 19 Oct 2026). Consent is
 * assumed by default and can be revoked: no answer on record is on, and switching off records the
 * refusal that stops every send of this artisan's recordings in both portals.
 *
 * The web twin is `frontend/components/forms/ArtisanConsentToggle.tsx`; the Field Repository's
 * handset twin is its `RecordingConsent.kt`.
 */
@Serializable
data class ArtisanConsentDto(
    val artisanId: String = "",
    /** "GRANTED" | "REFUSED" | "NOT_RECORDED", or a token a newer server sent. */
    val state: String = "NOT_RECORDED",
    val source: String? = null,
    val recordedAt: String? = null,
)

@Serializable
data class ArtisanConsentRequest(val state: String)

/** The switch's words: the web's `ArtisanConsentToggle.tsx`, verbatim. */
object ArtisanConsentCopy {
    const val LABEL = "Recordings may be transcribed"
    const val DESCRIPTION =
        "On by default. Switch off if the artisan does not want their recordings sent to a transcription or AI service."
    const val SAVE_FAILED = "Couldn't save. Try again."
    const val LOAD_FAILED = "Couldn't load recording consent."
    const val OWNER_ATTESTATION = "owner attestation"

    /** The switch's position: on unless the artisan refused. */
    fun granted(consent: ArtisanConsentDto): Boolean = consent.state != "REFUSED"

    /** "Agreed on 18 Oct 2026 · confirmed by the owner": the web's `artisanConsentStatus`. [date] is formatted. */
    fun status(consent: ArtisanConsentDto, date: String?): String {
        if (consent.state != "GRANTED" && consent.state != "REFUSED") return "On by default"
        val head = if (consent.state == "GRANTED") "Agreed" else "Declined"
        val by = if (consent.source == OWNER_ATTESTATION) " · confirmed by the owner" else ""
        return head + (date?.let { " on $it" } ?: "") + by
    }
}
