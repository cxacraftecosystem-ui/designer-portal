package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

@Serializable
data class ArtisanConsentDto(
    val artisanId: String = "",
    val state: String = "NOT_RECORDED",
    val source: String? = null,
    val recordedAt: String? = null,
)

@Serializable
data class ArtisanConsentRequest(val state: String)

object ArtisanConsentCopy {
    const val LABEL = "Recordings may be transcribed"
    const val DESCRIPTION =
        "On by default. Switch off if the artisan does not want their recordings sent to a transcription or AI service."
    const val SAVE_FAILED = "Couldn't save. Try again."
    const val LOAD_FAILED = "Couldn't load recording consent."
    const val OWNER_ATTESTATION = "owner attestation"

    fun granted(consent: ArtisanConsentDto): Boolean = consent.state != "REFUSED"

    fun status(consent: ArtisanConsentDto, date: String?): String {
        if (consent.state != "GRANTED" && consent.state != "REFUSED") return "On by default"
        val head = if (consent.state == "GRANTED") "Agreed" else "Declined"
        val by = if (consent.source == OWNER_ATTESTATION) " · confirmed by the owner" else ""
        return head + (date?.let { " on $it" } ?: "") + by
    }
}
