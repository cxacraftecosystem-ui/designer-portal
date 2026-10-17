package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The artisan QR card's code, parsed on the handset: a scanned QR payload or a typed short code.
 *
 * QR payload `CXA1:<issuer><keyVersion>:<code>:<tag>`; short code `D-7K3QM-9XA2F-HQ`. `code` is 10
 * random Crockford characters, never derived from the artisan; the QR holds no record id and nothing
 * personal. Only the issuing server holds the key that signs the tag, so the phone never checks a tag:
 * it matches the code against the register it cached (`ArtisanDto.card`, `linkedCardCodes`) and asks
 * the server for anything else.
 *
 * The rules are `backend/app/services/artisan_card.py`'s and `frontend/lib/artisanCard.ts`'s, held
 * together by `shared/artisan-card-vectors.json` (`ArtisanCardCodeTest`). An old `DPW1:` code is
 * NOT_A_CARD here and goes on to `DwWorkshopCodes`, so old printed cards keep scanning.
 */
object ArtisanCardCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PREFIX = "CXA1"
    val ISSUERS = listOf("D", "F")
    private const val CODE_LENGTH = 10
    private const val TAG_LENGTH = 16
    private const val SHORT_LENGTH = 1 + CODE_LENGTH + 2

    /** The server's own sentence for a card that cannot be right. */
    const val CHECK_MESSAGE = "Check the code. One character is wrong."

    /** The typed box's placeholder. */
    const val PLACEHOLDER = "D-XXXXX-XXXXX-XX"

    /** The draft value an offline, unmatched scan leaves in a picker: `card:D-7K3QM-9XA2F-HQ`. */
    const val PENDING_PREFIX = "card:"

    enum class Failure { NOT_A_CARD, BAD_CHECK, BAD_ISSUER, BAD_CHARACTER }

    data class Parsed(
        val issuer: String,
        val code: String,
        val keyVersion: Int?,
        val tag: String?,
        val typed: Boolean,
    ) {
        val shortCode: String get() = shortCode(issuer, code)
    }

    sealed interface Result {
        data class Ok(val card: Parsed) : Result
        data class Refused(val reason: Failure) : Result
    }

    private fun isCrockford(text: String) = text.all { it in ALPHABET }
    private fun mapConfusables(text: String) = text.replace('I', '1').replace('L', '1').replace('O', '0')

    private fun fnv1a32(text: String): Long {
        var hash = 0x811C9DC5L
        for (byte in text.toByteArray(Charsets.US_ASCII)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash
    }

    /** The two check characters: FNV-1a 32-bit over `CXA1:<issuer>:<code>`, mod 1024. */
    fun check(issuer: String, code: String): String {
        val value = (fnv1a32("$PREFIX:$issuer:$code") % 1024).toInt()
        return "${ALPHABET[value shr 5]}${ALPHABET[value and 31]}"
    }

    fun shortCode(issuer: String, code: String): String =
        "$issuer-${code.substring(0, 5)}-${code.substring(5)}-${check(issuer, code)}"

    fun parse(raw: String?): Result {
        if (raw == null) return Result.Refused(Failure.NOT_A_CARD)
        val cleaned = raw.uppercase().filterNot { it.isWhitespace() || it == '-' || it == '.' }
        if (cleaned.isEmpty()) return Result.Refused(Failure.NOT_A_CARD)
        if (cleaned.startsWith("$PREFIX:")) {
            val parts = cleaned.split(":")
            if (parts.size != 4 || parts[1].length != 2) return Result.Refused(Failure.NOT_A_CARD)
            val head = parts[1]
            if ('U' in parts[2] || 'U' in parts[3]) return Result.Refused(Failure.BAD_CHARACTER)
            val issuer = head.substring(0, 1)
            val version = head[1]
            if (issuer !in ISSUERS) return Result.Refused(Failure.BAD_ISSUER)
            if (version !in '1'..'9') return Result.Refused(Failure.NOT_A_CARD)
            val body = mapConfusables(parts[2])
            val tag = mapConfusables(parts[3])
            if (body.length != CODE_LENGTH || tag.length != TAG_LENGTH) return Result.Refused(Failure.NOT_A_CARD)
            if (!isCrockford(body) || !isCrockford(tag)) return Result.Refused(Failure.NOT_A_CARD)
            return Result.Ok(Parsed(issuer, body, version - '0', tag, typed = false))
        }
        if (':' in cleaned) return Result.Refused(Failure.NOT_A_CARD)
        if ('U' in cleaned) return Result.Refused(Failure.BAD_CHARACTER)
        val mapped = mapConfusables(cleaned)
        if (mapped.length != SHORT_LENGTH || !isCrockford(mapped)) return Result.Refused(Failure.NOT_A_CARD)
        val issuer = mapped.substring(0, 1)
        val body = mapped.substring(1, 1 + CODE_LENGTH)
        val given = mapped.substring(1 + CODE_LENGTH)
        if (issuer !in ISSUERS) return Result.Refused(Failure.BAD_ISSUER)
        if (check(issuer, body) != given) return Result.Refused(Failure.BAD_CHECK)
        return Result.Ok(Parsed(issuer, body, null, null, typed = true))
    }

    /** Meant as a card, even if mistyped (so it is refused with [CHECK_MESSAGE], not handed to DPW1). */
    fun looksLikeCard(raw: String?): Boolean = when (val result = parse(raw)) {
        is Result.Ok -> true
        is Result.Refused -> result.reason == Failure.BAD_CHECK || result.reason == Failure.BAD_CHARACTER ||
            (result.reason == Failure.BAD_ISSUER && (raw ?: "").trim().uppercase().startsWith("$PREFIX:"))
    }

    /** The draft value an offline scan leaves behind, and its reverse. */
    fun pendingValue(card: Parsed): String = PENDING_PREFIX + card.shortCode
    fun isPending(value: String?): Boolean = value != null && value.startsWith(PENDING_PREFIX)
    fun pendingInput(value: String): String = value.removePrefix(PENDING_PREFIX)

    /** "Card D-7K3QM… links when you're online." */
    fun pendingLabel(value: String): String {
        val short = pendingInput(value)
        return "Card ${short.take(7)}… links when you're online."
    }
}

/** The letter this portal's cards carry. The Field Repository's are `F`. */
const val OWN_CARD_ISSUER = "D"

/**
 * Which cached register row a scanned card belongs to: a `D` card by the artisan's own card code, an
 * `F` card by the Field Repository code its link holds. Pure, so the offline match is testable
 * without a repository.
 */
fun matchCardInRegister(card: ArtisanCardCode.Parsed, register: List<ArtisanDto>): ArtisanDto? =
    if (card.issuer == OWN_CARD_ISSUER) register.firstOrNull { it.card?.code == card.code }
    else register.firstOrNull { card.code in it.linkedCardCodes }

/** The server's one sentence for every kind of miss. */
const val CARD_NO_MATCH = "No artisan matches this card."

/** What a scanned card turned out to be, on the phone. */
sealed interface CardResolution {
    /** The artisan, from the cached register or the server. [detail] is the second line a picker shows. */
    data class Found(val artisanId: String, val name: String, val detail: String?) : CardResolution
    /** The card was reissued; the artisan it belonged to can still be opened. */
    data class Replaced(val artisanId: String) : CardResolution
    /** A Field Repository artisan not linked here. Adding them happens on the web, with the consent tick. */
    data object Remote : CardResolution
    /** No signal and no match in the cached register: kept as `card:<shortCode>` until the sync resolves it. */
    data class Pending(val card: ArtisanCardCode.Parsed) : CardResolution
    /** The server's sentence (or the check sentence for a mistyped code). */
    data class Refused(val message: String) : CardResolution
}

/** "This card was replaced." and "…not here yet." — the web's words (`lib/artisanCardScan.ts`). */
const val CARD_REPLACED = "This card was replaced."
const val CARD_REMOTE = "This artisan is in the Field Repository and not here yet."

/*
 * THE CACHED REGISTER KEEPS THE CARD. `loadArtisanRegister` stores each artisan as a
 * `DwReferenceOption`, which has room for id, name, place and craft only; the card rides in its
 * `data` under underscore keys no entity field is named, so stage hydration never picks them up.
 */
private const val KEY_CODE = "_cardCode"
private const val KEY_SHORT = "_cardShortCode"
private const val KEY_TOKEN = "_cardToken"
private const val KEY_LINKED = "_linkedCardCodes"
private const val KEY_LAST4 = "_aadhaarLast4"

/** The card fields of [artisan] as an option's `data`. Empty when it has no card yet. */
fun artisanCardData(artisan: ArtisanDto): JsonObject {
    val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    artisan.card?.let {
        out[KEY_CODE] = JsonPrimitive(it.code)
        out[KEY_SHORT] = JsonPrimitive(it.shortCode)
        out[KEY_TOKEN] = JsonPrimitive(it.token)
    }
    if (artisan.linkedCardCodes.isNotEmpty()) out[KEY_LINKED] = JsonArray(artisan.linkedCardCodes.map(::JsonPrimitive))
    artisan.aadhaarLast4?.let { out[KEY_LAST4] = JsonPrimitive(it) }
    return JsonObject(out)
}

/** [artisan] with the card fields read back out of a cached option's `data`. */
fun withCardData(artisan: ArtisanDto, data: JsonObject): ArtisanDto {
    fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val code = text(KEY_CODE)
    val short = text(KEY_SHORT)
    val token = text(KEY_TOKEN)
    val linked = (data[KEY_LINKED] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    return artisan.copy(
        card = if (code != null && short != null && token != null) ArtisanCardWireDto(code, short, token) else null,
        linkedCardCodes = linked,
        aadhaarLast4 = text(KEY_LAST4),
    )
}
