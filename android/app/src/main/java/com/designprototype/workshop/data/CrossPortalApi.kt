package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class CrossPortalStatusDto(
    val enabled: Boolean = false,
    val sharedFields: String? = null,
)

@Serializable
data class CrossPortalLookupRequest(
    val aadhaarNumber: String? = null,
    val pehchanCardNumber: String? = null,
    val consent: Boolean = true,
)

@Serializable
data class CrossPortalFieldsDto(
    val name: String? = null,
    val localName: String? = null,
    val gender: String? = null,
    val dateOfBirth: String? = null,
    val pehchanCardAvailable: Boolean? = null,
    val pehchanCardNumber: String? = null,
    val craftStartDate: String? = null,
    val experienceYears: Int? = null,
    val experienceMonths: Int? = null,
    val phone: String? = null,
    val place: String? = null,
)

@Serializable
data class CrossPortalCraftDto(
    val code: String? = null,
    val name: String = "",
    val craftId: String? = null,
    val craftName: String? = null,
)

@Serializable
data class CrossPortalAddressDto(
    val state: String? = null,
    val district: String? = null,
    val village: String? = null,
)

@Serializable
data class CrossPortalMatchDto(
    val remoteArtisanId: String,
    val matchedBy: String? = null,
    val fields: CrossPortalFieldsDto = CrossPortalFieldsDto(),
    val identityFields: List<String> = emptyList(),
    val craft: CrossPortalCraftDto? = null,
    val statedAddress: CrossPortalAddressDto? = null,
)

@Serializable
data class CrossPortalLookupDto(val match: CrossPortalMatchDto? = null)

@Serializable
data class CrossPortalLinkRequest(
    val consent: Boolean = true,
    val expectedRemoteArtisanId: String? = null,
    val cardInput: String? = null,
)

sealed interface CrossPortalCardAnswer {
    data class Match(val match: CrossPortalMatchDto) : CrossPortalCardAnswer
    data class Existing(val artisanId: String) : CrossPortalCardAnswer
    data object NotFound : CrossPortalCardAnswer
}

@Serializable
data class CrossPortalLinkDto(
    val linked: Boolean = false,
    val remoteArtisanId: String? = null,
    val matchedBy: String? = null,
    val remoteAcknowledged: Boolean = false,
    val lastRefreshedAt: String? = null,
)

@Serializable
data class CrossPortalRefreshDto(
    val link: CrossPortalLinkDto = CrossPortalLinkDto(),
    val changed: List<String> = emptyList(),
    val artisan: CrossPortalFieldsDto? = null,
)

interface CrossPortalApi {
    @GET("cross-portal/status")
    suspend fun status(): CrossPortalStatusDto

    @POST("cross-portal/artisans/lookup")
    suspend fun lookup(@Body body: CrossPortalLookupRequest): CrossPortalLookupDto

    @GET("cross-portal/artisans/{id}/link")
    suspend fun link(@Path("id") id: String): CrossPortalLinkDto

    @POST("cross-portal/artisans/{id}/link")
    suspend fun createLink(@Path("id") id: String, @Body body: CrossPortalLinkRequest): CrossPortalLinkDto

    @POST("cross-portal/artisans/{id}/link/refresh")
    suspend fun refresh(@Path("id") id: String): CrossPortalRefreshDto

    @DELETE("cross-portal/artisans/{id}/link")
    suspend fun unlink(@Path("id") id: String): CrossPortalLinkDto

    @POST("artisan-cards/resolve")
    suspend fun resolveCard(@Body body: ResolveCardRequest): ResolveCardDto
}

class CrossPortalClient(private val api: CrossPortalApi) {
    suspend fun enabled(): Boolean = status().enabled

    suspend fun status(): CrossPortalStatusDto = runCatching { api.status() }.getOrDefault(CrossPortalStatusDto())

    suspend fun lookup(aadhaar: String?, pehchan: String?): CrossPortalMatchDto? =
        api.lookup(
            CrossPortalLookupRequest(
                aadhaarNumber = aadhaar?.filter { it.isDigit() }?.ifBlank { null },
                pehchanCardNumber = pehchan?.trim()?.ifBlank { null },
            )
        ).match

    suspend fun link(id: String): CrossPortalLinkDto = api.link(id)

    suspend fun lookupByCard(cardInput: String): CrossPortalCardAnswer {
        val answer = api.resolveCard(ResolveCardRequest(cardInput.trim(), consent = true))
        return when {
            answer.status == "FOUND" && answer.artisan != null -> CrossPortalCardAnswer.Existing(answer.artisan.id)
            answer.status == "REPLACED" && !answer.artisanId.isNullOrBlank() -> CrossPortalCardAnswer.Existing(answer.artisanId)
            answer.status == "REMOTE" && answer.match != null -> CrossPortalCardAnswer.Match(answer.match)
            else -> CrossPortalCardAnswer.NotFound
        }
    }

    suspend fun createLink(id: String, expected: String?, cardInput: String? = null): CrossPortalLinkDto =
        api.createLink(id, CrossPortalLinkRequest(expectedRemoteArtisanId = expected, cardInput = cardInput?.trim()?.ifBlank { null }))

    suspend fun refresh(id: String): CrossPortalRefreshDto = api.refresh(id)

    suspend fun unlink(id: String): CrossPortalLinkDto = api.unlink(id)

    companion object {
        fun create(context: Context): CrossPortalClient =
            CrossPortalClient(ApiClient.retrofit(TokenStore(context.applicationContext)).create(CrossPortalApi::class.java))
    }
}

data class CrossPortalPrefill(
    val name: String? = null,
    val localName: String? = null,
    val gender: String? = null,
    val dateOfBirth: String? = null,
    val craftStartDate: String? = null,
    val experienceYears: Int? = null,
    val experienceMonths: Int? = null,
    val phone: String? = null,
    val place: String? = null,
    val pehchanCardAvailable: Boolean? = null,
    val pehchanCardNumber: String? = null,
    val craftId: String? = null,
) {
    companion object {
        private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}")

        private fun day(value: String?): String? = value?.takeIf { DAY.containsMatchIn(it) }?.take(10)

        fun from(match: CrossPortalMatchDto): CrossPortalPrefill {
            val f = match.fields
            return CrossPortalPrefill(
                name = f.name?.trim()?.ifBlank { null },
                localName = f.localName?.trim()?.ifBlank { null },
                gender = f.gender?.trim()?.ifBlank { null },
                dateOfBirth = day(f.dateOfBirth),
                craftStartDate = day(f.craftStartDate),
                experienceYears = f.experienceYears,
                experienceMonths = f.experienceMonths,
                phone = f.phone?.trim()?.ifBlank { null },
                place = f.place?.trim()?.ifBlank { null },
                pehchanCardAvailable = f.pehchanCardAvailable,
                pehchanCardNumber = f.pehchanCardNumber?.trim()?.ifBlank { null },
                craftId = match.craft?.craftId,
            )
        }

        fun keepTypedPehchan(values: CrossPortalPrefill, typed: String?): CrossPortalPrefill {
            val number = typed?.trim()?.ifBlank { null }
            if (values.pehchanCardNumber != null || number == null) return values
            return values.copy(pehchanCardAvailable = true, pehchanCardNumber = number)
        }

        fun withStatedAddress(location: LocationRequest, stated: CrossPortalAddressDto): LocationRequest {
            val state = stated.state?.trim()?.ifBlank { null }
            val village = stated.village?.trim()?.ifBlank { null }
            var next = location
            if (state != null) next = next.copy(state = state, district = stated.district?.trim()?.ifBlank { null })
            if (village != null) next = next.copy(village = village)
            return next
        }

        fun fromRefreshed(artisan: CrossPortalFieldsDto, changed: List<String>): CrossPortalPrefill {
            fun has(key: String) = key in changed
            return CrossPortalPrefill(
                name = artisan.name?.trim()?.ifBlank { null }?.takeIf { has("name") },
                localName = artisan.localName?.trim()?.ifBlank { null }?.takeIf { has("localName") },
                gender = artisan.gender?.trim()?.ifBlank { null }?.takeIf { has("gender") },
                dateOfBirth = day(artisan.dateOfBirth)?.takeIf { has("dateOfBirth") },
                pehchanCardAvailable = artisan.pehchanCardAvailable?.takeIf { has("pehchanCardAvailable") },
                pehchanCardNumber = artisan.pehchanCardNumber?.trim()?.ifBlank { null }?.takeIf { has("pehchanCardNumber") },
            )
        }

        fun summary(match: CrossPortalMatchDto): String {
            val parts = mutableListOf(match.fields.name?.trim()?.ifBlank { null } ?: "Unnamed artisan")
            match.craft?.name?.takeIf { it.isNotBlank() }?.let { parts += it }
            (match.statedAddress?.district ?: match.fields.place)?.takeIf { it.isNotBlank() }?.let { parts += it }
            return parts.joinToString(" · ")
        }
    }
}

object CrossPortalCopy {
    const val HEADING = "Find in the Field Repository"
    const val SHARED_FIELDS_FALLBACK = "name, local name, gender, date of birth, Pehchan card, phone number, address, place, craft and craft experience"

    fun consent(sharedFields: String?): String =
        "The artisan agrees to share their ${sharedFields?.trim()?.ifBlank { null } ?: SHARED_FIELDS_FALLBACK} between the two portals"

    const val UNLINK_TITLE = "Unlink from the Field Repository?"
    const val UNLINK_BODY = "Refresh stops. What is already filled in stays."
    const val LINK_AFTER_SYNC = "Link it to the Field Repository from its page once it has synced."
    const val FIND = "Find"
    const val FILL = "Fill the form"
    const val NOT_THIS = "Not this person"
    const val NEED_NUMBER = "Enter the Aadhaar or Pehchan number first."
    const val NOT_FOUND = "Not found in the Field Repository, or not shared by the artisan."
    const val UNREACHABLE = "The Field Repository can't be reached. Fill the form yourself."
    const val LINKED = "Linked to the Field Repository. Name, gender, date of birth and Pehchan come from there."
    const val REFRESH = "Refresh"
    const val UNLINK = "Unlink"
    const val LINK = "Link"
    const val UPDATED = "Updated from the Field Repository."
    const val UP_TO_DATE = "Already up to date."

    const val ALREADY_HERE = "This artisan is already here."
    const val OPEN_ARTISAN = "Open the artisan"

    fun cardHeading(cardInput: String): String {
        val label = when (val parsed = ArtisanCardCode.parse(cardInput)) {
            is ArtisanCardCode.Result.Ok -> ArtisanCardCode.shortCode(parsed.card.issuer, parsed.card.code)
            else -> cardInput.trim().uppercase()
        }
        return "Field Repository card $label"
    }

    fun filled(summary: String) = "Filled from the Field Repository: $summary. Check it, then save."
    fun found(summary: String) = "Found: $summary"
}
